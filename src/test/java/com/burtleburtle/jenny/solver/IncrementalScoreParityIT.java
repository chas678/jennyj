package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.impl.solver.DefaultSolver;
import com.burtleburtle.jenny.bootstrap.GreedyInitializer;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.cli.WithoutParser;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP2 validation: (1) prove the hand-rolled
 * {@link JennyIncrementalScoreCalculator} produces the same score as the
 * declarative {@link JennyConstraintProvider} on every move, by wiring the
 * provider as the {@code assertionScoreDirectorFactory} and solving under
 * {@code FULL_ASSERT} (Timefold throws a score-corruption exception the moment
 * the two diverge); and (2) measure move-evaluation throughput of the
 * incremental calculator vs the constraint provider on the jenny self-test.
 */
class IncrementalScoreParityIT {

    /**
     * FULL_ASSERT on a small but representative n=3 problem (with withouts),
     * solved to optimum. Every move — cell change, active toggle, randomize-row,
     * deactivate-redundant, merge — is cross-checked against the constraint
     * provider. Reaching hard==0 without a ScoreCorruptionException proves the
     * calculator matches the provider across the full move repertoire.
     */
    @Test
    void fullAssert_smallProblem_matchesConstraintProviderToOptimum() {
        List<Dimension> dims = List.of(
                new Dimension(0, 3), new Dimension(1, 3), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3));
        String[] withoutStrings = {"1a2a", "3b4c", "1c5b"};
        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dims));
        }
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 3, withouts);
        JennySolution problem = buildProblem(dims, tuples, withouts);

        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withEnvironmentMode(EnvironmentMode.FULL_ASSERT)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(JennyIncrementalScoreCalculator.class)
                        .withAssertionScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                                .withConstraintProviderClass(JennyConstraintProvider.class)))
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(60)));

        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem); // throws on any score corruption

        long uncovered = countUncovered(solved, tuples);
        System.out.printf("FULL_ASSERT small: score=%s, active=%d, uncovered=%d%n",
                solved.getScore(), countActive(solved), uncovered);
        assertEquals(0, solved.getScore().hardScore(),
                "incremental hard score must reach 0 (validated move-by-move vs ConstraintProvider)");
        assertEquals(0, uncovered, "every tuple must be covered");
    }

    /**
     * FULL_ASSERT on the actual jenny self-test workload over the <em>full
     * integrated pipeline</em> built by {@link JennySolverFactory#createConfig(long)}:
     * LS consolidate (with SP3 {@code EvictRow} in the move union) → SP4
     * {@link ShrinkPhaseCommand} → LS polish → {@link ShrinkPhaseCommand} +
     * {@link RecoverPhaseCommand}. Because {@code createConfig} scales the
     * per-phase budgets from the requested time limit, a modest budget still
     * reaches every phase — so the incremental calculator is cross-checked
     * against the {@link JennyConstraintProvider} across EvictRow's composite
     * moves and the custom phases' Move-based edits, not just phase-1 local
     * search. (The earlier XML-based, 25s-capped version never got past phase 1
     * — its phase-1 budget alone exceeds the global cap — so it silently failed
     * to exercise SP3/SP4; see the SP integration verification.)
     *
     * <p>Pass conditions: no {@code ScoreCorruptionException} (thrown the moment
     * the incremental score diverges from the provider), and the recover phase
     * drives the incremental hard score to 0 — confirming the calculator stays
     * correct through recovery.
     */
    @Test
    void fullAssert_fullPipeline_noScoreCorruption() {
        SelfTest st = selfTest();
        JennySolution problem = buildProblem(st.dims, st.tuples, st.withouts);

        // createConfig(30) → consolidate 18s / polish 9s + the deterministic
        // custom phases, so all four phases run well inside the 60s ceiling even
        // under FULL_ASSERT's per-move assertion overhead.
        SolverConfig config = JennySolverFactory.createConfig(30)
                .withRandomSeed(0L)
                .withEnvironmentMode(EnvironmentMode.FULL_ASSERT)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(JennyIncrementalScoreCalculator.class)
                        .withAssertionScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                                .withConstraintProviderClass(JennyConstraintProvider.class)))
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(60)));

        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem); // throws on any score corruption
        long uncovered = countUncovered(solved, st.tuples);
        System.out.printf("FULL_ASSERT full-pipeline: score=%s, active=%d, uncovered=%d%n",
                solved.getScore(), countActive(solved), uncovered);
        // Reaching here means no move (incl. EvictRow / Shrink / Recover) diverged
        // from the ConstraintProvider; hard==0 confirms recovery ran correctly.
        assertEquals(0, solved.getScore().hardScore(),
                "incremental hard score must reach 0 through the full pipeline "
                        + "(validated move-by-move vs ConstraintProvider under FULL_ASSERT)");
        assertEquals(0, uncovered, "every allowed tuple must be covered");
    }

    /**
     * Move-evaluation throughput: the incremental calculator vs the constraint
     * provider on the self-test, each solved for a fixed budget in normal mode.
     * Reads Timefold's own move-evaluation-speed counter.
     */
    @Test
    void throughput_incrementalBeatsConstraintProvider() {
        SelfTest st = selfTest();
        long budgetSeconds = 15;

        long providerSpeed = measureMoveEvalSpeed(
                new ScoreDirectorFactoryConfig()
                        .withConstraintProviderClass(JennyConstraintProvider.class),
                st, budgetSeconds);
        long incrementalSpeed = measureMoveEvalSpeed(
                new ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(JennyIncrementalScoreCalculator.class),
                st, budgetSeconds);

        double speedup = providerSpeed == 0 ? Double.NaN
                : (double) incrementalSpeed / providerSpeed;
        System.out.printf(
                "THROUGHPUT (self-test, %ds/side): constraintProvider=%,d moves/s, "
                        + "incremental=%,d moves/s, speedup=%.1fx%n",
                budgetSeconds, providerSpeed, incrementalSpeed, speedup);

        assertTrue(incrementalSpeed > providerSpeed,
                "incremental calculator must evaluate moves faster than the constraint provider "
                        + "(incremental=" + incrementalSpeed + ", provider=" + providerSpeed + ")");
    }

    private long measureMoveEvalSpeed(
            ScoreDirectorFactoryConfig scoreDirectorFactory, SelfTest st, long budgetSeconds) {
        JennySolution problem = buildProblem(st.dims, st.tuples, st.withouts);
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withScoreDirectorFactory(scoreDirectorFactory)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(budgetSeconds)));
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        solver.solve(problem);
        return ((DefaultSolver<JennySolution>) solver).getMoveEvaluationSpeed();
    }

    // ================================================================
    // Problem builders
    // ================================================================

    private record SelfTest(List<Dimension> dims, List<Without> withouts, List<AllowedTuple> tuples) {
    }

    private static SelfTest selfTest() {
        List<Dimension> dims = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4));
        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"};
        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dims));
        }
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 3, withouts);
        return new SelfTest(dims, withouts, tuples);
    }

    private static JennySolution buildProblem(
            List<Dimension> dims, List<AllowedTuple> tuples, List<Without> withouts) {
        Random rnd = new Random(0);
        List<Map<Dimension, Feature>> greedyTests =
                GreedyInitializer.buildInitialTests(dims, tuples, withouts, rnd);

        int slotCount = Math.max(greedyTests.size() + 20, 128);
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dims.size());
        long cellId = 0;
        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            Map<Dimension, Feature> greedyTest = greedyTests.get(i);
            List<TestCell> owned = new ArrayList<>(dims.size());
            for (Dimension d : dims) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(greedyTest.get(d));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
        for (int i = greedyTests.size(); i < slotCount; i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            List<TestCell> owned = new ArrayList<>(dims.size());
            for (Dimension d : dims) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(0));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
        return new JennySolution(dims, tuples, withouts, testCases, testCells);
    }

    private static long countActive(JennySolution s) {
        return s.getTestCases().stream().filter(TestCase::isActiveFlag).count();
    }

    private static long countUncovered(JennySolution s, List<AllowedTuple> tuples) {
        return tuples.stream()
                .filter(t -> s.getTestCases().stream()
                        .noneMatch(tc -> tc.isActiveFlag() && tc.coversTuple(t)))
                .count();
    }
}
