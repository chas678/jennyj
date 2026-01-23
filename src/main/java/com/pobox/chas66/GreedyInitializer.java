package com.pobox.chas66;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class GreedyInitializer {
    private final Random random;

    public GreedyInitializer() {
        this.random = new Random();
    }

    public GreedyInitializer(long seed) {
        this.random = new Random(seed);
    }

    public PairwiseSolution initialize(List<Dimension> dimensions,
                                       Set<Combination> required,
                                       List<ForbiddenCombination> forbidden) {
        // Prioritize hard-to-cover (rare) combinations first for better greedy selection
        Set<Combination> uncovered = prioritizeHardCombinations(required, dimensions);
        List<TestRun> runs = new ArrayList<>();
        int idCounter = 0;
        int successfulRows = 0;

        while (!uncovered.isEmpty()) {
            Map<Integer, Character> rowMap = new HashMap<>();

            // 1. Horizontal Growth: Pick the best feature for each dimension
            for (Dimension dim : dimensions) {
                Character chosen = pickBestValidFeature(dim, rowMap, uncovered, forbidden);

                if (chosen == null) {
                    break;
                }
                rowMap.put(dim.getId(), chosen);
            }

            // 2. Construct the TestRun object
            TestRun run = new TestRun();
            run.setId(idCounter++);
            run.setActive(true);

            // 3. Map assignments to the TestRun
            List<FeatureAssignment> assignments = dimensions.stream().map(d -> {
                FeatureAssignment fa = new FeatureAssignment(run, run.getId() + "-" + d.getId(), d);
                fa.setValue(rowMap.get(d.getId()));
                return fa;
            }).toList();

            run.setAssignments(assignments);

            // 4. Final validation: Ensure the generated row doesn't violate any -w rules
            if (forbidden.stream().noneMatch(f -> f.isViolatedBy(run))) {
                runs.add(run);
                uncovered.removeIf(combo -> CoverageUtil.isRunCoveringCombo(combo, run));
                successfulRows++;
            }

            // 5. Safety breaks to prevent infinite loops in impossible constraint scenarios
            if (successfulRows > 1000 || idCounter > 2000) {
                break;
            }
        }

        return new PairwiseSolution(dimensions, new ArrayList<>(required), runs, null, forbidden);
    }

    private Character pickBestValidFeature(Dimension dim, Map<Integer, Character> partialRow,
                                      Set<Combination> uncovered, List<ForbiddenCombination> forbidden) {
        int maxGain = -1;
        List<Character> equalBestOptions = new ArrayList<>();

        for (int i = 0; i < dim.getSize(); i++) {
            char candidate = getCharName(i);
            if (isCandidateForbidden(dim.getId(), candidate, partialRow, forbidden)) continue;
            int gain = countPotentialNewCoverage(dim.getId(), candidate, partialRow, uncovered);

            if (gain > maxGain) {
                maxGain = gain;
                equalBestOptions.clear();
                equalBestOptions.add(candidate);
            } else if (gain == maxGain && gain >= 0) {
                equalBestOptions.add(candidate);
            }
        }

        // Randomly pick between equally good options to diversify the starting suite
        if (!equalBestOptions.isEmpty()) {
            return equalBestOptions.get(random.nextInt(equalBestOptions.size()));
        }
        return null;
    }

    private boolean isCandidateForbidden(int dimId, char feature, Map<Integer, Character> partialRow,
                                         List<ForbiddenCombination> forbidden) {
        for (ForbiddenCombination f : forbidden) {
            if (f.isViolatedByPartialRow(partialRow, dimId, feature)) return true;
        }
        return false;
    }

    /**
     * Counts how many uncovered combinations would potentially be covered by choosing
     * the given feature for the given dimension, given the current partial row.
     *
     * Optimized to use direct iteration instead of streams for better performance.
     * This method is called for every candidate feature during greedy initialization,
     * so performance is critical.
     *
     * @param dimId The dimension ID we're assigning a feature to
     * @param feature The candidate feature character
     * @param partialRow The row being built so far (dimension ID -> feature)
     * @param uncovered The set of combinations not yet covered
     * @return Count of combinations that would be covered by this choice
     */
    private int countPotentialNewCoverage(int dimId, char feature, Map<Integer, Character> partialRow, Set<Combination> uncovered) {
        int count = 0;

        for (Combination combo : uncovered) {
            Map<Integer, Character> comboAssignments = combo.getAssignments();

            // Quick filter: if this combo requires a different feature for dimId, skip it
            Character requiredFeature = comboAssignments.get(dimId);
            if (requiredFeature != null && requiredFeature != feature) {
                continue;
            }

            // Check if all other dimensions in the combo are compatible with partialRow
            boolean isCompatible = true;

            for (Map.Entry<Integer, Character> entry : comboAssignments.entrySet()) {
                int comboDimId = entry.getKey();

                // Skip the dimension we're currently assigning
                if (comboDimId == dimId) {
                    continue;
                }

                // Check if partialRow has a value for this dimension
                Character partialValue = partialRow.get(comboDimId);

                // If partialRow has a value and it doesn't match, combo is incompatible
                if (partialValue != null && !partialValue.equals(entry.getValue())) {
                    isCompatible = false;
                    break;
                }
            }

            if (isCompatible) {
                count++;
            }
        }

        return count;
    }

    private char getCharName(int index) {
        return CharacterEncoding.indexToChar(index);
    }

    /**
     * Prioritizes combinations by rarity score (sum of feature rarities).
     * This improves greedy performance by covering hard-to-cover tuples first,
     * reducing the risk of difficult-to-cover combinations being left until the end.
     *
     * @param combinations The combinations to prioritize
     * @param dimensions The dimensions (used for frequency calculation)
     * @return LinkedHashSet preserving the rarity-based order
     */
    private Set<Combination> prioritizeHardCombinations(Set<Combination> combinations,
                                                        List<Dimension> dimensions) {
        // Count feature frequencies across all combinations
        Map<String, Integer> featureFrequency = new HashMap<>();
        for (Combination combo : combinations) {
            for (Map.Entry<Integer, Character> entry : combo.getAssignments().entrySet()) {
                String key = entry.getKey() + ":" + entry.getValue();
                featureFrequency.merge(key, 1, Integer::sum);
            }
        }

        // Sort combinations by rarity score (lower score = rarer = higher priority)
        List<Combination> sortedCombos = combinations.stream()
            .sorted(Comparator.comparingInt(c -> getRarityScore(c, featureFrequency)))
            .collect(Collectors.toList());

        // Return as LinkedHashSet to preserve order
        return new LinkedHashSet<>(sortedCombos);
    }

    /**
     * Calculates the rarity score for a combination.
     * Lower score = rarer combination (contains less common features).
     *
     * @param combo The combination to score
     * @param frequencies Map of feature frequencies
     * @return Sum of feature frequencies (lower = rarer)
     */
    private int getRarityScore(Combination combo, Map<String, Integer> frequencies) {
        return combo.getAssignments().entrySet().stream()
            .mapToInt(e -> frequencies.getOrDefault(e.getKey() + ":" + e.getValue(), 999))
            .sum();
    }
}