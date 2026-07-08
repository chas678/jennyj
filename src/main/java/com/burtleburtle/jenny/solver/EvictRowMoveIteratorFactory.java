package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.HashMap;
import java.util.random.RandomGenerator;

/**
 * Emits composite "evict row" moves that delete one active, unpinned
 * {@link TestCase} while preserving full coverage — the Timefold port of the
 * Go generator's strongest suite-shrink pass ({@code localSearchReduce} /
 * {@code rehome} / {@code applyFlip} in {@code optimize.go}).
 *
 * <p>For a victim row {@code r}, the factory computes the tuples that
 * <em>only</em> {@code r} covers (its uniquely-covered "orphans"). Each orphan
 * is "rehomed" onto another active, unpinned row via a single-cell feature
 * flip that makes that row come to cover the orphan. A flip is legal only if it
 * (a) creates no {@link Without} violation on the flipped row and (b) drops no
 * tuple's coverage to zero. Once every orphan is rehomed — so every tuple
 * {@code r} covers is covered at least twice — the composite
 * {@code [flip1, …, flipK, deactivate(r)]} is emitted. If any orphan cannot be
 * rehomed, {@code r} is skipped and the next candidate is tried.
 *
 * <p>Coverage counts are recomputed once per iterator creation from the working
 * solution's active rows (O(rows × C(ndim, n))). All flip bookkeeping happens
 * on a private per-attempt clone of those counts and of the row feature grid;
 * the working solution is never mutated here — the emitted {@link Move} carries
 * the changes and Timefold applies (and can undo) them. Because the counting is
 * exact, an emitted move provably preserves the covered-tuple set and violates
 * no without, so it is a pure soft-score improvement (one fewer active row) at
 * unchanged hard score.
 *
 * <p>Candidates are tried in ascending order of unique-tuple count (fewest
 * orphans first), mirroring the Go pass's ordering; this biases eviction toward
 * the rows cheapest to remove.
 */
