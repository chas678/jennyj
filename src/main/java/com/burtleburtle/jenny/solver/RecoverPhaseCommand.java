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
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Recovery phase: the Timefold analogue of the Go reference's {@code recover}
 * ({@code cmd/jennygo/optimize.go}). It makes the CLI's "Could not cover"
 * output honest — a tuple is left uncovered only if a large randomized effort
 * fails to build a restriction-obeying test that covers it.
 *
 * <p>At entry it recomputes true coverage from the active rows. For every
 * allowed tuple still uncovered it pins that tuple's coordinates and tries, with
 * a large budget of random restarts plus without-repair (a port of jenny's
 * {@code trySatisfy}/{@code obey_withouts}), to build a complete valid test. On
 * success it fills a spare (inactive, unpinned) row with that test and activates
 * it. Only tuples that survive the budget (or run out of spare capacity) stay
 * uncovered.
 *
 * <p>The phase only ever <em>adds</em> coverage on without-obeying rows, so the
 * hard score cannot regress; this is asserted against the score read back
 * through the context.
 */
public final class RecoverPhaseCommand implements PhaseCommand<JennySolution> {

    private static final Logger LOG = LoggerFactory.getLogger(RecoverPhaseCommand.class);

    /** Random restarts per seed when building a valid covering test (Go: maxIters*8). */
    private static final int RECOVER_ITERS = 96;
    /** obey_withouts hill-climb passes tolerated without progress (Go: maxNoProgress). */
    private static final int MAX_NO_PROGRESS = 2;

    private int ndim;
    private PhaseSupport.WithoutModel wm;

    @Override
    public void changeWorkingSolution(PhaseCommandContext<JennySolution> context) {
        JennySolution solution = context.getWorkingSolution();
        List<AllowedTuple> tuples = solution.getAllowedTuples();
        if (tuples.isEmpty()) {
            return;
        }
        int n = tuples.get(0).size();
        List<Dimension> dimensions = solution.getDimensions();
        this.ndim = dimensions.size();
        int[] dimSize = new int[ndim];
        for (Dimension d : dimensions) {
            dimSize[d.index()] = d.size();
        }
        List<Without> withouts = solution.getWithouts();
        this.wm = PhaseSupport.WithoutModel.build(withouts, ndim, dimSize);

        Map<String, Integer> idOf = PhaseSupport.buildTupleIndex(tuples, ndim);

        // True coverage from the current active rows.
        boolean[] covered = new boolean[tuples.size()];
        for (TestCase tc : solution.getTestCases()) {
            if (tc.isActiveFlag()) {
                markCovered(tc, idOf, covered);
            }
        }
        List<Integer> residual = new ArrayList<>();
        for (int ti = 0; ti < tuples.size(); ti++) {
            if (!covered[ti]) {
                residual.add(ti);
            }
        }
        if (residual.isEmpty()) {
            return;
        }

        // Spare rows: inactive, unpinned capacity we may activate.
        List<TestCase> spares = new ArrayList<>();
        for (TestCase tc : solution.getTestCases()) {
            if (!tc.isActiveFlag() && !tc.isPinned()) {
                spares.add(tc);
            }
        }

        PlanningSolutionMetaModel<JennySolution> mm = context.getSolutionMetaModel();
        PlanningVariableMetaModel<JennySolution, TestCell, Object> featureVar =
                mm.<TestCell>genuineEntity(TestCell.class).basicVariable("feature");
        PlanningVariableMetaModel<JennySolution, TestCase, Object> activeVar =
                mm.<TestCase>genuineEntity(TestCase.class).basicVariable("active");

        HardSoftScore entryScore = (HardSoftScore) solution.getScore();
        HardSoftScore lastScore = entryScore;

        Random rng = new Random(0x1E7717L ^ tuples.size());
        int spareIdx = 0;
        int recovered = 0;
        int gaveUp = 0;

        int[] t = new int[ndim];
        boolean[] mut = new boolean[ndim];

        int ri = 0;
        while (ri < residual.size()) {
            int seed = residual.get(ri);
            if (covered[seed]) {   // incidentally covered by an earlier activation
                ri++;
                continue;
            }
            if (spareIdx >= spares.size()) {
                break;             // no capacity left
            }
            Arrays.fill(mut, true);
            for (int d = 0; d < ndim; d++) {
                t[d] = 0;
            }
            for (Feature f : tuples.get(seed).features()) {
                int d = f.dimension().index();
                t[d] = f.featureIndex();
                mut[d] = false;
            }
            if (!trySatisfy(t, mut, RECOVER_ITERS, dimSize, rng)) {
                gaveUp++;          // genuinely uncoverable within budget
                ri++;
                continue;
            }
            // Fill a spare row with t and activate it.
            TestCase spare = spares.get(spareIdx++);
            for (TestCell cell : spare.getCells()) {
                int d = cell.getDimension().index();
                Feature wanted = cell.getDimension().feature(t[d]);
                if (!wanted.equals(cell.getFeature())) {
                    lastScore = context.executeAndCalculateScore(
                            Moves.change(featureVar, cell, wanted));
                }
            }
            lastScore = context.executeAndCalculateScore(
                    Moves.change(activeVar, spare, Boolean.TRUE));
            markCoveredVector(t, n, idOf, covered);
            recovered++;
            ri++;
        }

        if (entryScore != null && lastScore != null
                && lastScore.hardScore() < entryScore.hardScore()) {
            throw new IllegalStateException(
                    "RecoverPhaseCommand regressed hard score from " + entryScore
                            + " to " + lastScore + " — recovery must only add coverage");
        }
        long stillUncovered = 0;
        for (boolean c : covered) {
            if (!c) {
                stillUncovered++;
            }
        }
        LOG.info("RecoverPhase: {} residual tuples, recovered {} (activated rows), "
                        + "{} left uncoverable, {} still uncovered, score {} -> {}",
                residual.size(), recovered, gaveUp, stillUncovered, entryScore, lastScore);
    }

