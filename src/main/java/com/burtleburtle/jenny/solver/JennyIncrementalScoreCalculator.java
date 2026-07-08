package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.calculator.IncrementalScoreCalculator;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Hand-rolled {@link IncrementalScoreCalculator} that ports the Go port's flat
 * coverage arrays (see {@code cmd/jennygo/jenny.go}) into Timefold, replacing
 * the unindexed constraint-stream scoring in {@link JennyConstraintProvider}.
 *
 * <p>The declarative provider evaluates {@code coversTuple()} on every
 * (tuple, testCase) pair on every move — quadratic in the two largest
 * collections. This calculator instead keeps a running {@code coverCount} per
 * tuple and, on a single cell flip, touches only the tuples in
 * {@code postings[d][oldFeature]} and {@code postings[d][newFeature]} (Go's
 * {@code idx[d][f]} postings), which is O(postings) rather than O(all tuples).
 *
 * <p><strong>Score parity.</strong> The maintained score must match
 * {@link JennyConstraintProvider} exactly (validated under
 * {@code FULL_ASSERT} against it as the assertion score director):
 * <ul>
 *   <li>{@code coverAllTuples} penalizes 1 hard per uncovered allowed tuple;</li>
 *   <li>{@code respectWithouts} penalizes <strong>2</strong> hard per
 *       (active test case, matching without) pair;</li>
 *   <li>{@code minimizeActiveTests} penalizes 1 soft per active test case.</li>
 * </ul>
 * Hence {@code hard = -(uncoveredCount + 2 * withoutMatchCount)} and
 * {@code soft = -activeCount}.
 *
 * <p><strong>State model.</strong> Genuine planning variables are
 * {@code TestCell.feature} and {@code TestCase.active}; the
 * {@code featuresByDim} shadow is ignored (this calculator reads cells directly
 * via its own {@code rowFeat} mirror, so it never depends on shadow-update
 * timing and cannot deadlock the variable-listener graph). Old values are read
 * from the mirror in {@code afterVariableChanged}; {@code beforeVariableChanged}
 * is a no-op.
 */
