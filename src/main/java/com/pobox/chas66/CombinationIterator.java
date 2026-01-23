package com.pobox.chas66;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lazy iterator for generating n-way combinations on-demand.
 * Implements caching to avoid regenerating combinations during repeated score calculations.
 *
 * This approach improves memory efficiency by:
 * 1. Spreading generation cost over time (not all upfront)
 * 2. Enabling early termination if solver finds solution before all tuples are generated
 * 3. Providing memory usage metrics for monitoring
 *
 * Thread-safety: This class is NOT thread-safe. Should be used from a single thread.
 */
public class CombinationIterator implements Iterable<Combination> {
    private final List<Dimension> dimensions;
    private final int nWay;

    // Cache of generated combinations (lazy populated)
    private final List<Combination> cachedCombinations;

    // Iterator state for lazy generation
    private Iterator<Set<Dimension>> dimensionSubsetIterator;
    private Iterator<List<Character>> currentCartesianProduct;
    private List<Dimension> currentSortedDims;
    private boolean fullyGenerated;

    /**
     * Creates a new combination iterator for n-way tuple generation.
     *
     * @param dimensions The dimensions to generate combinations from
     * @param nWay The n-way coverage level (2 for pairwise, 3 for 3-way, etc.)
     */
    public CombinationIterator(List<Dimension> dimensions, int nWay) {
        this.dimensions = new ArrayList<>(dimensions);
        this.nWay = nWay;
        this.cachedCombinations = new ArrayList<>();
        this.fullyGenerated = false;

        initializeIterators();
    }

    /**
     * Initializes the internal iterators for lazy generation.
     */
    private void initializeIterators() {
        // Generate all n-way subsets of dimensions
        Set<Set<Dimension>> dimensionSubsets = Sets.combinations(
            ImmutableSet.copyOf(dimensions),
            nWay
        );
        this.dimensionSubsetIterator = dimensionSubsets.iterator();
        this.currentCartesianProduct = null;
        this.currentSortedDims = null;
    }