    private void markCovered(TestCase tc, Map<String, Integer> idOf, boolean[] covered) {
        int[] vec = PhaseSupport.featureVector(tc, ndim);
        int n = idOf.isEmpty() ? 0 : firstKeyLength(idOf);
        markCoveredVector(vec, n, idOf, covered);
    }

    /** Marks every allowed tuple that the complete feature vector covers. */
    private void markCoveredVector(int[] vec, int n, Map<String, Integer> idOf, boolean[] covered) {
        if (n == 0) {
            return;
        }
        PhaseSupport.forEachCombo(ndim, n, combo -> {
            Integer id = idOf.get(PhaseSupport.key(combo, vec));
            if (id != null) {
                covered[id] = true;
            }
        });
    }

    private static int firstKeyLength(Map<String, Integer> idOf) {
        // key length == 2 * tupleSize (two chars per coordinate).
        return idOf.keySet().iterator().next().length() / 2;
    }

    // ---- port of trySatisfy / obey_withouts (jenny.go) --------------------

    private boolean trySatisfy(int[] t, boolean[] mut, int iters, int[] dimSize, Random rng) {
        for (int iter = 0; iter < iters; iter++) {
            for (int d = 0; d < ndim; d++) {
                if (mut[d]) {
                    t[d] = rng.nextInt(dimSize[d]);
                }
            }
            if (wm.size() == 0 || obeyWithouts(t, mut, dimSize, rng)) {
                if (!wm.anyViolated(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean obeyWithouts(int[] t, boolean[] mut, int[] dimSize, Random rng) {
        if (!wm.anyViolated(t)) {
            return true;
        }
        List<Integer> dims = new ArrayList<>();
        for (int d = 0; d < ndim; d++) {
            if (mut[d] && wm.touches(d)) {
                dims.add(d);
            }
        }
        int noProgress = 0;
        while (noProgress < MAX_NO_PROGRESS) {
            Collections.shuffle(dims, rng);
            boolean allZero = true;
            boolean progressed = false;
            for (int d : dims) {
                int cur = wm.countTouching(t, d);
                List<Integer> best = new ArrayList<>();
                int bestCount = cur + 1;
                for (int f = 0; f < dimSize[d]; f++) {
                    t[d] = f;
                    int c = wm.countTouching(t, d);
                    if (c < bestCount) {
                        bestCount = c;
                        best.clear();
                        best.add(f);
                    } else if (c == bestCount) {
                        best.add(f);
                    }
                }
                if (bestCount < cur) {
                    progressed = true;
                }
                t[d] = best.get(rng.nextInt(best.size()));
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
        return !wm.anyViolated(t);
    }
}
