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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 goal-line test: solve the jenny self-test benchmark and assert
 * we match or beat jenny.c's 116-test result with 0 uncovered tuples.
 *
 * <p>Tagged {@code benchmark} so it is excluded from default {@code mvn test}.
 * Run explicitly with: {@code mvn test -Dgroups=benchmark}.
 */
@Tag("benchmark")
class JennyBeatsBenchmarkTest {

    private static final int JENNY_C_TEST_COUNT = 116;
    private static final long MAX_WALL_TIME_MS = 130_000L;

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

        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofMillis(MAX_WALL_TIME_MS)));

        long start = System.currentTimeMillis();
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);
        long elapsed = System.currentTimeMillis() - start;

        long activeTests = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        long uncovered = tuples.stream()
                .filter(t -> solved.getTestCases().stream()
                        .noneMatch(tc -> tc.isActiveFlag() && tc.coversTuple(t)))
                .count();

        System.out.printf("benchmark: active=%d, uncovered=%d, elapsed=%dms%n",
                activeTests, uncovered, elapsed);

        assertEquals(0, uncovered, "Solution must cover every allowed tuple");
        assertTrue(activeTests <= JENNY_C_TEST_COUNT,
                "Active test count " + activeTests + " must be <= jenny.c's " + JENNY_C_TEST_COUNT);
        assertTrue(elapsed <= MAX_WALL_TIME_MS,
                "Wall time " + elapsed + "ms exceeds limit " + MAX_WALL_TIME_MS + "ms");
    }
}
