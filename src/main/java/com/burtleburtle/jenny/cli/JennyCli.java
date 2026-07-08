package com.burtleburtle.jenny.cli;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.bench.BenchRunner;
import com.burtleburtle.jenny.bootstrap.GreedyInitializer;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.solver.JennySolverFactory;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Command(
        name = "jenny",
        mixinStandardHelpOptions = false,
        versionProvider = ManifestVersionProvider.class,
        // Allow C jenny-style attached values: `-n2`, `-w1a2b`, `-ofile`.
        // Without this, picocli would also accept `-n=2`, which we don't want
        // (jenny.c never used `=`). With separator="", `-n2` is the only form.
        separator = "",
        description = "Timefold port of Bob Jenkins' jenny combinatorial test generator.")
public final class JennyCli implements Callable<Integer> {

    @Parameters(
            paramLabel = "DIM_SIZE",
            description = "One integer (2..52) per feature dimension.",
            arity = "1..*")
    private List<Integer> dimensionSizes = new ArrayList<>();

    @Option(names = "-n", description = "Tuple size (default 2, max 32).")
    private int tupleSize = 2;

    @Option(names = "-s", description = "Random seed (default 0).")
    private long seed = 0L;

    @Option(names = "-j",
            description = "Run K independent replicas in parallel and keep the smallest "
                    + "feasible suite (default 1, e.g. -j8). Parallelism is capped at the "
                    + "available processor count.")
    private int jobs = 1;

    @Option(names = "-w", description = "Without: forbidden combination. Repeatable.")
    private List<String> withoutStrings = new ArrayList<>();

    @Option(names = "-o", description = "Read existing tests from FILE (or '-' for stdin).")
    private String oldTestsFile;

    @Option(names = "-h", usageHelp = true, description = "Print help.")
    private boolean help;

    @Option(names = "--version", versionHelp = true, description = "Print version and exit.")
    private boolean versionRequested;

    @Option(names = "--time-limit-seconds",
            description = "Wall-clock time budget (default 60).")
    private long timeLimitSeconds = 60L;

    @Option(names = "--bench",
            description = "Head-to-head: fork the C jenny binary on the same input and "
                    + "print a two-row comparison table of tests and wall time.")
    private boolean bench;

    @Option(names = "--jenny-path",
            description = "Path to the C jenny binary (defaults to $JENNY_BIN "
                    + "or $HOME/src/jenny/jenny). Only used with --bench.")
    private String jennyPath;

    private PrintStream out = System.out;

    public void setOut(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new JennyCli()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        if (bench) {
            return runBench();
        }
        if (dimensionSizes.isEmpty()) {
            System.err.println("jenny: at least one dimension size is required");
            return 2;
        }
        if (tupleSize < 1 || tupleSize > 32) {
            System.err.println("jenny: -n must be in [1, 32]");
            return 2;
        }
        if (jobs < 1) {
            System.err.println("jenny: -j must be a positive integer, for example -j8");
            return 2;
        }

        List<Dimension> dimensions = new ArrayList<>(dimensionSizes.size());
        for (int i = 0; i < dimensionSizes.size(); i++) {
            dimensions.add(new Dimension(i, dimensionSizes.get(i)));
        }
        if (tupleSize > dimensions.size()) {
            System.err.println("jenny: -n exceeds number of dimensions");
            return 2;
        }

        List<Without> withouts = new ArrayList<>(withoutStrings.size());
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, tupleSize, withouts);

        // Parse existing tests from -o file (if provided)
        List<Map<Dimension, Feature>> oldTests = List.of();
        if (oldTestsFile != null && !oldTestsFile.isEmpty()) {
            try {
                oldTests = TestFileParser.parseTestFile(oldTestsFile, dimensions);
            } catch (IOException e) {
                System.err.println("jenny: cannot read test file '" + oldTestsFile + "': " + e.getMessage());
                return 2;
            } catch (IllegalArgumentException e) {
                System.err.println("jenny: " + e.getMessage());
                return 2;
            }
        }

