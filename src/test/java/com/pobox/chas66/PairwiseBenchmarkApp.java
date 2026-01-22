package com.pobox.chas66;

import ai.timefold.solver.benchmark.api.PlannerBenchmark;
import ai.timefold.solver.benchmark.api.PlannerBenchmarkFactory;
import ai.timefold.solver.benchmark.config.PlannerBenchmarkConfig;
import ai.timefold.solver.benchmark.config.SolverBenchmarkConfig;

import java.io.File;

/**
 * Benchmark runner for comparing solver configurations on various pairwise testing problems.
 *
 * Generates an HTML report with:
 * - Score comparison charts
 * - Time spent analysis
 * - Statistical summaries (best/average/worst)
 * - Configuration details
 *
 * Usage:
 *   mvn exec:java
 *
 * The benchmark configuration is created programmatically using PairwiseSolverFactory.
 */
public class PairwiseBenchmarkApp {

    public static void main(String[] args) {
        // Create benchmark config programmatically
        PlannerBenchmarkConfig benchmarkConfig = new PlannerBenchmarkConfig()
                .withBenchmarkDirectory(new File("target/benchmark-results"))
                .withWarmUpSecondsSpentLimit(5L)
                .withSolverBenchmarkConfigList(java.util.List.of(
                        // Incremental 30s
                        new SolverBenchmarkConfig()
                                .withName("Incremental-30s")
                                .withSolverConfig(PairwiseSolverFactory.createConfig()
                                        .withTerminationConfig(new ai.timefold.solver.core.config.solver.termination.TerminationConfig()
                                                .withSecondsSpentLimit(30L))),

                        // Incremental 60s
                        new SolverBenchmarkConfig()
                                .withName("Incremental-60s")
                                .withSolverConfig(PairwiseSolverFactory.createConfig()
                                        .withTerminationConfig(new ai.timefold.solver.core.config.solver.termination.TerminationConfig()
                                                .withSecondsSpentLimit(60L))),

                        // Easy 30s (for comparison)
                        new SolverBenchmarkConfig()
                                .withName("Easy-30s")
                                .withSolverConfig(PairwiseSolverFactory.createConfig()
                                        .withScoreDirectorFactory(new ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig()
                                                .withEasyScoreCalculatorClass(PairwiseEasyScoreCalculator.class))
                                        .withTerminationConfig(new ai.timefold.solver.core.config.solver.termination.TerminationConfig()
                                                .withSecondsSpentLimit(30L))),

                        // Incremental NoSwap 30s
                        new SolverBenchmarkConfig()
                                .withName("Incremental-NoSwap-30s")
                                .withSolverConfig(PairwiseSolverFactory.createConfigWithoutSwaps()
                                        .withTerminationConfig(new ai.timefold.solver.core.config.solver.termination.TerminationConfig()
                                                .withSecondsSpentLimit(30L)))
                ));

        PlannerBenchmarkFactory benchmarkFactory = PlannerBenchmarkFactory.create(benchmarkConfig);

        PlannerBenchmark benchmark = benchmarkFactory.buildPlannerBenchmark(
                createSmallProblem(),
                createMediumProblem(),
                createLargeProblem()
        );

        benchmark.benchmarkAndShowReportInBrowser();
    }

    /**
     * Small problem: 3 dimensions, 2-way coverage
     * Expected: ~9 rows, fast to solve
     */
    private static PairwiseSolution createSmallProblem() {
        JennyTF jenny = new JennyTF();
        return jenny.createInitialSolution(2, java.util.List.of(3, 3, 2), java.util.List.of(), null);
    }

    /**
     * Medium problem: 6 dimensions, 2-way coverage
     * Comparable to jenny.c example
     */
    private static PairwiseSolution createMediumProblem() {
        JennyTF jenny = new JennyTF();
        return jenny.createInitialSolution(2, java.util.List.of(4, 2, 4, 2, 4, 2), java.util.List.of(), null);
    }

    /**
     * Large problem: 12 dimensions, 3-way coverage, 13 constraints
     * The challenging real-world case
     */
    private static PairwiseSolution createLargeProblem() {
        JennyTF jenny = new JennyTF();
        java.util.List<String> constraints = java.util.List.of(
            "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc",
            "6a9abc", "6a10ab", "11a12abc", "11bc12d", "4c5ab",
            "1a3a", "1a9a", "3a9c"
        );
        return jenny.createInitialSolution(3,
            java.util.List.of(4, 4, 3, 3, 3, 3, 3, 3, 4, 3, 3, 4),
            constraints,
            null);
    }
}