    /**
     * Returns an iterator that generates combinations on-demand.
     * Multiple calls to this method will return iterators over the same cached data.
     */
    @Override
    public Iterator<Combination> iterator() {
        return new Iterator<Combination>() {
            private int cacheIndex = 0;

            @Override
            public boolean hasNext() {
                // If we have cached combinations, check cache first
                if (cacheIndex < cachedCombinations.size()) {
                    return true;
                }

                // If fully generated, no more combinations
                if (fullyGenerated) {
                    return false;
                }

                // Try to generate next combination
                return hasMoreToGenerate();
            }

            @Override
            public Combination next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                // Return from cache if available
                if (cacheIndex < cachedCombinations.size()) {
                    return cachedCombinations.get(cacheIndex++);
                }

                // Generate next combination and cache it
                Combination combo = generateNext();
                cachedCombinations.add(combo);
                cacheIndex++;
                return combo;
            }
        };
    }

    /**
     * Checks if there are more combinations to generate.
     * Updates fullyGenerated flag when exhausted.
     */
    private boolean hasMoreToGenerate() {
        // Check current cartesian product iterator
        if (currentCartesianProduct != null && currentCartesianProduct.hasNext()) {
            return true;
        }

        // Check if there are more dimension subsets
        boolean hasMore = dimensionSubsetIterator.hasNext();

        // Update fullyGenerated flag if we've exhausted everything
        if (!hasMore && (currentCartesianProduct == null || !currentCartesianProduct.hasNext())) {
            fullyGenerated = true;
        }

        return hasMore;
    }

    /**
     * Generates the next combination.
     */
    private Combination generateNext() {
        // If current cartesian product is exhausted, move to next dimension subset
        while (currentCartesianProduct == null || !currentCartesianProduct.hasNext()) {
            if (!dimensionSubsetIterator.hasNext()) {
                fullyGenerated = true;
                throw new NoSuchElementException("No more combinations to generate");
            }

            Set<Dimension> dimSubset = dimensionSubsetIterator.next();
            currentSortedDims = dimSubset.stream()
                .sorted(Comparator.comparingInt(Dimension::getId))
                .collect(Collectors.toList());

            List<Set<Character>> featureSets = currentSortedDims.stream()
                .map(d -> CharacterEncoding.getRangeAsSet(d.getSize()))
                .collect(Collectors.toList());

            currentCartesianProduct = Sets.cartesianProduct(featureSets).iterator();
        }

        // Generate combination from current cartesian product
        List<Character> product = currentCartesianProduct.next();
        Map<Integer, Character> assignmentMap = new HashMap<>();

        for (int i = 0; i < currentSortedDims.size(); i++) {
            assignmentMap.put(currentSortedDims.get(i).getId(), product.get(i));
        }

        return new Combination(assignmentMap);
    }

    /**
     * Forces full generation of all combinations (populates cache completely).
     * Use this when you need all combinations upfront.
     *
     * @return The total number of combinations generated
     */
    public int generateAll() {
        Iterator<Combination> iter = iterator();
        while (iter.hasNext()) {
            iter.next();
        }
        fullyGenerated = true; // Ensure flag is set
        return cachedCombinations.size();
    }

    /**
     * Returns all currently cached combinations as a Set.
     * This is useful for compatibility with existing code that expects Set<Combination>.
     *
     * @param fullyGenerate If true, generates all combinations first; if false, returns only cached ones
     */
    public Set<Combination> toSet(boolean fullyGenerate) {
        if (fullyGenerate) {
            generateAll();
        }
        return new HashSet<>(cachedCombinations);
    }

    /**
     * Returns all combinations as a List (maintains insertion order).
     *
     * @param fullyGenerate If true, generates all combinations first; if false, returns only cached ones
     */
    public List<Combination> toList(boolean fullyGenerate) {
        if (fullyGenerate) {
            generateAll();
        }
        return new ArrayList<>(cachedCombinations);
    }

    /**
     * Returns the number of combinations currently in the cache.
     */
    public int getCachedCount() {
        return cachedCombinations.size();
    }

    /**
     * Returns true if all combinations have been generated.
     */
    public boolean isFullyGenerated() {
        return fullyGenerated;
    }

    /**
     * Estimates the total number of combinations that will be generated.
     * This is C(d, n) * product(f_i^n) where d = dimensions, n = nWay, f_i = features per dimension.
     *
     * Note: This is an estimate and may be expensive to compute for large dimension sets.
     */
    public long estimateTotalCount() {
        // C(d, n) - number of ways to choose n dimensions
        long dimensionCombinations = binomialCoefficient(dimensions.size(), nWay);

        // For each dimension combination, count feature combinations
        // This is an approximation assuming uniform feature counts
        long avgFeaturesPerDim = (long) dimensions.stream()
            .mapToInt(Dimension::getSize)
            .average()
            .orElse(2.0);

        long avgFeatureCombinations = 1;
        for (int i = 0; i < nWay; i++) {
            avgFeatureCombinations *= avgFeaturesPerDim;
        }

        return dimensionCombinations * avgFeatureCombinations;
    }

    /**
     * Calculates binomial coefficient C(n, k) = n! / (k! * (n-k)!)
     */
    private long binomialCoefficient(int n, int k) {
        if (k > n) return 0;
        if (k == 0 || k == n) return 1;

        // Optimize by using the smaller of k and n-k
        k = Math.min(k, n - k);

        long result = 1;
        for (int i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return result;
    }

    /**
     * Returns memory usage statistics as a formatted string.
     */
    public String getMemoryStats() {
        return String.format(
            "CombinationIterator: cached=%d, fullyGenerated=%s, estimated=%d",
            getCachedCount(),
            isFullyGenerated(),
            estimateTotalCount()
        );
    }
}