public final class JennyIncrementalScoreCalculator
        implements IncrementalScoreCalculator<JennySolution, HardSoftScore> {

    // ---- static problem model (built once per resetWorkingSolution) ----
    private int ndim;
    private int nTuples;
    private int nRows;
    private int nWithouts;

    /** tupleDims[t] / tupleFeats[t]: coordinates of tuple t, sorted by dim index. */
    private int[][] tupleDims;
    private int[][] tupleFeats;

    /** postings[d][f] = ids of every allowed tuple that has coordinate (d, f). */
    private int[][][] postings;

    /** withoutDims[w] = dims the without names; withoutMasks[w][i] = forbidden-feature bitmask. */
    private int[][] withoutDims;
    private long[][] withoutMasks;
    /** withoutsByDim[d] = ids of withouts touching dimension d. */
    private int[][] withoutsByDim;

    private IdentityHashMap<TestCase, Integer> rowOf;

    // ---- mutable working state ----
    private int[][] rowFeat;          // [r][d] feature index, -1 if unassigned
    private boolean[] rowActive;      // [r]
    private int[] coverCount;         // [t] number of active rows covering tuple t
    private int uncoveredCount;       // #tuples with coverCount == 0
    private int activeCount;          // #active rows
    private int withoutMatchCount;    // sum over active rows of #withouts matched
    private int[] rowWithoutMatches;  // [r] #withouts row r matches (feature-only, active-agnostic)
    private boolean[][] rowWMatch;    // [r][w] whether row r currently matches without w

    // dedup scratch for whole-row coverage scans
    private int[] visited;            // [t] epoch stamp
    private int epoch;

    // ================================================================
    // Lifecycle
    // ================================================================

    @Override
    public void resetWorkingSolution(JennySolution solution) {
        List<Dimension> dims = solution.getDimensions();
        this.ndim = dims.size();
        int[] dimSize = new int[ndim];
        for (Dimension d : dims) {
            dimSize[d.index()] = d.size();
        }

        buildTuplesAndPostings(solution.getAllowedTuples(), dimSize);
        buildWithouts(solution.getWithouts());
        buildRows(solution.getTestCases());
        recomputeFromScratch();
    }

    private void buildTuplesAndPostings(List<AllowedTuple> tuples, int[] dimSize) {
        this.nTuples = tuples.size();
        this.tupleDims = new int[nTuples][];
        this.tupleFeats = new int[nTuples][];
        int[][] postingCount = new int[ndim][];
        for (int d = 0; d < ndim; d++) {
            postingCount[d] = new int[dimSize[d]];
        }
        for (int t = 0; t < nTuples; t++) {
            List<Feature> feats = tuples.get(t).features(); // sorted by dim index
            int k = feats.size();
            int[] ds = new int[k];
            int[] fs = new int[k];
            for (int i = 0; i < k; i++) {
                Feature f = feats.get(i);
                ds[i] = f.dimension().index();
                fs[i] = f.featureIndex();
                postingCount[ds[i]][fs[i]]++;
            }
            tupleDims[t] = ds;
            tupleFeats[t] = fs;
        }
        this.postings = new int[ndim][][];
        int[][] cursor = new int[ndim][];
        for (int d = 0; d < ndim; d++) {
            postings[d] = new int[dimSize[d]][];
            cursor[d] = new int[dimSize[d]];
            for (int f = 0; f < dimSize[d]; f++) {
                postings[d][f] = new int[postingCount[d][f]];
            }
        }
        for (int t = 0; t < nTuples; t++) {
            int[] ds = tupleDims[t];
            int[] fs = tupleFeats[t];
            for (int i = 0; i < ds.length; i++) {
                int d = ds[i];
                int f = fs[i];
                postings[d][f][cursor[d][f]++] = t;
            }
        }
    }

    private void buildWithouts(List<Without> ws) {
        this.nWithouts = ws.size();
        this.withoutDims = new int[nWithouts][];
        this.withoutMasks = new long[nWithouts][];
        int[] byDimCount = new int[ndim];
        for (int w = 0; w < nWithouts; w++) {
            Without without = ws.get(w);
            Set<Dimension> wdims = without.dimensions();
            int k = wdims.size();
            int[] ds = new int[k];
            long[] masks = new long[k];
            int i = 0;
            for (Dimension d : wdims) {
                ds[i] = d.index();
                long mask = 0L;
                for (Feature f : without.featuresFor(d)) {
                    mask |= (1L << f.featureIndex());
                }
                masks[i] = mask;
                byDimCount[d.index()]++;
                i++;
            }
            // keep dims (and their masks) sorted by dim index — insertion sort, k is tiny
            for (int a = 1; a < k; a++) {
                int dv = ds[a];
                long mv = masks[a];
                int b = a - 1;
                while (b >= 0 && ds[b] > dv) {
                    ds[b + 1] = ds[b];
                    masks[b + 1] = masks[b];
                    b--;
                }
                ds[b + 1] = dv;
                masks[b + 1] = mv;
            }
            withoutDims[w] = ds;
            withoutMasks[w] = masks;
        }
        this.withoutsByDim = new int[ndim][];
        int[] fill = new int[ndim];
        for (int d = 0; d < ndim; d++) {
            withoutsByDim[d] = new int[byDimCount[d]];
        }
        for (int w = 0; w < nWithouts; w++) {
            for (int di : withoutDims[w]) {
                withoutsByDim[di][fill[di]++] = w;
            }
        }
    }

    private void buildRows(List<TestCase> cases) {
        this.nRows = cases.size();
        this.rowOf = new IdentityHashMap<>(nRows * 2);
        this.rowFeat = new int[nRows][ndim];
        this.rowActive = new boolean[nRows];
        this.rowWithoutMatches = new int[nRows];
        this.rowWMatch = new boolean[nRows][nWithouts];
        for (int r = 0; r < nRows; r++) {
            int[] rf = rowFeat[r];
            for (int d = 0; d < ndim; d++) {
                rf[d] = -1;
            }
            TestCase tc = cases.get(r);
            rowOf.put(tc, r);
            rowActive[r] = tc.isActiveFlag();
            for (TestCell cell : tc.getCells()) {
                Feature f = cell.getFeature();
                rf[cell.getDimension().index()] = (f == null) ? -1 : f.featureIndex();
            }
        }
    }

    /** Full recompute of coverage, active, and without state from the mirrors. */
    private void recomputeFromScratch() {
        this.coverCount = new int[nTuples];
        this.visited = new int[nTuples];
        this.epoch = 0;
        this.uncoveredCount = nTuples; // all uncovered; addRowCoverage decrements on 0->1
        this.activeCount = 0;
        this.withoutMatchCount = 0;

        for (int r = 0; r < nRows; r++) {
            if (rowActive[r]) {
                activeCount++;
                addRowCoverage(r);
            }
        }
        for (int r = 0; r < nRows; r++) {
            int cnt = 0;
            for (int w = 0; w < nWithouts; w++) {
                boolean m = evalWithout(w, r);
                rowWMatch[r][w] = m;
                if (m) {
                    cnt++;
                }
            }
            rowWithoutMatches[r] = cnt;
            if (rowActive[r]) {
                withoutMatchCount += cnt;
            }
        }
    }

    // ================================================================
    // Incremental notifications
    // ================================================================

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        // No-op: old values are read from the rowFeat / rowActive mirror in
        // afterVariableChanged, so no pre-change bookkeeping is needed.
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        if (entity instanceof TestCell cell) {
            if ("feature".equals(variableName)) {
                handleFeatureChange(cell);
            }
        } else if (entity instanceof TestCase tc) {
            if ("active".equals(variableName)) {
                handleActiveChange(tc);
            }
        }
        // TestCase.featuresByDim (shadow) and anything else: ignored by design.
    }

    private void handleFeatureChange(TestCell cell) {
        Integer ri = rowOf.get(cell.getTestCase());
        if (ri == null) {
            return;
        }
        int r = ri;
        int d = cell.getDimension().index();
        Feature nf = cell.getFeature();
        int newF = (nf == null) ? -1 : nf.featureIndex();
        int oldF = rowFeat[r][d];
        if (oldF == newF) {
            return;
        }
        // rowCoversExcept(_, _, d) ignores dimension d, so updating the mirror
        // up front is safe for both the lose and gain scans below.
        int[] rf = rowFeat[r];
        rf[d] = newF;

        if (rowActive[r]) {
            if (oldF >= 0) {
                for (int t : postings[d][oldF]) {
                    if (rowCoversExcept(rf, t, d)) {
                        if (--coverCount[t] == 0) {
                            uncoveredCount++;
                        }
                    }
                }
            }
            if (newF >= 0) {
                for (int t : postings[d][newF]) {
                    if (rowCoversExcept(rf, t, d)) {
                        if (coverCount[t]++ == 0) {
                            uncoveredCount--;
                        }
                    }
                }
            }
        }
        updateWithoutsForDim(r, d);
    }

    private void handleActiveChange(TestCase tc) {
        Integer ri = rowOf.get(tc);
        if (ri == null) {
            return;
        }
        int r = ri;
        boolean newActive = tc.isActiveFlag();
        if (newActive == rowActive[r]) {
            return;
        }
        rowActive[r] = newActive;
        if (newActive) {
            activeCount++;
            addRowCoverage(r);
            withoutMatchCount += rowWithoutMatches[r];
        } else {
            activeCount--;
            removeRowCoverage(r);
            withoutMatchCount -= rowWithoutMatches[r];
        }
    }

    @Override
    public HardSoftScore calculateScore() {
        int hard = -(uncoveredCount + 2 * withoutMatchCount);
        int soft = -activeCount;
        return HardSoftScore.of(hard, soft);
    }

    // ================================================================
    // Coverage helpers (port of jenny.go commit/applyFlip accounting)
    // ================================================================

    /** Add row r's full coverage contribution (+1 to every tuple it covers). */
    private void addRowCoverage(int r) {
        int[] rf = rowFeat[r];
        int e = ++epoch;
        for (int d = 0; d < ndim; d++) {
            int f = rf[d];
            if (f < 0) {
                continue;
            }
            for (int t : postings[d][f]) {
                if (visited[t] == e) {
                    continue;
                }
                if (rowCovers(rf, t)) {
                    visited[t] = e;
                    if (coverCount[t]++ == 0) {
                        uncoveredCount--;
                    }
                }
            }
        }
    }

    /** Remove row r's full coverage contribution (-1 from every tuple it covers). */
    private void removeRowCoverage(int r) {
        int[] rf = rowFeat[r];
        int e = ++epoch;
        for (int d = 0; d < ndim; d++) {
            int f = rf[d];
            if (f < 0) {
                continue;
            }
            for (int t : postings[d][f]) {
                if (visited[t] == e) {
                    continue;
                }
                if (rowCovers(rf, t)) {
                    visited[t] = e;
                    if (--coverCount[t] == 0) {
                        uncoveredCount++;
                    }
                }
            }
        }
    }

    /** True iff every coordinate of tuple t matches the row's features. */
    private boolean rowCovers(int[] rf, int t) {
        int[] ds = tupleDims[t];
        int[] fs = tupleFeats[t];
        for (int i = 0; i < ds.length; i++) {
            if (rf[ds[i]] != fs[i]) {
                return false;
            }
        }
        return true;
    }

    /** True iff every coordinate of tuple t except dimension {@code skipDim} matches. */
    private boolean rowCoversExcept(int[] rf, int t, int skipDim) {
        int[] ds = tupleDims[t];
        int[] fs = tupleFeats[t];
        for (int i = 0; i < ds.length; i++) {
            int dd = ds[i];
            if (dd != skipDim && rf[dd] != fs[i]) {
                return false;
            }
        }
        return true;
    }

    // ================================================================
    // Without helpers
    // ================================================================

    /**
     * Re-evaluate only the withouts touching dimension {@code d} for row r
     * (Go's byDim bucketing) and fold the change into the running counts.
     */
    private void updateWithoutsForDim(int r, int d) {
        int[] ws = withoutsByDim[d];
        if (ws.length == 0) {
            return;
        }
        int delta = 0;
        boolean[] rMatch = rowWMatch[r];
        for (int w : ws) {
            boolean now = evalWithout(w, r);
            if (now != rMatch[w]) {
                rMatch[w] = now;
                delta += now ? 1 : -1;
            }
        }
        if (delta != 0) {
            rowWithoutMatches[r] += delta;
            if (rowActive[r]) {
                withoutMatchCount += delta;
            }
        }
    }

    /** True iff row r's features match without w (== C count_withouts on the row). */
    private boolean evalWithout(int w, int r) {
        int[] ds = withoutDims[w];
        long[] masks = withoutMasks[w];
        int[] rf = rowFeat[r];
        for (int i = 0; i < ds.length; i++) {
            int f = rf[ds[i]];
            if (f < 0 || ((masks[i] >>> f) & 1L) == 0L) {
                return false;
            }
        }
        return true;
    }
}
