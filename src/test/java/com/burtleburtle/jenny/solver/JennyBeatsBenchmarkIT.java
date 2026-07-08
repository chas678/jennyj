package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 goal-line test: solve the jenny self-test benchmark and assert
 * we match or beat jenny.c's 116-test result with 0 uncovered tuples and 0
 * without violations.
 *
 * <p>Coverage and without-freedom are both verified against the ground-truth
 * {@link SolutionOracle} (recomputed from cell state), not just the solver's
 * own {@code HardSoftScore} — a reported "clean" score is not itself proof;
 * see {@link SolutionOracle} for why. The hard score is asserted {@code == 0}
 * as a second, independent check that must agree with the oracle.
 *
 * <p>Named {@code *IT} so it runs only under {@code mvn verify} (failsafe),
 * not {@code mvn test} or {@code mvn package} (surefire).
 */
class JennyBeatsBenchmarkIT {

    private static final int JENNY_C_TEST_COUNT = 116;
    // Loose sanity ceiling only. Wall-clock includes JVM warmup, GC, and OS
    // scheduling, so it is environment-sensitive (a loaded machine can stall
    // well past the solver's own budget). The authoritative bound is the
    // solver's internal 110s spent-limit set below; the functional assertions
    // (uncovered == 0, active <= 116, 0hard) are what gate correctness.
    private static final long MAX_WALL_TIME_MS = 150_000L;

    @Test
    void beatsJennyOnSelfTest() {
        List<Dimension> dimensions = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4));
        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"};

        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, 3, withouts);

        Random rnd = new Random(0);
        List<Map<Dimension, Feature>> greedyTests = GreedyInitializer.buildInitialTests(
                dimensions, tuples, withouts, rnd);

        int slotCount = Math.max(greedyTests.size() + 20, 200);
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;

        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            // Greedy tests are unpinned so the solver can deactivate or merge them.
            Map<Dimension, Feature> greedyTest = greedyTests.get(i);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
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
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(0));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }

        JennySolution problem = new JennySolution(
                dimensions, tuples, withouts, testCases, testCells);

        // Solver budget is 110s — the authoritative termination bound. It sits
        // comfortably under the loose 150s wall-clock sanity ceiling so that
        // buildSolver + measurement overhead (and moderate machine load) cannot
        // cause a false failure even if the solver exhausts its full budget.
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofMillis(110_000)));

        long start = System.currentTimeMillis();
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);
        long elapsed = System.currentTimeMillis() - start;

        long activeTests = SolutionOracle.activeTests(solved).size();
        // Ground truth, recomputed from cell state — see SolutionOracle. Deliberately
        // not trusted from solved.getScore() alone: SP0 exists because a solution can
        // report a clean-looking score while still containing without violations.
        Set<AllowedTuple> uncovered = SolutionOracle.uncoveredTuples(solved);
        List<String> withoutViolations = SolutionOracle.withoutViolations(solved);

        System.out.printf(
                "benchmark: active=%d, uncovered=%d, withoutViolations=%d, elapsed=%dms, hardScore=%d, score=%s%n",
                activeTests, uncovered.size(), withoutViolations.size(), elapsed,
                solved.getScore().hardScore(), solved.getScore());

        assertEquals(0, uncovered.size(),
                "Solution must cover every allowed tuple. Uncovered: " + uncovered);
        assertTrue(withoutViolations.isEmpty(),
                "No active test may violate a without. Violations: " + withoutViolations);
        // Independent cross-check: the oracle above recomputes from cells; this
        // confirms the solver's own score agrees. Both must be clean — either alone
        // is not sufficient proof of a valid suite.
        assertEquals(0, solved.getScore().hardScore(),
                "Hard score must be exactly 0: " + solved.getScore());
        assertTrue(activeTests <= JENNY_C_TEST_COUNT,
                "Active test count " + activeTests + " must be <= jenny.c's " + JENNY_C_TEST_COUNT);
        assertTrue(elapsed <= MAX_WALL_TIME_MS,
                "Wall time " + elapsed + "ms exceeds limit " + MAX_WALL_TIME_MS + "ms");
    }
}
