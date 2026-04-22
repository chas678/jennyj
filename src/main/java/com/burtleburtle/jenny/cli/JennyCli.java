package com.burtleburtle.jenny.cli;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "jenny",
        mixinStandardHelpOptions = false,
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

    @Option(names = "-w", description = "Without: forbidden combination. Repeatable.")
    private List<String> withoutStrings = new ArrayList<>();

    @Option(names = "-o", description = "Read existing tests from FILE (or '-' for stdin).")
    private String oldTestsFile;

    @Option(names = "-h", usageHelp = true, description = "Print help.")
    private boolean help;

    @Option(names = "--time-limit-seconds",
            description = "Wall-clock time budget (default 5).")
    private long timeLimitSeconds = 5L;

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
        if (dimensionSizes.isEmpty()) {
            System.err.println("jenny: at least one dimension size is required");
            return 2;
        }
        if (tupleSize < 1 || tupleSize > 32) {
            System.err.println("jenny: -n must be in [1, 32]");
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

        int slotCount = estimateSlotCount(dimensions, tupleSize);
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;
        for (int i = 0; i < slotCount; i++) {
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
                .withRandomSeed(seed)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(timeLimitSeconds))
                        .withBestScoreFeasible(true));

        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);

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

    private static int estimateSlotCount(List<Dimension> dimensions, int tupleSize) {
        int[] sizes = dimensions.stream().mapToInt(Dimension::size).toArray();
        java.util.Arrays.sort(sizes);
        long product = 1L;
        for (int i = sizes.length - tupleSize; i < sizes.length; i++) {
            product = Math.multiplyExact(product, sizes[i]);
        }
        long overcap = Math.min(product * 2L, 65534L);
        return Math.max(4, (int) overcap);
    }
}
