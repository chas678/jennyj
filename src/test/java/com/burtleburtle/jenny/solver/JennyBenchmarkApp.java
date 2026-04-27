package com.burtleburtle.jenny.solver;

import ai.timefold.solver.benchmark.api.PlannerBenchmark;
import ai.timefold.solver.benchmark.api.PlannerBenchmarkFactory;
import ai.timefold.solver.benchmark.config.PlannerBenchmarkConfig;
import ai.timefold.solver.benchmark.config.SolverBenchmarkConfig;
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

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Benchmark harness using Timefold's built-in {@link PlannerBenchmark}.
 * Runs three problems (small / medium / self-test) against four solver
 * configurations and writes an HTML report to
 * {@code target/benchmark-results/}.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn -q test-compile
 *   mvn exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.burtleburtle.jenny.solver.JennyBenchmarkApp
 * }</pre>
 *
 * <p>Or directly:
 * <pre>{@code
 *   java -cp $(mvn -q dependency:build-classpath -Dmdep.includeScope=test -DincludeScope=test \
 *           -Dsilent=true -Dmdep.outputFile=/dev/stdout):target/test-classes:target/classes \
 *       com.burtleburtle.jenny.solver.JennyBenchmarkApp
 * }</pre>
 *
 * <p>The output HTML report includes score charts, time-spent histograms,
 * and per-config statistics.
 */
public final class JennyBenchmarkApp {

    private JennyBenchmarkApp() {
    }

    public static void main(String[] args) {
        JennySolution small = createSmallProblem();
        JennySolution medium = createMediumProblem();
        JennySolution selfTest = createSelfTestProblem();

        PlannerBenchmarkConfig benchmarkConfig = new PlannerBenchmarkConfig()
                .withBenchmarkDirectory(new File("target/benchmark-results"))
                .withWarmUpSecondsSpentLimit(5L)
                .withSolverBenchmarkConfigList(List.of(
                        named("MultiPhase-30s",
                                JennySolverFactory.createConfig(small.getAllowedTuples().size())
                                        .withTerminationConfig(spent(30))),
                        named("MultiPhase-60s",
                                JennySolverFactory.createConfig(medium.getAllowedTuples().size())
                                        .withTerminationConfig(spent(60))),
                        named("MultiPhase-120s",
                                JennySolverFactory.createConfig(selfTest.getAllowedTuples().size())
                                        .withTerminationConfig(spent(120)))));

        PlannerBenchmarkFactory factory = PlannerBenchmarkFactory.create(benchmarkConfig);
        PlannerBenchmark benchmark = factory.buildPlannerBenchmark(small, medium, selfTest);
        benchmark.benchmark();
        System.out.println("Benchmark report written under target/benchmark-results/");
    }

    private static SolverBenchmarkConfig named(String name, SolverConfig config) {
        return new SolverBenchmarkConfig().withName(name).withSolverConfig(config);
    }

    private static TerminationConfig spent(long seconds) {
        return new TerminationConfig().withSpentLimit(Duration.ofSeconds(seconds));
    }

    /** 3-binary-dim pairs — trivial. */
    private static JennySolution createSmallProblem() {
        return buildSeededProblem(
                List.of(new Dimension(0, 2), new Dimension(1, 2), new Dimension(2, 2)),
                2, List.of());
    }

    /** 6 mixed dims, pairwise — comparable to jenny.c demo. */
    private static JennySolution createMediumProblem() {
        return buildSeededProblem(
                List.of(new Dimension(0, 4), new Dimension(1, 2), new Dimension(2, 4),
                        new Dimension(3, 2), new Dimension(4, 4), new Dimension(5, 2)),
                2, List.of());
    }

    /** The jenny self-test (-n3 4 4 3 3 3 3 3 3 4 3 3 4 + 13 withouts). */
    private static JennySolution createSelfTestProblem() {
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
        return buildSeededProblem(dimensions, 3, withouts);
    }

    private static JennySolution buildSeededProblem(
            List<Dimension> dimensions, int tupleSize, List<Without> withouts) {
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, tupleSize, withouts);
        Random rnd = new Random(0L);
        List<Map<Dimension, Feature>> greedyTests = GreedyInitializer.buildInitialTests(
                dimensions, tuples, withouts, rnd);

        int slotCount = Math.max(greedyTests.size() + 20, Math.max(tuples.size() / 4, 50));
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;

        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            Map<Dimension, Feature> greedy = greedyTests.get(i);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(greedy.get(d));
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
        return new JennySolution(dimensions, tuples, withouts, testCases, testCells);
    }
}
