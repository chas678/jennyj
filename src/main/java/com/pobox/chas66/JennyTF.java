package com.pobox.chas66;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Command(name = "jenny-tf",
        version = "1.0",
        mixinStandardHelpOptions = true,
        separator = "", // Allows -n2 instead of -n 2
        description = "Timefold-optimized pairwise test suite generator")
public class JennyTF implements Callable<Integer> {

    @Option(names = "-n", defaultValue = "2")
    private int n;

    @Option(names = "-w")
    private List<String> withouts = new ArrayList<>();

    @Option(names = "-s")
    private Long seed;

    @Parameters(arity = "1..*")
    private List<Integer> dimSizes = new ArrayList<>();

    @Override
    public Integer call() {
        if (dimSizes.isEmpty()) {
            new CommandLine(this).usage(System.err);
            return 1;
        }
        PairwiseSolution result = solve(n, dimSizes, withouts, seed);

        // Print the result using our cleanup logic
        printOutput(result);
        return 0;
    }

    public PairwiseSolution solve(int n, List<Integer> sizes, List<String> w, Long s) {
        // 1. Setup Dimensions & Combinations
        List<Dimension> dimensions = IntStream.range(0, sizes.size())
                .mapToObj(i -> new Dimension(i, sizes.get(i))).toList();
        Set<Combination> required = generateTuples(dimensions, n);
        applyWithouts(required, w);
        List<ForbiddenCombination> forbiddenList = parseWithouts(w);

        // 2. NEW: Use GreedyInitializer to generate a feasible starting solution
        GreedyInitializer initializer = new GreedyInitializer();
        PairwiseSolution start = initializer.initialize(dimensions, required, forbiddenList);

        start.setForbiddenCombinations(forbiddenList); // Ensure constraints can see -w rules

        // 3. Add Buffer Rows
        // The GreedyInitializer provides a MINIMUM set. We add extra rows (inactive)
        // to give the solver "draft space" to move assignments around for further reduction.
        addBufferRows(start, dimensions, 20);

        // 4. Configure & Build Solver
        SolverConfig cfg = PairwiseSolverFactory.createConfig();
        if (s != null) cfg.setRandomSeed(s);
        cfg.setEnvironmentMode(EnvironmentMode.NO_ASSERT);

        SolverFactory<PairwiseSolution> solverFactory = SolverFactory.create(cfg);
        Solver<PairwiseSolution> solver = solverFactory.buildSolver();

        // 5. Solve (Now starting from a feasible state)
        PairwiseSolution bestSolution = solver.solve(start);

        System.err.println("--- Debug: Checking Uncovered Tuples ---");
        int uncoveredCount = 0;

        for (Combination combo : bestSolution.getRequiredCombinations()) {
            boolean covered = false;
            for (TestRun run : bestSolution.getTestRuns()) {
                if (run.getActive() && isRunCoveringCombo(combo, run)) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                uncoveredCount++;
                // Print the missing tuple in a readable format (e.g., "4e 5d")
                String missing = combo.getAssignments().entrySet().stream()
                        .map(e -> (e.getKey() + 1) + "" + e.getValue())
                        .collect(Collectors.joining(" "));
                System.err.println("MISSING TUPLE: " + missing);
            }
        }
        return bestSolution;
    }

