package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Greedy construction of an initial covering array, ported from the Go
 * reference generator (jennygo {@code jenny.go}). Given the allowed n-tuples,
 * it emits a small set of complete, without-obeying test rows that covers
 * every coverable tuple.
 *
 * <p>Algorithm (AETG-style greedy), per emitted row:
 * <ol>
 *   <li><b>Seed selection</b> — deterministically pick the most-constrained
 *       still-uncovered tuple: the one whose coordinates participate in the
 *       fewest remaining uncovered tuples (Go {@code pickSeedPos}). This is
 *       NOT a uniform-random draw — the old implementation computed a rarity
 *       order and then ignored it.</li>
 *   <li><b>Complete-then-improve candidates</b> — for each seed, build
 *       {@link #GROUP_SIZE} candidate rows. Each pins the seed's cells, fills
 *       the remaining dimensions at random, then repairs any without violation
 *       by hill-climbing ({@code trySatisfy}/{@code obeyWithouts}); the row is
 *       then greedily improved by a multi-pass {@code maximize} that reassigns
 *       each free dimension to the feature covering the most currently
 *       uncovered tuples, with shuffled dimension order and random tie-breaks.
 *       Every candidate is a COMPLETE, LEGAL row — a row that violates a
 *       without is never produced (there is no {@code feature(0)} fallback).</li>
 *   <li>Keep the candidate covering the most currently-uncovered tuples,
 *       commit it, and shrink the uncovered set.</li>
 * </ol>
 *
 * <p>Coverage counting is postings-indexed: for each (dimension, feature) we
 * pre-build the list of tuple ids containing it, so counting a row's new
 * coverage touches only the tuples that share one of its cells rather than
 * scanning the whole tuple list (the old implementation's ~1.3M checks/row).
 *
 * <p>After construction a cheap {@code removeRedundant} pass drops any row all
 * of whose covered tuples are also covered by another row (coverage-preserving,
 * strictly smaller).
 */
public class GreedyInitializer {

    /** Complete candidate rows generated per uncovered seed (Go {@code groupSize}). */
    private static final int GROUP_SIZE = 8;
    /** Random restarts when repairing a candidate's withouts (Go {@code maxIters}). */
    private static final int MAX_ITERS = 12;
    /** Hill-climb passes tolerated without progress in without repair (Go {@code maxNoProgress}). */
    private static final int MAX_NO_PROGRESS = 2;

    /**
     * Build initial test cases using the greedy covering-array construction.
     * Each returned map assigns a feature to every dimension (a complete,
     * without-obeying row).
     */
    public static List<Map<Dimension, Feature>> buildInitialTests(
            List<Dimension> dimensions,
            List<AllowedTuple> tuples,
            List<Without> withouts,
            Random random) {

        if (tuples.isEmpty()) {
            return new ArrayList<>();
        }
        Engine engine = new Engine(dimensions, tuples, withouts, random);
        engine.run();
        engine.removeRedundant();
        return engine.toFeatureMaps();
    }

    /**
     * Integer-indexed greedy engine. Dimensions are addressed by their
     * position {@code d} in the supplied {@code dimensions} list; features by
     * their index {@code f} within that dimension. Tuple coordinates are held
     * flat: tuple {@code ti} occupies {@code tupDim[ti*n .. ti*n+n]} /
     * {@code tupFeat[...]}.
     */
    private static final class Engine {

        private final List<Dimension> dimensions;
        private final int ndim;
        private final int[] dimSize;
        private final int n;
        private final Random rnd;

        // Flat tuple coordinates.
        private final int nt;
        private final int[] tupDim;
        private final int[] tupFeat;

        // postings[d][f] = ids of every tuple containing (d,f) (static).
        private final int[][][] postings;
        // uncovCount[d][f] = number of still-uncovered tuples containing (d,f).
        private final int[][] uncovCount;
        private final boolean[] covered;

        // Working set of still-uncovered tuple ids (compacted on commit).
        private int[] uncov;
        private int uncovLen;

        // Epoch-stamped visit scratch for dedup during coverage counting.
        private final int[] visit;
        private int epoch;

        // Internal withouts. Restriction wi touches dimensions wDims[wi] (sorted),
        // and wFeat[wi][k] is the forbidden-feature membership mask for wDims[wi][k].
        private final int[][] wDims;
        private final boolean[][][] wFeat;
        private final int[][] byDim; // byDim[d] = ids of withouts touching dimension d

        private final List<int[]> tests = new ArrayList<>();

        Engine(List<Dimension> dimensions, List<AllowedTuple> tuples,
               List<Without> withouts, Random random) {
            this.dimensions = dimensions;
            this.ndim = dimensions.size();
            this.rnd = random;
            this.dimSize = new int[ndim];
            Map<Dimension, Integer> dimPos = new HashMap<>(ndim * 2);
            for (int d = 0; d < ndim; d++) {
                Dimension dim = dimensions.get(d);
                dimPos.put(dim, d);
                dimSize[d] = dim.size();
            }

            this.n = tuples.get(0).size();
            this.nt = tuples.size();
            this.tupDim = new int[nt * n];
            this.tupFeat = new int[nt * n];
            int[] coord = new int[n];
            for (int ti = 0; ti < nt; ti++) {
                List<Feature> feats = tuples.get(ti).features();
                // Collect (dimPos, featIdx) and sort ascending by dimension.
                int[][] pf = new int[n][2];
                for (int k = 0; k < n; k++) {
                    Feature f = feats.get(k);
                    pf[k][0] = dimPos.get(f.dimension());
                    pf[k][1] = f.featureIndex();
                }
                java.util.Arrays.sort(pf, (a, b) -> Integer.compare(a[0], b[0]));
                for (int k = 0; k < n; k++) {
                    tupDim[ti * n + k] = pf[k][0];
                    tupFeat[ti * n + k] = pf[k][1];
                }
            }

            // Build postings and uncovered counts.
            this.postings = new int[ndim][][];
            this.uncovCount = new int[ndim][];
            int[][] fill = new int[ndim][];
            for (int d = 0; d < ndim; d++) {
                postings[d] = new int[dimSize[d]][];
                uncovCount[d] = new int[dimSize[d]];
                fill[d] = new int[dimSize[d]];
            }
            for (int ti = 0; ti < nt; ti++) {
                for (int k = 0; k < n; k++) {
                    uncovCount[tupDim[ti * n + k]][tupFeat[ti * n + k]]++;
                }
            }
            for (int d = 0; d < ndim; d++) {
                for (int f = 0; f < dimSize[d]; f++) {
                    postings[d][f] = new int[uncovCount[d][f]];
                }
            }
            for (int ti = 0; ti < nt; ti++) {
                for (int k = 0; k < n; k++) {
                    int d = tupDim[ti * n + k];
                    int f = tupFeat[ti * n + k];
                    postings[d][f][fill[d][f]++] = ti;
                }
            }

            this.covered = new boolean[nt];
            this.visit = new int[nt];
            this.uncov = new int[nt];
            for (int ti = 0; ti < nt; ti++) {
                uncov[ti] = ti;
            }
            this.uncovLen = nt;

            // Internal withouts.
            int w = withouts.size();
            this.wDims = new int[w][];
            this.wFeat = new boolean[w][][];
            List<List<Integer>> byDimList = new ArrayList<>(ndim);
            for (int d = 0; d < ndim; d++) {
                byDimList.add(new ArrayList<>());
            }
            for (int wi = 0; wi < w; wi++) {
                Without without = withouts.get(wi);
                Set<Dimension> wdims = without.dimensions();
                int[] ds = new int[wdims.size()];
                int idx = 0;
                for (Dimension dim : wdims) {
                    ds[idx++] = dimPos.get(dim);
                }
                java.util.Arrays.sort(ds);
                wDims[wi] = ds;
                wFeat[wi] = new boolean[ds.length][];
                for (int k = 0; k < ds.length; k++) {
                    int d = ds[k];
                    boolean[] mask = new boolean[dimSize[d]];
                    for (Feature f : without.featuresFor(dimensions.get(d))) {
                        mask[f.featureIndex()] = true;
                    }
                    wFeat[wi][k] = mask;
                    byDimList.get(d).add(wi);
                }
            }
            this.byDim = new int[ndim][];
            for (int d = 0; d < ndim; d++) {
                List<Integer> l = byDimList.get(d);
                int[] arr = new int[l.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = l.get(i);
                }
                byDim[d] = arr;
            }
        }

        // ---- main loop ---------------------------------------------------

        void run() {
            while (uncovLen > 0) {
                int seed = pickSeed();
                int[] best = bestCandidate(seed);
                if (best == null) {
                    excludeSeed(seed);
                } else {
                    commit(best);
                }
            }
        }

        /**
         * Deterministically pick the most-constrained uncovered tuple: the one
         * whose coordinates participate in the fewest remaining uncovered
         * tuples (min sum of postings sizes). Ties resolve to the first such
         * tuple in iteration order (Go {@code pickSeedPos}).
         */
        private int pickSeed() {
            int bestTi = uncov[0];
            long bestScore = Long.MAX_VALUE;
            for (int i = 0; i < uncovLen; i++) {
                int ti = uncov[i];
                long score = 0;
                int base = ti * n;
                for (int k = 0; k < n; k++) {
                    score += uncovCount[tupDim[base + k]][tupFeat[base + k]];
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestTi = ti;
                }
            }
            return bestTi;
        }

        /**
         * Build {@link #GROUP_SIZE} complete rows covering the seed tuple;
         * return the one covering the most additional uncovered tuples, or
         * {@code null} if none could satisfy the restrictions.
         */
        private int[] bestCandidate(int seed) {
            int[] best = null;
            int bestScore = -1;
            for (int i = 0; i < GROUP_SIZE; i++) {
                int[] t = makeTest(seed);
                if (t == null) {
                    continue;
                }
                int s = countNewCoverage(t);
                if (s > bestScore) {
                    bestScore = s;
                    best = t;
                }
            }
            return best;
        }

        /**
         * One complete, restriction-obeying row that covers the seed tuple and
         * greedily maximizes additional coverage. {@code null} if the seed's
         * pinned cells cannot be completed into a legal row.
         */
        private int[] makeTest(int seed) {
            int base = seed * n;
            int[] t = new int[ndim];
            boolean[] mut = new boolean[ndim];
            java.util.Arrays.fill(mut, true);
            for (int k = 0; k < n; k++) {
                int d = tupDim[base + k];
                t[d] = tupFeat[base + k];
                mut[d] = false;
            }
            if (!trySatisfy(t, mut, MAX_ITERS)) {
                return null;
            }
            maximize(t, mut);
            return t;
        }

        /**
         * Randomize the mutable dimensions of {@code t} and repair restrictions,
         * up to {@code iters} attempts. Returns true once {@code t} obeys every
         * restriction (Go {@code trySatisfy}).
         */
        private boolean trySatisfy(int[] t, boolean[] mut, int iters) {
            for (int iter = 0; iter < iters; iter++) {
                for (int d = 0; d < ndim; d++) {
                    if (mut[d]) {
                        t[d] = rnd.nextInt(dimSize[d]);
                    }
                }
                if (wDims.length == 0 || obeyWithouts(t, mut)) {
                    if (!anyViolated(t)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Hill-climb the mutable dimensions to drive the number of violated
         * restrictions to zero (Go {@code obeyWithouts}). Sidesteps among
         * equally-good features and gives up after {@link #MAX_NO_PROGRESS}
         * fruitless passes.
         */
        private boolean obeyWithouts(int[] t, boolean[] mut) {
            if (!anyViolated(t)) {
                return true;
            }
            int[] dims = new int[ndim];
            int dimsLen = 0;
            for (int d = 0; d < ndim; d++) {
                if (mut[d] && byDim[d].length > 0) {
                    dims[dimsLen++] = d;
                }
            }
            int[] best = new int[maxDimSize()];
            int noProgress = 0;
            while (noProgress < MAX_NO_PROGRESS) {
                shuffle(dims, dimsLen);
                boolean allZero = true;
                boolean progressed = false;
                for (int di = 0; di < dimsLen; di++) {
                    int d = dims[di];
                    int count = countWithoutsTouching(t, d);
                    int bestLen = 0;
                    int bestCount = count + 1;
                    for (int f = 0; f < dimSize[d]; f++) {
                        t[d] = f;
                        int c = countWithoutsTouching(t, d);
                        if (c < bestCount) {
                            bestCount = c;
                            bestLen = 0;
                            best[bestLen++] = f;
                        } else if (c == bestCount) {
                            best[bestLen++] = f;
                        }
                    }
                    if (bestCount < count) {
                        progressed = true;
                    }
                    t[d] = best[rnd.nextInt(bestLen)];
                    if (bestCount > 0) {
                        allZero = false;
                    }
                }
                if (allZero) {
                    return true;
                }
                if (progressed) {
                    noProgress = 0;
                } else {
                    noProgress++;
                }
            }
            return !anyViolated(t);
        }

        /**
         * Repeatedly reassign each mutable dimension to the feature covering
         * the most currently-uncovered tuples, staying restriction-free, until
         * a full pass yields no improvement (Go {@code maximize}).
         */
        private void maximize(int[] t, boolean[] mut) {
            int[] order = new int[ndim];
            int orderLen = 0;
            for (int d = 0; d < ndim; d++) {
                if (mut[d]) {
                    order[orderLen++] = d;
                }
            }
            int[] best = new int[maxDimSize()];
            while (true) {
                shuffle(order, orderLen);
                boolean improved = false;
                for (int oi = 0; oi < orderLen; oi++) {
                    int d = order[oi];
                    int cur = t[d];
                    int curCov = coverCount(t, d, cur);
                    int bestLen = 0;
                    best[bestLen++] = cur;
                    int bestCov = curCov;
                    for (int f = 0; f < dimSize[d]; f++) {
                        if (f == cur) {
                            continue;
                        }
                        t[d] = f;
                        if (violatesTouching(t, d)) {
                            continue;
                        }
                        int c = coverCount(t, d, f);
                        if (c > bestCov) {
                            bestCov = c;
                            bestLen = 0;
                            best[bestLen++] = f;
                        } else if (c == bestCov) {
                            best[bestLen++] = f;
                        }
                    }
                    t[d] = best[rnd.nextInt(bestLen)];
                    if (bestCov > curCov) {
                        improved = true;
                    }
                }
                if (!improved) {
                    return;
                }
            }
        }

        // ---- coverage counting ------------------------------------------

        /** Uncovered tuples containing (d,f) that the complete row {@code t} covers. */
        private int coverCount(int[] t, int d, int f) {
            int c = 0;
            for (int ti : postings[d][f]) {
                if (covered[ti]) {
                    continue;
                }
                int base = ti * n;
                boolean ok = true;
                for (int k = 0; k < n; k++) {
                    int dd = tupDim[base + k];
                    if (dd != d && t[dd] != tupFeat[base + k]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    c++;
                }
            }
            return c;
        }

        /** Distinct still-uncovered tuples the complete row {@code t} covers. */
        private int countNewCoverage(int[] t) {
            epoch++;
            int c = 0;
            for (int d = 0; d < ndim; d++) {
                for (int ti : postings[d][t[d]]) {
                    if (covered[ti] || visit[ti] == epoch) {
                        continue;
                    }
                    if (testCovers(t, ti)) {
                        visit[ti] = epoch;
                        c++;
                    }
                }
            }
            return c;
        }

        private boolean testCovers(int[] t, int ti) {
            int base = ti * n;
            for (int k = 0; k < n; k++) {
                if (t[tupDim[base + k]] != tupFeat[base + k]) {
                    return false;
                }
            }
            return true;
        }

        /** Record {@code t}, mark the uncovered tuples it covers, compact the working set. */
        private void commit(int[] t) {
            tests.add(t);
            epoch++;
            for (int d = 0; d < ndim; d++) {
                for (int ti : postings[d][t[d]]) {
                    if (covered[ti] || visit[ti] == epoch) {
                        continue;
                    }
                    if (testCovers(t, ti)) {
                        visit[ti] = epoch;
                        covered[ti] = true;
                        int base = ti * n;
                        for (int k = 0; k < n; k++) {
                            uncovCount[tupDim[base + k]][tupFeat[base + k]]--;
                        }
                    }
                }
            }
            compact();
        }

        /** Drop a seed that cannot be completed into a legal row (keeps the loop finite). */
        private void excludeSeed(int seed) {
            if (!covered[seed]) {
                covered[seed] = true;
                int base = seed * n;
                for (int k = 0; k < n; k++) {
                    uncovCount[tupDim[base + k]][tupFeat[base + k]]--;
                }
            }
            compact();
        }

        private void compact() {
            int out = 0;
            for (int i = 0; i < uncovLen; i++) {
                int ti = uncov[i];
                if (!covered[ti]) {
                    uncov[out++] = ti;
                }
            }
            uncovLen = out;
        }

        // ---- redundant-row removal --------------------------------------

        /**
         * Drop any row all of whose covered tuples are also covered by another
         * row. Coverage-preserving: a row is removed only while every tuple it
         * covers still has coverage count >= 2, so each tuple keeps >= 1 cover.
         */
        void removeRedundant() {
            int m = tests.size();
            if (m == 0) {
                return;
            }
            int[] coverCnt = new int[nt];
            int[][] covLists = new int[m][];
            for (int idx = 0; idx < m; idx++) {
                int[] t = tests.get(idx);
                epoch++;
                int[] buf = new int[nt == 0 ? 0 : Math.min(nt, 64)];
                int len = 0;
                for (int d = 0; d < ndim; d++) {
                    for (int ti : postings[d][t[d]]) {
                        if (visit[ti] == epoch) {
                            continue;
                        }
                        if (testCovers(t, ti)) {
                            visit[ti] = epoch;
                            if (len == buf.length) {
                                buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                            }
                            buf[len++] = ti;
                            coverCnt[ti]++;
                        }
                    }
                }
                covLists[idx] = java.util.Arrays.copyOf(buf, len);
            }

            boolean[] removed = new boolean[m];
            for (int idx = m - 1; idx >= 0; idx--) {
                boolean redundant = true;
                for (int ti : covLists[idx]) {
                    if (coverCnt[ti] < 2) {
                        redundant = false;
                        break;
                    }
                }
                if (redundant) {
                    removed[idx] = true;
                    for (int ti : covLists[idx]) {
                        coverCnt[ti]--;
                    }
                }
            }

            List<int[]> kept = new ArrayList<>(m);
            for (int idx = 0; idx < m; idx++) {
                if (!removed[idx]) {
                    kept.add(tests.get(idx));
                }
            }
            tests.clear();
            tests.addAll(kept);
        }

        // ---- withouts ----------------------------------------------------

        private boolean violated(int[] t, int wi) {
            int[] ds = wDims[wi];
            boolean[][] masks = wFeat[wi];
            for (int k = 0; k < ds.length; k++) {
                if (!masks[k][t[ds[k]]]) {
                    return false;
                }
            }
            return true;
        }

        private boolean anyViolated(int[] t) {
            for (int wi = 0; wi < wDims.length; wi++) {
                if (violated(t, wi)) {
                    return true;
                }
            }
            return false;
        }

        private boolean violatesTouching(int[] t, int d) {
            for (int wi : byDim[d]) {
                if (violated(t, wi)) {
                    return true;
                }
            }
            return false;
        }

        private int countWithoutsTouching(int[] t, int d) {
            int c = 0;
            for (int wi : byDim[d]) {
                if (violated(t, wi)) {
                    c++;
                }
            }
            return c;
        }

        // ---- helpers -----------------------------------------------------

        private int maxDimSize() {
            int max = 0;
            for (int s : dimSize) {
                if (s > max) {
                    max = s;
                }
            }
            return max;
        }

        private void shuffle(int[] a, int len) {
            for (int i = len - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int tmp = a[i];
                a[i] = a[j];
                a[j] = tmp;
            }
        }

        List<Map<Dimension, Feature>> toFeatureMaps() {
            List<Map<Dimension, Feature>> result = new ArrayList<>(tests.size());
            for (int[] t : tests) {
                Map<Dimension, Feature> map = new HashMap<>(ndim * 2);
                for (int d = 0; d < ndim; d++) {
                    map.put(dimensions.get(d), dimensions.get(d).feature(t[d]));
                }
                result.add(map);
            }
            return result;
        }
    }
}
