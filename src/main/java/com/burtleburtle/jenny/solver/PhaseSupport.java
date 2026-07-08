package com.burtleburtle.jenny.solver;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared bookkeeping for the deterministic post-processing phases
 * ({@link ShrinkPhaseCommand}, {@link RecoverPhaseCommand}). This is a direct
 * port of the flat coverage index, combo enumeration and without predicate from
 * the Go reference (see {@code cmd/jennygo/optimize.go} and
 * {@code cmd/jennygo/jenny.go} in the sibling {@code jennygo} project).
 *
 * <p>Every method here is pure — it operates on plain {@code int[]} feature
 * vectors (one feature index per dimension index) and never touches Timefold
 * planning variables. The phase commands translate the resulting decisions into
 * {@code Move}s applied through the {@code PhaseCommandContext}.
 */
final class PhaseSupport {

    private PhaseSupport() {
    }

    /**
     * Invokes {@code fn} for every ascending n-combination of dimension indices
     * {@code [0, ndim)}. The {@code combo} buffer is reused between calls, so
     * callers must not retain it. Mirrors {@code generator.forEachCombo}.
     */
    static void forEachCombo(int ndim, int n, Consumer<int[]> fn) {
        int[] combo = new int[n];
        forEachComboRec(ndim, n, combo, 0, 0, fn);
    }

    private static void forEachComboRec(int ndim, int n, int[] combo, int start, int depth,
                                        Consumer<int[]> fn) {
        if (depth == n) {
            fn.accept(combo);
            return;
        }
        for (int d = start; d <= ndim - (n - depth); d++) {
            combo[depth] = d;
            forEachComboRec(ndim, n, combo, d + 1, depth + 1, fn);
        }
    }

    /**
     * Encodes a tuple's coordinates into a compact key. {@code dims} holds the
     * (ascending) dimension indices and {@code featByDim} maps a dimension index
     * to its feature index. Two chars per coordinate: dimension index then
     * feature index (both fit in a {@code char} given MAX_DIMENSIONS &lt; 65535
     * and MAX_FEATURES == 52). Mirrors {@code appendKey}.
     */
    static String key(int[] dims, int[] featByDim) {
        char[] buf = new char[dims.length * 2];
        for (int i = 0; i < dims.length; i++) {
            int d = dims[i];
            buf[2 * i] = (char) d;
            buf[2 * i + 1] = (char) featByDim[d];
        }
        return new String(buf);
    }

    /**
     * Builds the id-of-tuple index: canonical key -&gt; tuple id (its position in
     * {@code tuples}). The feature list of an {@link AllowedTuple} is already
     * sorted ascending by dimension index, matching {@link #forEachCombo}.
     */
    static Map<String, Integer> buildTupleIndex(List<AllowedTuple> tuples, int ndim) {
        Map<String, Integer> idOf = new HashMap<>(tuples.size() * 2);
        int[] featByDim = new int[ndim];
        for (int ti = 0; ti < tuples.size(); ti++) {
            List<Feature> fs = tuples.get(ti).features();
            int[] dims = new int[fs.size()];
            for (int i = 0; i < fs.size(); i++) {
                Feature f = fs.get(i);
                dims[i] = f.dimension().index();
                featByDim[dims[i]] = f.featureIndex();
            }
            idOf.put(key(dims, featByDim), ti);
        }
        return idOf;
    }

    /**
     * Extracts a feature-index-per-dimension vector from an (already built)
     * {@link TestCase}. Cells whose feature is {@code null} (unset) map to -1.
     */
    static int[] featureVector(TestCase tc, int ndim) {
        int[] feat = new int[ndim];
        java.util.Arrays.fill(feat, -1);
        for (var cell : tc.getCells()) {
            Feature f = cell.getFeature();
            if (f != null) {
                feat[cell.getDimension().index()] = f.featureIndex();
            }
        }
        return feat;
    }

    /**
     * Fast integer model of the {@code -w} restrictions, ported from the Go
     * {@code without} struct plus {@code generator.byDim}. Operates on feature
     * vectors indexed by dimension index.
     */
    static final class WithoutModel {

        /** dims[w] = ascending dimension indices touched by without w. */
        private final int[][] dims;
        /** forbidden[w][i] is a per-feature-index bitmap for dims[w][i]. */
        private final boolean[][][] forbidden;
        /** byDim[d] = indices of withouts that touch dimension d. */
        private final int[][] byDim;

        private WithoutModel(int[][] dims, boolean[][][] forbidden, int[][] byDim) {
            this.dims = dims;
            this.forbidden = forbidden;
            this.byDim = byDim;
        }

        int size() {
            return dims.length;
        }

        static WithoutModel build(List<Without> withouts, int ndim, int[] dimSize) {
            int nw = withouts.size();
            int[][] wdims = new int[nw][];
            boolean[][][] forbidden = new boolean[nw][][];
            List<List<Integer>> byDimList = new ArrayList<>(ndim);
            for (int d = 0; d < ndim; d++) {
                byDimList.add(new ArrayList<>());
            }
            for (int w = 0; w < nw; w++) {
                Without wo = withouts.get(w);
                List<Dimension> ds = new ArrayList<>(wo.dimensions());
                ds.sort((a, b) -> Integer.compare(a.index(), b.index()));
                int[] di = new int[ds.size()];
                boolean[][] fb = new boolean[ds.size()][];
                for (int i = 0; i < ds.size(); i++) {
                    Dimension dim = ds.get(i);
                    di[i] = dim.index();
                    boolean[] mask = new boolean[dimSize[dim.index()]];
                    for (Feature f : wo.featuresFor(dim)) {
                        mask[f.featureIndex()] = true;
                    }
                    fb[i] = mask;
                    byDimList.get(dim.index()).add(w);
                }
                wdims[w] = di;
                forbidden[w] = fb;
            }
            int[][] byDim = new int[ndim][];
            for (int d = 0; d < ndim; d++) {
                byDim[d] = byDimList.get(d).stream().mapToInt(Integer::intValue).toArray();
            }
            return new WithoutModel(wdims, forbidden, byDim);
        }

        /** True iff the complete feature vector breaks restriction w. */
        private boolean violatedW(int w, int[] feat) {
            int[] wd = dims[w];
            boolean[][] fb = forbidden[w];
            for (int i = 0; i < wd.length; i++) {
                if (!fb[i][feat[wd[i]]]) {
                    return false;
                }
            }
            return true;
        }

        /** True iff the complete feature vector breaks any restriction. */
        boolean anyViolated(int[] feat) {
            for (int w = 0; w < dims.length; w++) {
                if (violatedW(w, feat)) {
                    return true;
                }
            }
            return false;
        }

        /** True iff at least one restriction touches dimension d. */
        boolean touches(int d) {
            return byDim[d].length > 0;
        }

        /** True iff any restriction touching dimension d is broken (== C's check). */
        boolean violatesTouching(int[] feat, int d) {
            for (int w : byDim[d]) {
                if (violatedW(w, feat)) {
                    return true;
                }
            }
            return false;
        }

        /** Count of restrictions touching dimension d that are currently broken. */
        int countTouching(int[] feat, int d) {
            int c = 0;
            for (int w : byDim[d]) {
                if (violatedW(w, feat)) {
                    c++;
                }
            }
            return c;
        }
    }
}
