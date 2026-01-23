package com.pobox.chas66;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Command(name = "jenny-tf",
        version = "1.0",
        mixinStandardHelpOptions = true,
        separator = "", // Allows -n2 instead of -n 2
        description = "Timefold-optimized pairwise test suite generator")
public class JennyTF implements Callable<Integer> {

    // Pattern for parsing -w constraint strings like "1a2b" or "6ab7bc"
    private static final Pattern WITHOUT_PATTERN = Pattern.compile("(\\d+)([a-zA-Z]+)");

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

    /**
     * Creates an initial solution ready for benchmarking.
     * Public method for use by benchmark framework.
     */
    public PairwiseSolution createInitialSolution(int n, List<Integer> sizes, List<String> w, Long s) {
        // 1. Setup Dimensions & Combinations (Lazy Generation)
        List<Dimension> dimensions = IntStream.range(0, sizes.size())
                .mapToObj(i -> new Dimension(i, sizes.get(i))).toList();

        // Create lazy combination iterator for memory efficiency
        CombinationIterator combinationIterator = new CombinationIterator(dimensions, n);
        log.info("Created CombinationIterator for lazy tuple generation. Estimated: {} combinations",
                combinationIterator.estimateTotalCount());

        // For GreedyInitializer, we need a Set (will generate combinations once and cache them)
        Set<Combination> required = combinationIterator.toSet(true);
        log.info("Generated {} combinations for greedy initialization", required.size());

        applyWithouts(required, w);
        List<ForbiddenCombination> forbiddenList = parseWithouts(w);

        // 2. Use GreedyInitializer to generate a feasible starting solution
        GreedyInitializer initializer = (s != null) ? new GreedyInitializer(s) : new GreedyInitializer();
        List<Dimension> sortedDims = dimensions.stream()
                .sorted(Comparator.comparingInt(Dimension::getSize).reversed())
                .toList();
        PairwiseSolution start = initializer.initialize(sortedDims, required, forbiddenList);

        start.setForbiddenCombinations(forbiddenList);

        // 3. Add Buffer Rows (with smart sizing based on coverage density)
        int bufferCount = calculateOptimalBufferCount(start, sortedDims, required.size());
        addBufferRows(start, dimensions, bufferCount);

        return start;
    }

    /**
     * Calculates optimal buffer row count based on initial solution density.
     *
     * High density (>80% avg coverage) = more buffer needed for consolidation
     * Low density (<50% avg coverage) = less buffer needed
     * Medium density = use geometric heuristic
     */
    private int calculateOptimalBufferCount(PairwiseSolution initialSolution,
                                           List<Dimension> sortedDims,
                                           int totalCombinations) {
        // Count active rows in initial solution
        long initialActiveRows = initialSolution.getTestRuns().stream()
            .filter(TestRun::getActive)
            .count();

        if (initialActiveRows == 0) {
            // Fallback: use geometric heuristic
            return Math.max(15, Math.min(25, sortedDims.get(0).getSize() * sortedDims.get(1).getSize() / 3));
        }

        // Calculate average density (combinations per row)
        double avgDensity = (double) totalCombinations / initialActiveRows;

        // Theoretical max density = n-way strength (e.g., 2 for pairwise)
        // Higher actual density means more consolidation opportunity
        int baseDims = sortedDims.get(0).getSize() * sortedDims.get(1).getSize();

        if (avgDensity > sortedDims.size() * 0.8) {
            // High density: solver needs more buffer to consolidate effectively
            int buffer = Math.min(30, baseDims / 2);
            log.debug("High density ({:.2f}), using {} buffer rows", avgDensity, buffer);
            return buffer;
        } else if (avgDensity < sortedDims.size() * 0.5) {
            // Low density: less buffer needed, solution already sparse
            int buffer = Math.min(15, baseDims / 4);
            log.debug("Low density ({:.2f}), using {} buffer rows", avgDensity, buffer);
            return buffer;
        } else {
            // Medium density: use standard heuristic
            int buffer = Math.max(15, Math.min(25, baseDims / 3));
            log.debug("Medium density ({:.2f}), using {} buffer rows", avgDensity, buffer);
            return buffer;
        }
    }