public class EvictRowMoveIteratorFactory
        implements MoveIteratorFactory<JennySolution, Move<JennySolution>> {

    @Override
    public long getSize(ScoreDirector<JennySolution> scoreDirector) {
        return scoreDirector.getWorkingSolution().getTestCases().stream()
                .filter(tc -> !tc.isPinned() && tc.isActiveFlag())
                .count();
    }

    @Override
    public Iterator<Move<JennySolution>> createOriginalMoveIterator(
            ScoreDirector<JennySolution> scoreDirector) {
        return new Ctx(scoreDirector).iterator();
    }

    @Override
    public Iterator<Move<JennySolution>> createRandomMoveIterator(
            ScoreDirector<JennySolution> scoreDirector, RandomGenerator workingRandom) {
        // Candidate order is deterministic (ascending unique-tuple count); the
        // Go pass sorts identically. The random generator is unused — the bias
        // toward fewest-unique rows is the whole selection strategy.
        return new Ctx(scoreDirector).iterator();
    }

    /**
     * Immutable snapshot of the working solution plus the derived coverage
     * index, taken once when the iterator is created. All eviction attempts run
     * against clones of this snapshot.
     */
    private static final class Ctx {

        private final int ndim;
        private final int n;
        private final int nt;
        private final Map<String, Integer> idOf;

        // Parallel arrays over all ACTIVE rows (pinned + unpinned). Coverage
        // counts sum over every active row; only unpinned rows may be a victim
        // or a rehome target.
        private final TestCase[] rowCase;
        private final boolean[] rowPinned;
        private final Feature[][] rowFeat; // [row][dimIndex] -> feature (may be null)
        private final TestCell[][] rowCell; // [row][dimIndex] -> cell
        private final int nrows;

        private final int[] baseCount; // coverage count per tuple id
        private final List<Without>[] withoutsByDim;

        private final int[] ordered; // candidate row indices, ascending by uniq

        private final GenuineVariableDescriptor<JennySolution> featureDescriptor;
        private final GenuineVariableDescriptor<JennySolution> activeDescriptor;

        @SuppressWarnings("unchecked")
        Ctx(ScoreDirector<JennySolution> scoreDirector) {
            JennySolution sol = scoreDirector.getWorkingSolution();
            List<Dimension> dims = sol.getDimensions();
            this.ndim = dims.size();
            List<AllowedTuple> tuples = sol.getAllowedTuples();
            this.nt = tuples.size();
            this.n = tuples.isEmpty() ? 0 : tuples.get(0).size();

            this.idOf = new HashMap<>(nt * 2);
            for (int ti = 0; ti < nt; ti++) {
                idOf.put(tupleKey(tuples.get(ti)), ti);
            }

            this.withoutsByDim = new List[ndim];
            for (int d = 0; d < ndim; d++) {
                withoutsByDim[d] = new ArrayList<>();
            }
            for (Without w : sol.getWithouts()) {
                for (Dimension d : w.dimensions()) {
                    withoutsByDim[d.index()].add(w);
                }
            }

            List<TestCase> active = sol.getTestCases().stream()
                    .filter(TestCase::isActiveFlag)
                    .toList();
            this.nrows = active.size();
            this.rowCase = new TestCase[nrows];
            this.rowPinned = new boolean[nrows];
            this.rowFeat = new Feature[nrows][];
            this.rowCell = new TestCell[nrows][];
            for (int r = 0; r < nrows; r++) {
                TestCase tc = active.get(r);
                rowCase[r] = tc;
                rowPinned[r] = tc.isPinned();
                Feature[] fs = new Feature[ndim];
                TestCell[] cs = new TestCell[ndim];
                for (TestCell cell : tc.getCells()) {
                    int di = cell.getDimension().index();
                    fs[di] = cell.getFeature();
                    cs[di] = cell;
                }
                rowFeat[r] = fs;
                rowCell[r] = cs;
            }

            this.baseCount = new int[nt];
            int[] combo = new int[n];
            for (int r = 0; r < nrows; r++) {
                Feature[] fs = rowFeat[r];
                forEachCombo(combo, 0, 0, c -> {
                    int id = tupId(fs, c);
                    if (id >= 0) {
                        baseCount[id]++;
                    }
                });
            }

            // Order unpinned candidates ascending by unique-tuple count. Ties
            // broken by row index for deterministic enumeration.
            List<int[]> cand = new ArrayList<>(); // {rowIndex, uniqCount}
            for (int r = 0; r < nrows; r++) {
                if (rowPinned[r]) {
                    continue;
                }
                int[] uniq = {0};
                Feature[] fs = rowFeat[r];
                forEachCombo(combo, 0, 0, c -> {
                    int id = tupId(fs, c);
                    if (id >= 0 && baseCount[id] == 1) {
                        uniq[0]++;
                    }
                });
                cand.add(new int[]{r, uniq[0]});
            }
            cand.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1])
                    : Integer.compare(a[0], b[0]));
            this.ordered = new int[cand.size()];
            for (int i = 0; i < cand.size(); i++) {
                ordered[i] = cand.get(i)[0];
            }

            InnerScoreDirector<JennySolution, ?> inner =
                    (InnerScoreDirector<JennySolution, ?>) scoreDirector;
            this.featureDescriptor = inner.getSolutionDescriptor()
                    .findEntityDescriptor(TestCell.class)
                    .getGenuineVariableDescriptor("feature");
            this.activeDescriptor = inner.getSolutionDescriptor()
                    .findEntityDescriptor(TestCase.class)
                    .getGenuineVariableDescriptor("active");
        }

        // ---- tuple-key coverage index (mirrors Go appendKey / buildIDOf) ----

        private static String tupleKey(AllowedTuple t) {
            List<Feature> fs = t.features(); // sorted ascending by dimension
            char[] key = new char[fs.size() * 2];
            for (int i = 0; i < fs.size(); i++) {
                Feature f = fs.get(i);
                key[2 * i] = (char) f.dimension().index();
                key[2 * i + 1] = (char) f.featureIndex();
            }
            return new String(key);
        }

        /** Tuple id of the n-combo {@code combo} over {@code row}, or -1. */
        private int tupId(Feature[] row, int[] combo) {
            char[] key = new char[combo.length * 2];
            for (int i = 0; i < combo.length; i++) {
                Feature f = row[combo[i]];
                if (f == null) {
                    return -1;
                }
                key[2 * i] = (char) combo[i];
                key[2 * i + 1] = (char) f.featureIndex();
            }
            Integer id = idOf.get(new String(key));
            return id == null ? -1 : id;
        }

        private interface ComboFn {
            void accept(int[] combo);
        }

        private void forEachCombo(int[] combo, int start, int depth, ComboFn fn) {
            if (depth == n) {
                fn.accept(combo);
                return;
            }
            for (int d = start; d <= ndim - (n - depth); d++) {
                combo[depth] = d;
                forEachCombo(combo, d + 1, depth + 1, fn);
            }
        }

        // ---- without predicate (mirrors Go violatesTouching / matches) ----

        private boolean matchesRow(Without w, Feature[] row) {
            for (Dimension d : w.dimensions()) {
                Feature assigned = row[d.index()];
                if (assigned == null || !w.featuresFor(d).contains(assigned)) {
                    return false;
                }
            }
            return true;
        }

        private boolean violatesTouching(Feature[] row, int d) {
            for (Without w : withoutsByDim[d]) {
                if (matchesRow(w, row)) {
                    return true;
                }
            }
            return false;
        }

        // ---- eviction attempt (mirrors Go tryRemove / rehome / applyFlip) ----

        private Move<JennySolution> tryBuildEvict(int victim) {
            int[] count = baseCount.clone();
            Feature[][] wfeat = new Feature[nrows][];
            for (int r = 0; r < nrows; r++) {
                wfeat[r] = rowFeat[r].clone();
            }
            List<Move<JennySolution>> subMoves = new ArrayList<>();

            // Orphans: tuples the victim uniquely covers (count == 1).
            List<int[]> orphanCombos = new ArrayList<>(); // combo dims
            List<Integer> orphanIds = new ArrayList<>();
            int[] scan = new int[n];
            Feature[] vrow = wfeat[victim];
            forEachCombo(scan, 0, 0, c -> {
                int id = tupId(vrow, c);
                if (id >= 0 && count[id] == 1) {
                    orphanCombos.add(c.clone());
                    orphanIds.add(id);
                }
            });

            for (int i = 0; i < orphanIds.size(); i++) {
                int id = orphanIds.get(i);
                if (count[id] >= 2) {
                    continue; // already rehomed by an earlier flip this attempt
                }
                int[] combo = orphanCombos.get(i);
                // The orphan's required feature per dimension is the victim's.
                if (!rehome(victim, combo, wfeat, count, subMoves)) {
                    return null;
                }
            }

            // Safety net: every tuple the victim covers must now be covered at
            // least twice, so deactivating the victim leaves each still covered.
            boolean[] safe = {true};
            forEachCombo(scan, 0, 0, c -> {
                int id = tupId(vrow, c);
                if (id >= 0 && count[id] < 2) {
                    safe[0] = false;
                }
            });
            if (!safe[0]) {
                return null;
            }

            subMoves.add(new SelectorBasedChangeMove<>(
                    activeDescriptor, rowCase[victim], Boolean.FALSE));
            return Moves.compose(subMoves);
        }

        /**
         * Find a single-cell flip on some other active, unpinned row that makes
         * it cover the orphan tuple described by {@code combo} over the victim
         * row, applying it on success. Mirrors Go {@code rehome}.
         */
        private boolean rehome(int victim, int[] combo, Feature[][] wfeat,
                int[] count, List<Move<JennySolution>> subMoves) {
            Feature[] vrow = wfeat[victim];
            for (int j = 0; j < n; j++) {
                int d = combo[j];
                Feature want = vrow[d];
                for (int r2 = 0; r2 < nrows; r2++) {
                    if (r2 == victim || rowPinned[r2]) {
                        continue;
                    }
                    Feature[] row2 = wfeat[r2];
                    if (want.equals(row2[d])) {
                        continue; // matches on d already; a d-flip can't fix another mismatch
                    }
                    boolean match = true;
                    for (int k = 0; k < n; k++) {
                        if (k == j) {
                            continue;
                        }
                        int dk = combo[k];
                        if (!vrow[dk].equals(row2[dk])) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    if (applyFlip(r2, d, want, wfeat, count, subMoves)) {
                        return true; // r2 now matches all orphan coords -> covers it
                    }
                }
            }
            return false;
        }

        /**
         * Set {@code wfeat[r2][d] = newVal} if it keeps r2 without-obeying and
         * drops no tuple to zero coverage. On success the flip is committed to
         * the working clone (counts updated, ChangeMove appended); on failure
         * state is left exactly as it was. Mirrors Go {@code applyFlip}.
         */
        private boolean applyFlip(int r2, int d, Feature newVal, Feature[][] wfeat,
                int[] count, List<Move<JennySolution>> subMoves) {
            Feature[] row = wfeat[r2];
            Feature old = row[d];
            if (newVal.equals(old)) {
                return true;
            }
            row[d] = newVal;
            boolean bad = violatesTouching(row, d);
            row[d] = old;
            if (bad) {
                return false;
            }

            // Apply count deltas for every combo that includes d, tracking them
            // for rollback if any coverage would hit zero.
            List<int[]> delta = new ArrayList<>(); // {id, +/-1}
            boolean[] fail = {false};
            int[] scan = new int[n];
            forEachCombo(scan, 0, 0, c -> {
                boolean in = false;
                for (int x : c) {
                    if (x == d) {
                        in = true;
                        break;
                    }
                }
                if (!in) {
                    return;
                }
                row[d] = old;
                int lid = tupId(row, c);
                row[d] = newVal;
                int gid = tupId(row, c);
                row[d] = old;
                if (lid >= 0) {
                    count[lid]--;
                    delta.add(new int[]{lid, -1});
                    if (count[lid] == 0) {
                        fail[0] = true;
                    }
                }
                if (gid >= 0) {
                    count[gid]++;
                    delta.add(new int[]{gid, 1});
                }
            });
            if (fail[0]) {
                for (int i = delta.size() - 1; i >= 0; i--) {
                    count[delta.get(i)[0]] -= delta.get(i)[1];
                }
                return false; // row[d] already restored to old
            }
            row[d] = newVal;
            subMoves.add(new SelectorBasedChangeMove<>(
                    featureDescriptor, rowCell[r2][d], newVal));
            return true;
        }

        Iterator<Move<JennySolution>> iterator() {
            return new Iterator<>() {
                private int cursor = 0;
                private Move<JennySolution> buffered;
                private boolean computed = false;

                private void ensureBuffer() {
                    if (computed) {
                        return;
                    }
                    while (cursor < ordered.length) {
                        Move<JennySolution> m = tryBuildEvict(ordered[cursor++]);
                        if (m != null) {
                            buffered = m;
                            computed = true;
                            return;
                        }
                    }
                    buffered = null;
                    computed = true;
                }

                @Override
                public boolean hasNext() {
                    ensureBuffer();
                    return buffered != null;
                }

                @Override
                public Move<JennySolution> next() {
                    ensureBuffer();
                    if (buffered == null) {
                        throw new NoSuchElementException();
                    }
                    Move<JennySolution> m = buffered;
                    buffered = null;
                    computed = false;
                    return m;
                }
            };
        }
    }
}
