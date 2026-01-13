package com.pobox.chas66;

import ai.timefold.solver.core.api.score.ScoreExplanation;
import ai.timefold.solver.core.api.score.ScoreManager;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
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

        // 2. Calculate a smart initial pool size
        // For pairwise, the lower bound is roughly the product of the two largest dimensions.
        // We add a buffer so the solver has "room" to move things around.
        List<Integer> sortedSizes = sizes.stream().sorted(Comparator.reverseOrder()).toList();
        int minPossibleRows = (sortedSizes.size() >= 2) ? sortedSizes.get(0) * sortedSizes.get(1) : sortedSizes.get(0);
        int poolSize = Math.max(minPossibleRows + 10, 20); // Minimum of 20, or product + 10

        // 3. Prepare the Solution
        PairwiseSolution start = new PairwiseSolution();
        start.setDimensions(dimensions);
        start.setForbiddenCombinations(forbiddenList);
        start.setRequiredCombinations(new ArrayList<>(required));

        List<TestRun> runs = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            TestRun run = new TestRun();
            run.setId(i);
            run.setActive(true);

            List<FeatureAssignment> assignments = dimensions.stream().map(d ->
                    new FeatureAssignment(run, run.getId() + "-" + d.getId(), d)
            ).collect(Collectors.toList());

            run.setAssignments(assignments); // This triggers rebuilding the internal Map
            runs.add(run);
        }
        start.setTestRuns(runs);

        // 4. Configure & Build Solver (Standardized)
        SolverConfig cfg = PairwiseSolverFactory.createConfig();
        if (s != null) cfg.setRandomSeed(s);

        // Use NO_ASSERT for performance, or FULL_ASSERT for debugging
        cfg.setEnvironmentMode(EnvironmentMode.NO_ASSERT);

        SolverFactory<PairwiseSolution> solverFactory = SolverFactory.create(cfg);
        Solver<PairwiseSolution> solver = solverFactory.buildSolver();

        // 5. Execution
        PairwiseSolution bestSolution = solver.solve(start);

        // 6. Diagnostics (Printed to stderr so it doesn't mess up pipe output)
        SolutionManager<PairwiseSolution, HardMediumSoftScore> solutionManager = SolutionManager.create(solverFactory);
        ScoreExplanation<PairwiseSolution, HardMediumSoftScore> explanation = solutionManager.explain(bestSolution);

        if (bestSolution.getScore().hardScore() < 0) {
            System.err.println("WARNING: Could not cover all tuples. Hard Score: " + bestSolution.getScore().hardScore());
        }

        return bestSolution;
    }

    private List<ForbiddenCombination> parseWithouts(List<String> w) {
        List<ForbiddenCombination> forbiddenList = new ArrayList<>();
        Pattern pattern = Pattern.compile("(\\d+)([a-zA-Z]+)");
        for (String withoutStr : w) {
            Map<Integer, Set<Character>> restrictions = new HashMap<>();
            Matcher matcher = pattern.matcher(withoutStr);
            while (matcher.find()) {
                int dimId = Integer.parseInt(matcher.group(1)) - 1;
                Set<Character> chars = matcher.group(2).chars()
                        .mapToObj(c -> (char) c).collect(Collectors.toSet());
                restrictions.put(dimId, chars);
            }
            forbiddenList.add(new ForbiddenCombination(restrictions));
        }
        return forbiddenList;
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
        int nextId = solution.getTestRuns().stream().mapToInt(TestRun::getId).max().orElse(0) + 1;
        for (int i = 0; i < bufferSize; i++) {
            TestRun extraRun = new TestRun();
            extraRun.setId(nextId++);
            extraRun.setActive(false);

            // Construct assignments using the (TestRun, StringId, Dimension) constructor
            List<FeatureAssignment> assignments = dimensions.stream().map(d -> {
                FeatureAssignment fa = new FeatureAssignment(extraRun, extraRun.getId() + "-" + d.getId(), d);
                fa.setValue('a'); // Default value for inactive rows
                return fa;
            }).collect(Collectors.toList());

            extraRun.setAssignments(assignments);
            solution.getTestRuns().add(extraRun);
        }
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
        for (var entry : a.getAssignmentMap().entrySet()) {
            char valA = entry.getValue().getValue();
            char valB = b.getAssignmentForDimension(entry.getKey()).getValue();
            if (valA != valB) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new JennyTF());
        cmd.setOptionsCaseInsensitive(true);
        System.exit(cmd.execute(args));
    }
}