    public PairwiseSolution solve(int n, List<Integer> sizes, List<String> w, Long s) {
        PairwiseSolution start = createInitialSolution(n, sizes, w, s);

        // 4. Configure & Build Solver (with problem-size-aware tuning)
        int problemSize = start.getRequiredCombinations().size();
        SolverConfig cfg = PairwiseSolverFactory.createConfig(problemSize);
        if (s != null) cfg.setRandomSeed(s);
        cfg.setEnvironmentMode(EnvironmentMode.NO_ASSERT);

        SolverFactory<PairwiseSolution> solverFactory = SolverFactory.create(cfg);
        Solver<PairwiseSolution> solver = solverFactory.buildSolver();
// Add the Step Monitor Listener
        solver.addEventListener(event -> {
            PairwiseSolution newBest = event.getNewBestSolution();
            var score = newBest.getScore();
            if (score != null) {
                int activeRows = (int) Math.abs(score.mediumScore());
                long hardScore = score.hardScore();
                long softScore = score.softScore();

                log.info("Progress: {} rows | Hard: {} | Soft: {}",
                        activeRows, hardScore, softScore);
            }
        });
        // 5. Solve (Now starting from a feasible state)
        log.info("Total Combinations in Problem Fact: {}", start.getRequiredCombinations().size());
        PairwiseSolution bestSolution = solver.solve(start);

        log.debug("--- Debug: Checking Uncovered Tuples ---");
        int uncoveredCount = 0;

        for (Combination combo : bestSolution.getRequiredCombinations()) {
            boolean covered = false;
            for (TestRun run : bestSolution.getTestRuns()) {
                if (CoverageUtil.isRunCoveringCombo(combo, run)) {
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
                log.error("MISSING TUPLE: {}" , missing);
            }
        }
        return bestSolution;
    }

    private Set<Combination> generateTuples(List<Dimension> dims, int n) {
        Set<Combination> result = new HashSet<>();

        for (Set<Dimension> dimSubset : Sets.combinations(ImmutableSet.copyOf(dims), n)) {

            List<Dimension> sortedDims = dimSubset.stream()
                    .sorted(Comparator.comparingInt(Dimension::getId))
                    .toList();

            List<Set<Character>> featureSets = sortedDims.stream()
                    .map(this::getFeaturesForDim)
                    .toList();

            for (List<Character> prod : Sets.cartesianProduct(featureSets)) {
                Map<Integer, Character> map = new HashMap<>();

                for (int i = 0; i < sortedDims.size(); i++) {
                    map.put(sortedDims.get(i).getId(), prod.get(i));
                }
                result.add(new Combination(map));
            }
        }
        return result;
    }

    private Set<Character> getFeaturesForDim(Dimension d) {
        return CharacterEncoding.getRangeAsSet(d.getSize());
    }

    /**
     * Parses a single -w constraint string (e.g., "1a2b" or "6ab7bc") into a map
     * of dimension ID -> forbidden characters.
     *
     * @param withoutStr The constraint string
     * @return Map of dimension ID (0-indexed) to set of forbidden characters
     */
    private Map<Integer, Set<Character>> parseWithoutPattern(String withoutStr) {
        Map<Integer, Set<Character>> restrictions = new HashMap<>();
        Matcher matcher = WITHOUT_PATTERN.matcher(withoutStr);

        while (matcher.find()) {
            int dimId = Integer.parseInt(matcher.group(1)) - 1; // Convert to 0-indexed
            Set<Character> chars = matcher.group(2).chars()
                    .mapToObj(c -> (char) c)
                    .collect(Collectors.toSet());
            restrictions.put(dimId, chars);
        }
        return restrictions;
    }

    private void applyWithouts(Set<Combination> required, List<String> withouts) {
        if (withouts == null) return;
        for (String withoutStr : withouts) {
            Map<Integer, Set<Character>> restrictions = parseWithoutPattern(withoutStr);
            required.removeIf(tuple -> restrictions.entrySet().stream().allMatch(entry -> {
                Character val = tuple.getAssignments().get(entry.getKey());
                return val != null && entry.getValue().contains(val);
            }));
        }
    }

    private void addBufferRows(PairwiseSolution solution, List<Dimension> dimensions, int bufferSize) {
        // Determine the starting ID for new buffer rows
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
            }).toList();

            extraRun.setAssignments(assignments); // Triggers the internal map rebuild
            solution.getTestRuns().add(extraRun);
        }
    }

    private List<ForbiddenCombination> parseWithouts(List<String> w) {
        return w.stream()
                .map(this::parseWithoutPattern)
                .filter(m -> !m.isEmpty())
                .map(ForbiddenCombination::new)
                .toList();
    }

    private void printOutput(PairwiseSolution solution) {
        List<TestRun> activeRuns = solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .toList();

        // Use a LinkedHashSet to maintain insertion order and automatically handle uniqueness
        // A canonical string representation is used for uniqueness checking
        Set<String> uniqueOutputRows = new LinkedHashSet<>();
        for (TestRun run : activeRuns) {
            String row = run.getAssignments().stream()
                    .sorted(Comparator.comparingInt(a -> a.getDimension().getId()))
                    .map(a -> (a.getDimension().getId() + 1) + a.getValue().toString())
                    .collect(Collectors.joining(" ", " ", " "));
            uniqueOutputRows.add(row);
        }

        log.info("Final Suite Size: {} rows", uniqueOutputRows.size());

        uniqueOutputRows.forEach(System.out::println);
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