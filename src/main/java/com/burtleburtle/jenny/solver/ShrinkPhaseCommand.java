package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.phase.PhaseCommand;
import ai.timefold.solver.core.api.solver.phase.PhaseCommandContext;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Deterministic shrink phase: a faithful port of the Go reference's
 * {@code removeRedundant} followed by {@code localSearchReduce}
 * ({@code cmd/jennygo/optimize.go}).
 *
 * <p>Both passes are <em>coverage-safe</em> and <em>without-safe</em> by
 * construction, so the hard score can never regress and each evicted (row
 * deactivated) test is a guaranteed &minus;1 soft:
 * <ul>
 *   <li>{@code removeRedundant} deactivates any active, unpinned row all of
 *       whose allowed tuples are covered by at least one other active row
 *       (fixpoint sweep).</li>
 *   <li>{@code localSearchReduce} deactivates rows {@code removeRedundant}
 *       cannot: for a row whose tuples are <em>uniquely</em> covered, it rehomes
 *       each such tuple onto another active row via a single-cell flip
 *       (rejecting any flip that violates a without or drops a tuple to zero
 *       coverage; full rollback on failure), then deactivates the row.</li>
 * </ul>
 *
 * <p>All mutation is computed on a private scratch model; only the surviving
 * edits are replayed as {@link Move}s through the {@link PhaseCommandContext}
 * (which performs the {@code beforeVariableChanged}/{@code afterVariableChanged}
 * /{@code triggerVariableListeners} bookkeeping and incremental score update).
 * The entry and exit hard scores are read back through the context; a hard
 * regression is impossible by construction and is treated as a fail-fast bug.
 *
 * <p>Pinned rows (pre-loaded {@code -o} tests) are never deactivated and pinned
 * cells are never flipped.
 */
public final class ShrinkPhaseCommand implements PhaseCommand<JennySolution> {

    private static final Logger LOG = LoggerFactory.getLogger(ShrinkPhaseCommand.class);

    @Override
    public void changeWorkingSolution(PhaseCommandContext<JennySolution> context) {
        JennySolution solution = context.getWorkingSolution();
        List<AllowedTuple> tuples = solution.getAllowedTuples();
        if (tuples.isEmpty()) {
            return;
        }
        int n = tuples.get(0).size();
        int ndim = solution.getDimensions().size();

        // Active rows form the working set; pinned rows count toward coverage
        // but are never deactivated or flipped.
        List<TestCase> rows = new ArrayList<>();
        for (TestCase tc : solution.getTestCases()) {
            if (tc.isActiveFlag()) {
                rows.add(tc);
            }
        }
        if (rows.size() < 2) {
            return;
        }

        Model model = new Model(rows, ndim, n, tuples, solution.getWithouts());
        model.removeRedundant();
        model.localSearchReduce();

        List<Model.CellEdit> cellEdits = model.cellEdits();
        List<TestCase> evicted = model.evictedRows();
        if (cellEdits.isEmpty() && evicted.isEmpty()) {
            return;
        }
        applyAndVerify(context, solution, cellEdits, evicted);
    }

