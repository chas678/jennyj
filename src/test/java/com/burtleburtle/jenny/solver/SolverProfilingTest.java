package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.api.solver.Solver;
import com.burtleburtle.jenny.bootstrap.GreedyInitializer;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import com.burtleburtle.jenny.cli.WithoutParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Profiling test to analyze solver performance and initialization efficiency.
 * Uses jenny self-test benchmark as the profiling workload.
 */
class SolverProfilingTest {

    @Test
    void profileJennySelfTest() {
        System.out.println("\n=== PROFILING: Jenny Self-Test Benchmark ===\n");

        // Build jenny self-test problem: -n3 4 4 3 3 3 3 3 3 4 3 3 4 with 13 -w constraints
        List<Dimension> dimensions = List.of(
                new Dimension(0, 4),  // 1
                new Dimension(1, 4),  // 2
                new Dimension(2, 3),  // 3
                new Dimension(3, 3),  // 4
                new Dimension(4, 3),  // 5
                new Dimension(5, 3),  // 6
                new Dimension(6, 3),  // 7
                new Dimension(7, 3),  // 8
                new Dimension(8, 4),  // 9
                new Dimension(9, 3),  // 10
                new Dimension(10, 3), // 11
                new Dimension(11, 4)  // 12
        );

        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"
        };

        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }

        long tupleEnumStart = System.currentTimeMillis();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, 3, withouts);
        long tupleEnumTime = System.currentTimeMillis() - tupleEnumStart;

        System.out.println("Problem size:");
        System.out.println("  Dimensions: " + dimensions.size());
        System.out.println("  Tuples to cover: " + tuples.size());
        System.out.println("  Without constraints: " + withouts.size());
        System.out.println("  Tuple enumeration time: " + tupleEnumTime + "ms\n");

        // Profile greedy initialization
        System.out.println("=== Phase 1: Greedy Initialization ===");
        Random rnd = new Random(0);
        long greedyStart = System.currentTimeMillis();
        List<Map<Dimension, Feature>> greedyTests = GreedyInitializer.buildInitialTests(
                dimensions, tuples, withouts, rnd);
        long greedyTime = System.currentTimeMillis() - greedyStart;

        System.out.println("  Tests generated: " + greedyTests.size());
        System.out.println("  Time elapsed: " + greedyTime + "ms");
        System.out.println("  Tests per second: " + (greedyTests.size() * 1000L / greedyTime) + "\n");

        // Build planning problem
        System.out.println("=== Phase 2: Solution Construction ===");
        long constructStart = System.currentTimeMillis();
        int slotCount = Math.max(greedyTests.size() + 20, 128);
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;

        // Add greedy tests (unpinned — solver may modify/deactivate them)
        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);

            Map<Dimension, Feature> greedyTest = greedyTests.get(i);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                Feature assignedFeature = greedyTest.get(d);
                cell.setFeature(assignedFeature);
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }

        // Add empty slots
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

        JennySolution problem = new JennySolution(dimensions, tuples, withouts, testCases, testCells);
        long constructTime = System.currentTimeMillis() - constructStart;
        System.out.println("  Total slots: " + slotCount);
        System.out.println("  Total cells: " + testCells.size());
        System.out.println("  Construction time: " + constructTime + "ms\n");

        // Profile solver with FULL_ASSERT mode for detailed tracking
        System.out.println("=== Phase 3: Solver Run (with FULL_ASSERT profiling) ===");
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withEnvironmentMode(EnvironmentMode.FULL_ASSERT) // Enable assertions and checks
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(10)));

        long solveStart = System.currentTimeMillis();
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);
        long solveTime = System.currentTimeMillis() - solveStart;

        // Analyze results
        long activeTests = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        long uncoveredCount = tuples.stream()
                .filter(tuple -> solved.getTestCases().stream()
                        .noneMatch(tc -> tc.isActiveFlag() && tc.coversTuple(tuple)))
                .count();

        System.out.println("  Solve time: " + solveTime + "ms");
        System.out.println("  Final score: " + solved.getScore());
        System.out.println("  Active tests: " + activeTests);
        System.out.println("  Uncovered tuples: " + uncoveredCount + "\n");

        // Summary
        System.out.println("=== Performance Summary ===");
        long totalTime = tupleEnumTime + greedyTime + constructTime + solveTime;
        System.out.println("  Tuple enumeration: " + tupleEnumTime + "ms (" + (tupleEnumTime * 100 / totalTime) + "%)");
        System.out.println("  Greedy init:       " + greedyTime + "ms (" + (greedyTime * 100 / totalTime) + "%)");
        System.out.println("  Solution construct:" + constructTime + "ms (" + (constructTime * 100 / totalTime) + "%)");
        System.out.println("  Solver run:        " + solveTime + "ms (" + (solveTime * 100 / totalTime) + "%)");
        System.out.println("  TOTAL:             " + totalTime + "ms\n");

        System.out.println("=== Profiling Complete ===\n");
    }

    @Test
    void profileNormalMode() {
        System.out.println("\n=== PROFILING: Normal Mode (Production Performance) ===\n");

        // Build same problem as above
        List<Dimension> dimensions = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4)
        );

        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"
        };

        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, 3, withouts);

        Random rnd = new Random(0);
        long greedyStart = System.currentTimeMillis();
        List<Map<Dimension, Feature>> greedyTests = GreedyInitializer.buildInitialTests(
                dimensions, tuples, withouts, rnd);
        long greedyTime = System.currentTimeMillis() - greedyStart;

        // Build solution
        int slotCount = Math.max(greedyTests.size() + 20, 128);
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;

        // Add greedy tests (unpinned — solver may modify/deactivate them)
        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
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

        JennySolution problem = new JennySolution(dimensions, tuples, withouts, testCases, testCells);

        // Normal mode (production settings)
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(10)));

        long solveStart = System.currentTimeMillis();
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);
        long solveTime = System.currentTimeMillis() - solveStart;

        long activeTests = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        long uncoveredCount = tuples.stream()
                .filter(tuple -> solved.getTestCases().stream()
                        .noneMatch(tc -> tc.isActiveFlag() && tc.coversTuple(tuple)))
                .count();

        System.out.println("Results:");
        System.out.println("  Greedy init time:  " + greedyTime + "ms");
        System.out.println("  Solve time:        " + solveTime + "ms");
        System.out.println("  Total time:        " + (greedyTime + solveTime) + "ms");
        System.out.println("  Final score:       " + solved.getScore());
        System.out.println("  Active tests:      " + activeTests);
        System.out.println("  Uncovered tuples:  " + uncoveredCount);
        System.out.println("\n=== Normal Mode Complete ===\n");
    }
}