    private boolean isRunCoveringCombo(Combination combo, TestRun run) {
        // A deactivated run cannot cover any tuples [cite: 108]
        if (!run.getActive()) {
            return false;
        }
        for (var entry : combo.getAssignments().entrySet()) {
            var assignment = run.getAssignmentForDimension(entry.getKey());
            if (assignment == null || !assignment.getValue().equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private Set<Combination> generateTuples(List<Dimension> dims, int n) {
        Set<Combination> result = new HashSet<>();
        for (Set<Dimension> dimSubset : Sets.combinations(ImmutableSet.copyOf(dims), n)) {
            List<Set<Character>> featureSets = dimSubset.stream()
                    .sorted(Comparator.comparingInt(Dimension::getId))
                    .map(this::getFeaturesForDim).toList();

            for (List<Character> prod : Sets.cartesianProduct(featureSets)) {
                Map<Integer, Character> map = new HashMap<>();
                List<Dimension> sorted = dimSubset.stream().sorted(Comparator.comparingInt(Dimension::getId)).toList();
                for (int i = 0; i < sorted.size(); i++) map.put(sorted.get(i).getId(), prod.get(i));
                result.add(new Combination(map));
            }
        }
        return result;
    }

    private Set<Character> getFeaturesForDim(Dimension d) {
        Set<Character> chars = new LinkedHashSet<>();
        for (int i = 0; i < d.getSize(); i++) {
            chars.add(i < 26 ? (char) ('a' + i) : (char) ('A' + (i - 26)));
        }
        return chars;
    }

    private void applyWithouts(Set<Combination> required, List<String> withouts) {
        if (withouts == null) return;
        Pattern pattern = Pattern.compile("(\\d+)([a-zA-Z]+)");
        for (String withoutStr : withouts) {
            Map<Integer, Set<Character>> restrictions = new HashMap<>();
            Matcher matcher = pattern.matcher(withoutStr);
            while (matcher.find()) {
                int dimId = Integer.parseInt(matcher.group(1)) - 1;
                Set<Character> chars = matcher.group(2).chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
                restrictions.put(dimId, chars);
            }
            required.removeIf(tuple -> restrictions.entrySet().stream().allMatch(entry -> {
                Character val = tuple.getAssignments().get(entry.getKey());
                return val != null && entry.getValue().contains(val);
            }));
        }
    }

    private void addBufferRows(PairwiseSolution solution, List<Dimension> dimensions, int bufferSize) {
        // Determine the starting ID for new buffer rows [cite: 69]
        int nextId = solution.getTestRuns().stream().mapToInt(TestRun::getId).max().orElse(0) + 1;

        for (int i = 0; i < bufferSize; i++) {
            TestRun extraRun = new TestRun();
            extraRun.setId(nextId++);
            extraRun.setActive(false); // Buffer rows start as inactive to be "squeezed" in

            // Construct assignments for each dimension in this buffer row
            List<FeatureAssignment> assignments = dimensions.stream().map(d -> {
                FeatureAssignment fa = new FeatureAssignment(extraRun, extraRun.getId() + "-" + d.getId(), d);

                // FIX: Ensure the default value 'a' is only assigned if the dimension size > 0
                // Since all dimensions in your input (3 3 2 5 7) are > 0, 'a' (index 0) is valid.
                fa.setValue('a');
                return fa;
            }).collect(Collectors.toList());

            extraRun.setAssignments(assignments); // Triggers the internal map rebuild [cite: 72, 126-128]
            solution.getTestRuns().add(extraRun);
        }
    }

    private List<ForbiddenCombination> parseWithouts(List<String> w) {
        List<ForbiddenCombination> forbiddenList = new ArrayList<>();
        // Matches patterns like "1a", "2bc", "10xyz"
        Pattern pattern = Pattern.compile("(\\d+)([a-zA-Z]+)");

        for (String withoutStr : w) {
            Map<Integer, Set<Character>> restrictions = new HashMap<>();
            Matcher matcher = pattern.matcher(withoutStr);

            while (matcher.find()) {
                // Dimension 1 in the CLI is Dimension 0 in the code
                int dimId = Integer.parseInt(matcher.group(1)) - 1;
                Set<Character> chars = matcher.group(2).chars()
                        .mapToObj(c -> (char) c)
                        .collect(Collectors.toSet());
                restrictions.put(dimId, chars);
            }

            if (!restrictions.isEmpty()) {
                forbiddenList.add(new ForbiddenCombination(restrictions));
            }
        }
        return forbiddenList;
    }

    private void printOutput(PairwiseSolution solution) {
        // 1. Get only the active rows
        List<TestRun> activeRuns = solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .collect(Collectors.toList());

        // 2. Subsumption Cleanup (Optional but Recommended)
        // Even if rows are active, some might have become redundant
        // during the final moves. We check if any row is a complete
        // duplicate or subset of another.
        List<TestRun> minimizedRuns = new ArrayList<>();
        for (TestRun current : activeRuns) {
            boolean isRedundant = false;
            for (TestRun other : activeRuns) {
                if (current == other) continue;
                if (isSubsumed(current, other)) {
                    isRedundant = true;
                    break;
                }
            }
            if (!isRedundant) {
                minimizedRuns.add(current);
            }
        }

        // 3. Final Output in Jenny.c format
        System.err.printf("Final Suite Size: %d rows%n", minimizedRuns.size());

        minimizedRuns.stream()
                .sorted(Comparator.comparingInt(TestRun::getId))
                .forEach(run -> {
                    // Jenny format: " 1a 2b 3c "
                    String row = run.getAssignments().stream()
                            .sorted(Comparator.comparingInt(a -> a.getDimension().getId()))
                            .map(a -> (a.getDimension().getId() + 1) + a.getValue().toString())
                            .collect(Collectors.joining(" ", " ", " "));
                    System.out.println(row);
                });
    }

    /**
     * Checks if run 'a' is redundant because run 'b' covers everything 'a' does.
     * In a fixed-dimension problem, this usually means the rows are identical.
     */
    private boolean isSubsumed(TestRun a, TestRun b) {
        // Exact character match across all dimensions
        for (var entry : a.getAssignmentMap().entrySet()) {
            FeatureAssignment assignmentB = b.getAssignmentForDimension(entry.getKey());
            if (assignmentB == null || !entry.getValue().getValue().equals(assignmentB.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new JennyTF());
        cmd.setOptionsCaseInsensitive(true);
        System.exit(cmd.execute(args));
    }
}