    /** Replays the surviving edits as moves and confirms no hard regression. */
    private void applyAndVerify(PhaseCommandContext<JennySolution> context, JennySolution solution,
                                List<Model.CellEdit> cellEdits, List<TestCase> evicted) {
        PlanningSolutionMetaModel<JennySolution> mm = context.getSolutionMetaModel();
        PlanningVariableMetaModel<JennySolution, TestCell, Object> featureVar =
                mm.<TestCell>genuineEntity(TestCell.class).basicVariable("feature");
        PlanningVariableMetaModel<JennySolution, TestCase, Object> activeVar =
                mm.<TestCase>genuineEntity(TestCase.class).basicVariable("active");

        HardSoftScore entryScore = (HardSoftScore) solution.getScore();
        HardSoftScore lastScore = entryScore;
        int applied = 0;

        // 1. Cell flips on surviving rows (coverage-preserving rehomes).
        for (Model.CellEdit edit : cellEdits) {
            Move<JennySolution> move = Moves.change(featureVar, edit.cell(),
                    edit.cell().getDimension().feature(edit.newFeatureIndex()));
            lastScore = context.executeAndCalculateScore(move);
            applied++;
        }
        // 2. Deactivate evicted rows.
        for (TestCase row : evicted) {
            Move<JennySolution> move = Moves.change(activeVar, row, Boolean.FALSE);
            lastScore = context.executeAndCalculateScore(move);
            applied++;
        }

        if (entryScore != null && lastScore != null
                && lastScore.hardScore() < entryScore.hardScore()) {
            throw new IllegalStateException(
                    "ShrinkPhaseCommand regressed hard score from " + entryScore
                            + " to " + lastScore + " — coverage/without bookkeeping bug");
        }
        LOG.info("ShrinkPhase: evicted {} rows, {} cell rehomes, {} moves, score {} -> {}",
                evicted.size(), cellEdits.size(), applied, entryScore, lastScore);
    }

    /**
     * Scratch model over the active rows. Feature indices live in {@code feat},
     * coverage counts in {@code count}; nothing here touches Timefold planning
     * variables until {@link #cellEdits()}/{@link #evictedRows()} are replayed.
     */
    private static final class Model {

        private final List<TestCase> rows;
        private final int ndim;
        private final int n;
        private final Map<String, Integer> idOf;

        private final int[][] feat;             // feat[r][dimIndex] = feature index (mutated)
        private final int[][] originalFeat;     // snapshot for the final diff
        private final boolean[] pinnedRow;
        private final boolean[][] pinnedCell;
        private final TestCell[][] cellByDim;
        private final PhaseSupport.WithoutModel wm;

        private final boolean[] removed;
        private final int[] count;              // count[tupleId] = active rows covering it
        private final int[] comboFeats;         // scratch reused across combo callbacks

        Model(List<TestCase> rows, int ndim, int n, List<AllowedTuple> tuples,
              List<Without> withouts) {
            this.rows = rows;
            this.ndim = ndim;
            this.n = n;
            this.idOf = PhaseSupport.buildTupleIndex(tuples, ndim);
            int r = rows.size();
            this.feat = new int[r][];
            this.originalFeat = new int[r][];
            this.pinnedRow = new boolean[r];
            this.pinnedCell = new boolean[r][ndim];
            this.cellByDim = new TestCell[r][ndim];
            int[] dimSize = new int[ndim];
            for (Dimension d : rows.get(0).getCells().stream().map(TestCell::getDimension).toList()) {
                dimSize[d.index()] = d.size();
            }
            for (int i = 0; i < r; i++) {
                TestCase tc = rows.get(i);
                pinnedRow[i] = tc.isPinned();
                feat[i] = PhaseSupport.featureVector(tc, ndim);
                originalFeat[i] = feat[i].clone();
                for (TestCell cell : tc.getCells()) {
                    int d = cell.getDimension().index();
                    pinnedCell[i][d] = cell.isPinned();
                    cellByDim[i][d] = cell;
                    dimSize[d] = cell.getDimension().size();
                }
            }
            this.wm = PhaseSupport.WithoutModel.build(withouts, ndim, dimSize);
            this.removed = new boolean[r];
            this.count = new int[tuples.size()];
            this.comboFeats = new int[ndim]; // indexed by dimension index (see PhaseSupport.key)
        }

        /** id of the n-combo formed by {@code combo} over row r, or -1. */
        private int tupID(int r, int[] combo) {
            int[] rf = feat[r];
            for (int x : combo) {
                comboFeats[x] = rf[x];
            }
            Integer id = idOf.get(PhaseSupport.key(combo, comboFeats));
            return id == null ? -1 : id;
        }

        List<TestCase> evictedRows() {
            List<TestCase> out = new ArrayList<>();
            for (int i = 0; i < removed.length; i++) {
                if (removed[i]) {
                    out.add(rows.get(i));
                }
            }
            return out;
        }

        record CellEdit(TestCell cell, int newFeatureIndex) {
        }

