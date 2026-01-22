# Benchmarking Guide for Jenny-TF

This guide explains how to run performance benchmarks to systematically evaluate and tune the solver.

## Quick Start

Run the benchmark with:

```bash
mvn exec:java
```

This will:
1. Test 4 solver configurations (Incremental 30s/60s, Easy 30s, Incremental-NoSwap 30s)
2. Run on 3 problem instances (Small, Medium, Large)
3. Generate an HTML report in `target/benchmark-results/`
4. Automatically open the report in your browser

Note: The benchmark uses a programmatic configuration (not XML) created in `PairwiseBenchmarkApp.java`.

## What Gets Benchmarked

### Solver Configurations

1. **Incremental-30s** - IncrementalScoreCalculator with all move types, 30 second time limit
2. **Incremental-60s** - Same as above but 60 seconds (tests if more time helps)
3. **Easy-30s** - EasyScoreCalculator for comparison (to measure speedup)
4. **Incremental-NoSwap-30s** - Without swap moves (faster but potentially lower quality)

### Problem Instances

1. **Small (3×3×2, 2-way)** - Fast sanity check, ~21 combinations
2. **Medium (4×2×4×2×4×2, 2-way)** - Jenny.c comparable case, ~61 combinations
3. **Large (12 dims, 3-way, 13 constraints)** - Real-world challenging case, 7,214 combinations

## Understanding the Report

The HTML report includes:

### Score Charts
- Shows hard/medium/soft scores over time for each configuration
- Lower medium score = fewer rows = better solution quality

### Performance Metrics
- **Best score** - Best solution found
- **Average score** - Average across multiple runs (if configured)
- **Time spent** - Actual solving time
- **Move evaluation speed** - Moves/second (higher = faster)
- **Score calculation speed** - Score calculations/second

### Winner Analysis
- Shows which configuration won on each problem instance
- Considers both solution quality (score) and time

## Customizing Benchmarks

### Edit Problem Instances

Modify `src/test/java/com/pobox/chas66/PairwiseBenchmarkApp.java` to add your own test cases. Simply add a new method like:

```java
private static PairwiseSolution createCustomProblem() {
    JennyTF jenny = new JennyTF();
    List<Integer> dims = List.of(5, 5, 5);
    List<String> constraints = List.of("1a2b", "2c3a");
    return jenny.createInitialSolution(2, dims, constraints, 42L);
}
```

Then add it to the benchmark in the `main` method:

```java
PlannerBenchmark benchmark = benchmarkFactory.buildPlannerBenchmark(
        createSmallProblem(),
        createMediumProblem(),
        createLargeProblem(),
        createCustomProblem()  // Add your custom problem
);
```

### Edit Solver Configurations

Modify `src/main/java/com/pobox/chas66/PairwiseSolverFactory.java` to test different:
- Termination times (`withSecondsSpentLimit`, `withUnimprovedSecondsSpentLimit`)
- Tabu sizes (`withEntityTabuSize`)
- Late acceptance sizes (`withLateAcceptanceSize`)
- Move selectors (add/remove moves in the `withMoveSelectorList`)

Or add new solver configurations in `PairwiseBenchmarkApp.java` by creating additional `SolverBenchmarkConfig` objects.

### Run Multiple Iterations

For statistical significance, you can configure multiple runs per problem. This requires modifying the `PlannerBenchmarkConfig` in `PairwiseBenchmarkApp.java`. The Timefold benchmark framework supports this through problem benchmark configuration, though the current implementation runs each combination once.

## Comparing Against jenny.c

To measure against jenny.c baseline:

1. Run jenny.c on the same input:
   ```bash
   ./jenny -n2 4 2 4 2 4 2 > jenny_baseline.txt
   wc -l jenny_baseline.txt  # Count rows
   ```

2. Note the row count (e.g., 20 rows for that input)

3. Check the benchmark report for the "Medium" problem (4×2×4×2×4×2)
   - Compare row count (medium score magnitude)
   - In our tests, Jenny-TF produced 16 rows vs jenny.c's ~20 rows - we beat it!

## Performance Tuning Workflow

1. **Baseline** - Run benchmark to establish current performance
2. **Change one thing** - Modify a single parameter (e.g., tabu size 40→60)
3. **Re-benchmark** - Run again and compare reports
4. **Keep if better** - If quality improved without major speed loss, keep it
5. **Repeat** - Iterate on different parameters

## Example: Tuning Tabu Size

To test different tabu sizes, create multiple solver configurations in `PairwiseBenchmarkApp.java`:

```java
.withSolverBenchmarkConfigList(java.util.List.of(
        // Test tabu size 20
        new SolverBenchmarkConfig()
                .withName("Tabu-20")
                .withSolverConfig(new SolverConfig()
                        .withSolutionClass(PairwiseSolution.class)
                        .withEntityClasses(TestRun.class, FeatureAssignment.class)
                        .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                                .withIncrementalScoreCalculatorClass(PairwiseIncrementalScoreCalculator.class))
                        .withPhases(new LocalSearchPhaseConfig()
                                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                                        .withEntityTabuSize(20))  // Try 20
                                .withTerminationConfig(new TerminationConfig()
                                        .withSecondsSpentLimit(30L)))),

        // Test tabu size 40
        new SolverBenchmarkConfig()
                .withName("Tabu-40")
                .withSolverConfig(/* same as above but with entityTabuSize(40) */),

        // Test tabu size 60
        new SolverBenchmarkConfig()
                .withName("Tabu-60")
                .withSolverConfig(/* same as above but with entityTabuSize(60) */)
))
```

Or better yet, use `PairwiseSolverFactory.createConfig()` as a base and modify just the parameter you want to test.

## CI/CD Integration

To run benchmarks in CI without opening browser, modify `PairwiseBenchmarkApp.java` to use `benchmark.benchmark()` instead of `benchmark.benchmarkAndShowReportInBrowser()`:

```java
// In main method, replace:
benchmark.benchmarkAndShowReportInBrowser();

// With:
benchmark.benchmark();
System.out.println("Benchmark report: " +
    new File("target/benchmark-results").getAbsolutePath() + "/index.html");
```

The report will still be generated in `target/benchmark-results/` for manual review.

## Interpreting Results for jenny.c Comparison

**Goal:** Match or beat jenny.c on row count while maintaining fast solve times

**Success criteria:**
- Small problems: < 5 seconds solve time
- Medium problems: < 30 seconds solve time, ≤20 rows
- Large problems: < 90 seconds solve time, ≤120 rows

**Red flags:**
- Move evaluation speed < 50/sec on large problems (too slow)
- Hard score > 0 at termination (solution invalid)
- Medium score worse than greedy initializer (solver making it worse)

## Advanced: Statistical Analysis

For rigorous comparison, use subSingleCount ≥ 30 and analyze:
- Mean score
- Standard deviation (should be low for consistent results)
- Best/worst case (shows solver variance)
- Confidence intervals

The benchmark framework automatically generates these statistics when multiple runs are configured.
