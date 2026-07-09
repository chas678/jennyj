package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.phase.PhaseCommand;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.phase.PhaseConfig;
import ai.timefold.solver.core.config.phase.custom.CustomPhaseConfig;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, C-independent unit tests for the SP4 custom phases. Each runs a solver
 * whose only phase is the command under test on a hand-built, fully-initialised
 * solution, so it exercises the port directly (no local search involved).
 */
class PhaseCommandTest {

    /** ShrinkPhaseCommand must deactivate duplicate rows with hard score intact. */
    @Test
    void shrink_removesRedundantRows() {
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2));
        List<Without> withouts = List.of();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, withouts);
        assertEquals(4, tuples.size());

        // aa, ab, ba, bb + duplicate aa, ab -> two rows are redundant.
        int[][] rows = {{0, 0}, {0, 1}, {1, 0}, {1, 1}, {0, 0}, {0, 1}};
        JennySolution problem = build(dims, tuples, withouts, rows, rows.length, 0);

        JennySolution solved = solveSinglePhase(problem, ShrinkPhaseCommand.class);

        long active = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        assertEquals(0, solved.getScore().hardScore(), "shrink must not regress hard score");
        assertEquals(4, active, "the two duplicate rows must be deactivated");
        assertAllCovered(solved);
    }

    /**
     * Shrink with ndim &gt; tupleSize (3 binary dims, pairs) — guards the
     * combo/key indexing (feature scratch must be sized by dimension count).
     */
    @Test
    void shrink_threeDims_removesDuplicate() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2), new Dimension(1, 2), new Dimension(2, 2));
        List<Without> withouts = List.of();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, withouts);
        assertEquals(12, tuples.size());

        // 8 full assignments cover all 12 pairs; append one duplicate to drop.
        int[][] rows = {
                {0, 0, 0}, {0, 0, 1}, {0, 1, 0}, {0, 1, 1},
                {1, 0, 0}, {1, 0, 1}, {1, 1, 0}, {1, 1, 1},
                {0, 0, 0}};
        JennySolution problem = build(dims, tuples, withouts, rows, rows.length, 0);

        JennySolution solved = solveSinglePhase(problem, ShrinkPhaseCommand.class);

        long active = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        assertEquals(0, solved.getScore().hardScore(), "shrink must not regress hard score");
        assertTrue(active < 9, "the duplicate row must be deactivated (was " + active + ")");
        assertAllCovered(solved);
    }

    /** RecoverPhaseCommand must cover a coverable-but-uncovered tuple. */
    @Test
    void recover_coversMissingTuple() {
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2));
        List<Without> withouts = List.of();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, withouts);

        // Active: aa, ab, ba -> (1b,2b) is uncovered. Two inactive spares follow.
        int[][] rows = {{0, 0}, {0, 1}, {1, 0}, {0, 0}, {0, 0}};
        JennySolution problem = build(dims, tuples, withouts, rows, 3, 0);

        JennySolution solved = solveSinglePhase(problem, RecoverPhaseCommand.class);

        assertEquals(0, solved.getScore().hardScore(),
                "recover must cover the missing tuple (hard score 0)");
        assertAllCovered(solved);
    }

    /**
     * SP4 budget plumbing: a larger {@code --time-limit-seconds} must give the
     * local-search phases proportionally more time (the old config hard-coded
     * 60s/30s caps that ignored the requested limit).
     */
    @Test
    void largerTimeLimitPropagatesToLocalSearchPhases() {
        long small = firstLsSpentLimit(JennySolverFactory.createConfig(30));
        long large = firstLsSpentLimit(JennySolverFactory.createConfig(300));
        System.out.printf("SP4-BUDGET consolidate secondsSpentLimit: 30s->%d, 300s->%d%n",
                small, large);
        assertEquals(14L, small, "30s budget -> 45% consolidate");
        assertEquals(135L, large, "300s budget -> 45% consolidate");
        assertTrue(large > small, "larger time limit must give LS phases more time");
    }

    /** secondsSpentLimit of the first local-search phase in the config. */
    private static long firstLsSpentLimit(SolverConfig config) {
        for (PhaseConfig<?> phase : config.getPhaseConfigList()) {
            if (phase instanceof LocalSearchPhaseConfig ls) {
                return ls.getTerminationConfig().getSecondsSpentLimit();
            }
        }
        throw new AssertionError("no local-search phase found");
    }

    // ---- helpers ----------------------------------------------------------

    private static JennySolution solveSinglePhase(JennySolution problem,
                                                  Class<? extends PhaseCommand> command) {
        SolverConfig config = new SolverConfig()
                .withSolutionClass(JennySolution.class)
                .withEntityClasses(TestCase.class, TestCell.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withConstraintProviderClass(JennyConstraintProvider.class))
                .withPhaseList(List.<PhaseConfig>of(new CustomPhaseConfig()
                        .withCustomPhaseCommandClassList(
                                List.<Class<? extends PhaseCommand>>of(command))));
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        return solver.solve(problem);
    }

    /**
     * Builds a solution. The first {@code activeCount} rows are active; the rest
     * are inactive spare capacity. The first {@code pinnedCount} rows are pinned.
     */
    private static JennySolution build(List<Dimension> dims, List<AllowedTuple> tuples,
                                       List<Without> withouts, int[][] rows,
                                       int activeCount, int pinnedCount) {
        List<TestCase> testCases = new ArrayList<>();
        List<TestCell> testCells = new ArrayList<>();
        long cellId = 0;
        for (int i = 0; i < rows.length; i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(i < activeCount);
            boolean pinned = i < pinnedCount;
            tc.setPinned(pinned);
            List<TestCell> owned = new ArrayList<>();
            for (Dimension d : dims) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(rows[i][d.index()]));
                cell.setPinned(pinned);
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
        return new JennySolution(dims, tuples, withouts, testCases, testCells);
    }

    private static void assertAllCovered(JennySolution solved) {
        for (AllowedTuple tuple : solved.getAllowedTuples()) {
            boolean covered = solved.getTestCases().stream()
                    .anyMatch(tc -> tc.isActiveFlag() && tc.coversTuple(tuple));
            assertTrue(covered, "tuple must be covered: " + tuple);
        }
    }
}