        /** Cell edits = feature diffs on surviving (non-removed) rows. */
        List<CellEdit> cellEdits() {
            List<CellEdit> out = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                if (removed[r]) {
                    continue;
                }
                for (int d = 0; d < ndim; d++) {
                    if (cellByDim[r][d] != null && feat[r][d] != originalFeat[r][d]) {
                        out.add(new CellEdit(cellByDim[r][d], feat[r][d]));
                    }
                }
            }
            return out;
        }

        // ---- removeRedundant (port of optimize.go:189) --------------------

        void removeRedundant() {
            List<int[]> testIDs = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                List<Integer> ids = new ArrayList<>();
                final int ri = i;
                PhaseSupport.forEachCombo(ndim, n, combo -> {
                    int id = tupID(ri, combo);
                    if (id >= 0) {
                        ids.add(id);
                        count[id]++;
                    }
                });
                testIDs.add(ids.stream().mapToInt(Integer::intValue).toArray());
            }
            while (true) {
                boolean progress = false;
                for (int i = 0; i < rows.size(); i++) {
                    if (removed[i] || pinnedRow[i]) {
                        continue;
                    }
                    boolean redundant = true;
                    for (int id : testIDs.get(i)) {
                        if (count[id] < 2) {
                            redundant = false;
                            break;
                        }
                    }
                    if (redundant) {
                        removed[i] = true;
                        for (int id : testIDs.get(i)) {
                            count[id]--;
                        }
                        progress = true;
                    }
                }
                if (!progress) {
                    break;
                }
            }
        }

        // ---- localSearchReduce (port of optimize.go:257) ------------------

        private final List<int[]> cntLog = new ArrayList<>();   // {id, delta}
        private final List<int[]> cellLog = new ArrayList<>();  // {row, dim, old}

        void localSearchReduce() {
            // Recompute counts over the survivors of removeRedundant.
            Arrays.fill(count, 0);
            for (int i = 0; i < rows.size(); i++) {
                if (removed[i]) {
                    continue;
                }
                final int ri = i;
                PhaseSupport.forEachCombo(ndim, n, combo -> {
                    int id = tupID(ri, combo);
                    if (id >= 0) {
                        count[id]++;
                    }
                });
            }

            while (true) {
                // Order candidates by how many tuples they uniquely cover (fewest first).
                List<int[]> cand = new ArrayList<>(); // {row, uniqCount}
                for (int r = 0; r < rows.size(); r++) {
                    if (removed[r] || pinnedRow[r]) {
                        continue;
                    }
                    int[] u = {0};
                    final int rr = r;
                    PhaseSupport.forEachCombo(ndim, n, combo -> {
                        int id = tupID(rr, combo);
                        if (id >= 0 && count[id] == 1) {
                            u[0]++;
                        }
                    });
                    cand.add(new int[]{r, u[0]});
                }
                cand.sort((a, b) -> Integer.compare(a[1], b[1]));

                boolean progress = false;
                for (int[] c : cand) {
                    int r = c[0];
                    if (removed[r]) {
                        continue;
                    }
                    if (tryRemove(r)) {
                        progress = true;
                    }
                }
                if (!progress) {
                    break;
                }
            }
        }

        private void rollback() {
            for (int i = cntLog.size() - 1; i >= 0; i--) {
                int[] e = cntLog.get(i);
                count[e[0]] -= e[1];
            }
            cntLog.clear();
            for (int i = cellLog.size() - 1; i >= 0; i--) {
                int[] e = cellLog.get(i);
                feat[e[0]][e[1]] = e[2];
            }
            cellLog.clear();
        }

        /**
         * Sets {@code feat[r2][d] = newVal} iff it keeps r2 without-obeying and
         * drops no tuple to zero coverage. Mirrors {@code applyFlip}.
         */
        private boolean applyFlip(int r2, int d, int newVal) {
            int[] row = feat[r2];
            int old = row[d];
            if (old == newVal) {
                return true;
            }
            row[d] = newVal;
            boolean bad = wm.violatesTouching(row, d);
            row[d] = old;
            if (bad) {
                return false;
            }
            int logStart = cntLog.size();
            boolean[] fail = {false};
            PhaseSupport.forEachCombo(ndim, n, combo -> {
                boolean in = false;
                for (int x : combo) {
                    if (x == d) {
                        in = true;
                        break;
                    }
                }
                if (!in) {
                    return;
                }
                int lid = tupID(r2, combo);   // row[d] == old (currently covered)
                row[d] = newVal;
                int gid = tupID(r2, combo);   // row[d] == newVal (newly covered)
                row[d] = old;
                if (lid >= 0) {
                    count[lid]--;
                    cntLog.add(new int[]{lid, -1});
                    if (count[lid] == 0) {
                        fail[0] = true;
                    }
                }
                if (gid >= 0) {
                    count[gid]++;
                    cntLog.add(new int[]{gid, 1});
                }
            });
            if (fail[0]) {
                for (int i = cntLog.size() - 1; i >= logStart; i--) {
                    int[] e = cntLog.get(i);
                    count[e[0]] -= e[1];
                }
                while (cntLog.size() > logStart) {
                    cntLog.remove(cntLog.size() - 1);
                }
                return false;
            }
            row[d] = newVal;
            cellLog.add(new int[]{r2, d, old});
            return true;
        }

        /** Finds a single-cell flip on another row that makes it cover orphan o. */
        private boolean rehome(int r, int[] oDims, int[] oFeats) {
            for (int j = 0; j < n; j++) {
                int d = oDims[j];
                int want = oFeats[j];
                for (int r2 = 0; r2 < rows.size(); r2++) {
                    if (r2 == r || removed[r2] || pinnedRow[r2] || pinnedCell[r2][d]) {
                        continue;
                    }
                    int[] row2 = feat[r2];
                    if (row2[d] == want) {
                        continue; // matches on d already; a d-flip can't fix a mismatch elsewhere
                    }
                    boolean match = true;
                    for (int k = 0; k < n; k++) {
                        if (k == j) {
                            continue;
                        }
                        if (row2[oDims[k]] != oFeats[k]) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    if (applyFlip(r2, d, want)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Attempts to delete row r, rehoming every tuple it uniquely covers. */
        private boolean tryRemove(int r) {
            cntLog.clear();
            cellLog.clear();
            List<int[]> orphanDims = new ArrayList<>();
            List<int[]> orphanFeats = new ArrayList<>();
            List<Integer> orphanIds = new ArrayList<>();
            PhaseSupport.forEachCombo(ndim, n, combo -> {
                int id = tupID(r, combo);
                if (id >= 0 && count[id] == 1) {
                    int[] ds = new int[n];
                    int[] fs = new int[n];
                    for (int i = 0; i < n; i++) {
                        ds[i] = combo[i];
                        fs[i] = feat[r][combo[i]];
                    }
                    orphanDims.add(ds);
                    orphanFeats.add(fs);
                    orphanIds.add(id);
                }
            });
            for (int oi = 0; oi < orphanIds.size(); oi++) {
                if (count[orphanIds.get(oi)] >= 2) {
                    continue; // already rehomed by an earlier flip this attempt
                }
                if (!rehome(r, orphanDims.get(oi), orphanFeats.get(oi))) {
                    rollback();
                    return false;
                }
            }
            boolean[] safe = {true};
            PhaseSupport.forEachCombo(ndim, n, combo -> {
                int id = tupID(r, combo);
                if (id >= 0 && count[id] < 2) {
                    safe[0] = false;
                }
            });
            if (!safe[0]) {
                rollback();
                return false;
            }
            PhaseSupport.forEachCombo(ndim, n, combo -> {
                int id = tupID(r, combo);
                if (id >= 0) {
                    count[id]--;
                }
            });
            removed[r] = true;
            // Flips on surviving rows are kept (committed into feat[]); the final
            // cellEdits() diff picks them up. Clear the per-attempt logs.
            cntLog.clear();
            cellLog.clear();
            return true;
        }
    }
}