        // -j1 (the default) takes this exact path with no executor involved, so its
        // behavior is unchanged from before -j existed.
        JennySolution solved;
        if (jobs <= 1) {
            solved = solveReplica(dimensions, tuples, withouts, oldTests, seed);
        } else {
            solved = bestOfReplicas(dimensions, tuples, withouts, oldTests, seed, jobs);
            if (solved == null) {
                return 4;
            }
        }

        for (AllowedTuple tuple : solved.getAllowedTuples()) {
            boolean covered = solved.getTestCases().stream()
                    .anyMatch(tc -> tc.isActiveFlag() && tc.coversTuple(tuple));
            if (!covered) {
                out.print(OutputFormatter.formatUncoveredTupleLine(tuple));
            }
        }

        for (TestCase tc : solved.getTestCases()) {
            if (!tc.isActiveFlag()) {
                continue;
            }
            out.print(OutputFormatter.formatTest(tc, dimensions));
        }

        return 0;
    }

    private int runBench() {
        Path jennyBin = BenchRunner.resolveJennyPath(jennyPath);
        if (!BenchRunner.jennyBinaryExists(jennyBin)) {
            System.err.println("jenny: --bench needs an executable C jenny binary at "
                    + jennyBin + " (build it with: cc -O2 -o " + jennyBin
                    + " ~/src/jenny/jenny.c)");
            return 3;
        }
        List<String> passThrough = buildPassThroughArgs();
        BenchRunner runner = new BenchRunner(jennyBin);
        try {
            BenchRunner.Result c = runner.runJennyC(passThrough, 120L);
            BenchRunner.Result tf = runner.runTimefold(passThrough);
            runner.printComparison(out, c, tf);
            return 0;
        } catch (Exception e) {
            System.err.println("jenny: --bench failed: " + e.getMessage());
            return 4;
        }
    }

    private List<String> buildPassThroughArgs() {
        List<String> args = new ArrayList<>();
        args.add("-n" + tupleSize);
        args.add("-s" + seed);
        for (String w : withoutStrings) {
            args.add("-w" + w);
        }
        for (Integer size : dimensionSizes) {
            args.add(String.valueOf(size));
        }
        return args;
    }

    /**
     * Runs {@code jobs} independent replicas concurrently on a thread pool capped at the
     * available processor count, and returns the best one. Each replica builds its own
     * greedy-initialized {@link JennySolution} from a distinct, well-spread seed (golden-ratio
     * step, mirroring the Go port's {@code bestOfK}) and solves it in isolation — no mutable
     * state is shared between replicas, so the only cost of a replica is CPU and memory.
     *
     * <p>Keep-best is deterministic: among replicas that reach {@code hard == 0}, the one with
     * the fewest active rows wins; among infeasible replicas, the lexicographically best
     * (hard, soft) score wins. Ties in either case go to the lowest replica index, because
     * replicas are folded into {@code best} strictly in submission order and only replace it on
     * a strict improvement.
     *
     * <p>Returns {@code null} (after reporting the failure to stderr) if a replica's solve
     * threw or was interrupted.
     */
    private JennySolution bestOfReplicas(
            List<Dimension> dimensions,
            List<AllowedTuple> tuples,
            List<Without> withouts,
            List<Map<Dimension, Feature>> oldTests,
            long seed,
            int jobs) {
        int poolSize = Math.max(1, Math.min(jobs, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<JennySolution>> futures = new ArrayList<>(jobs);
            for (int i = 0; i < jobs; i++) {
                // Distinct, well-spread seed per replica (golden-ratio step), same constant
                // jennygo's par.go uses.
                long replicaSeed = seed + (long) i * 0x9E3779B1L;
                futures.add(executor.submit(
                        () -> solveReplica(dimensions, tuples, withouts, oldTests, replicaSeed)));
            }

            JennySolution best = null;
            for (Future<JennySolution> future : futures) {
                JennySolution candidate;
                try {
                    candidate = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("jenny: interrupted while waiting for a -j replica");
                    return null;
                } catch (ExecutionException e) {
                    System.err.println("jenny: -j replica failed: " + e.getCause());
                    return null;
                }
                if (isBetter(candidate, best)) {
                    best = candidate;
                }
            }
            return best;
        } finally {
            executor.shutdown();
        }
    }

    /** Builds one greedy-initialized solution from {@code replicaSeed} and solves it. */
    private JennySolution solveReplica(
            List<Dimension> dimensions,
            List<AllowedTuple> tuples,
            List<Without> withouts,
            List<Map<Dimension, Feature>> oldTests,
            long replicaSeed) {
        // Build greedy initial solution to cover most tuples
        Random rnd = new Random(replicaSeed);
        List<Map<Dimension, Feature>> greedyTests = GreedyInitializer.buildInitialTests(
                dimensions, tuples, withouts, rnd);

        int slotCount = estimateSlotCount(dimensions, tupleSize);
        // Ensure enough slots for old tests + greedy tests + extra room for optimization
        slotCount = Math.max(slotCount, oldTests.size() + greedyTests.size() + 20);

        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;

        // First, create test cases from old tests (pinned)
        for (int i = 0; i < oldTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            tc.setPinned(true); // Pin old tests so solver doesn't modify them

            Map<Dimension, Feature> oldTest = oldTests.get(i);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                Feature assignedFeature = oldTest.get(d);
                cell.setFeature(assignedFeature);
                cell.setPinned(true); // Pin the cell too
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }

        // Then add greedy initial tests (UNPINNED so the solver can
        // deactivate or merge them — see Phase 6 design doc).
        int greedyStart = oldTests.size();
        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(greedyStart + i);
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

        // Finally create empty slots for solver to fill if needed
        int emptyStart = oldTests.size() + greedyTests.size();
        for (int i = emptyStart; i < slotCount; i++) {
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

        SolverConfig config = JennySolverFactory.createConfig()
                .withRandomSeed(replicaSeed)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(timeLimitSeconds)));

        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        return solver.solve(problem);
    }

    /**
     * True iff {@code candidate} should replace {@code currentBest} (or {@code currentBest} is
     * absent). Feasible (hard == 0) beats infeasible; among feasible replicas fewest active
     * rows wins; among infeasible replicas the lexicographically best (hard, soft) score wins.
     * Uses strict inequality throughout so callers folding replicas in index order keep the
     * lowest index on ties.
     */
    private static boolean isBetter(JennySolution candidate, JennySolution currentBest) {
        if (currentBest == null) {
            return true;
        }
        boolean candidateFeasible = candidate.getScore().hardScore() == 0;
        boolean bestFeasible = currentBest.getScore().hardScore() == 0;
        if (candidateFeasible != bestFeasible) {
            return candidateFeasible;
        }
        if (candidateFeasible) {
            return activeRowCount(candidate) < activeRowCount(currentBest);
        }
        int hardCompare = Long.compare(
                candidate.getScore().hardScore(), currentBest.getScore().hardScore());
        if (hardCompare != 0) {
            return hardCompare > 0;
        }
        return Long.compare(
                candidate.getScore().softScore(), currentBest.getScore().softScore()) > 0;
    }

    private static long activeRowCount(JennySolution solution) {
        return solution.getTestCases().stream().filter(TestCase::isActiveFlag).count();
    }

    private static int estimateSlotCount(List<Dimension> dimensions, int tupleSize) {
        int[] sizes = dimensions.stream().mapToInt(Dimension::size).toArray();
        java.util.Arrays.sort(sizes);
        long product = 1L;
        for (int i = sizes.length - tupleSize; i < sizes.length; i++) {
            product = Math.multiplyExact(product, sizes[i]);
        }
        long overcap = Math.min(Math.multiplyExact(product, 2L), 65534L);
        return Math.max(4, (int) overcap);
    }
}
