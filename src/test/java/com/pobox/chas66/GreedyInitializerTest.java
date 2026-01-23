package com.pobox.chas66;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

@DisplayName("GreedyInitializer Tests")
@Tag("fast")
class GreedyInitializerTest {

    /**
     * Helper method to generate n-way combinations similar to JennyTF.generateTuples
     */
    private Set<Combination> generateCombinations(List<Dimension> dims, int n) {
        Set<Combination> result = new HashSet<>();

        for (Set<Dimension> dimSubset : Sets.combinations(ImmutableSet.copyOf(dims), n)) {
            List<Dimension> sortedDims = dimSubset.stream()
                    .sorted(Comparator.comparingInt(Dimension::id))
                    .toList();

            List<Set<Character>> featureSets = sortedDims.stream()
                    .<Set<Character>>map(d -> new HashSet<>(CharacterEncoding.getRangeAsSet(d.size())))
                    .toList();

            for (List<Character> prod : Sets.cartesianProduct(featureSets)) {
                Map<Integer, Character> map = new HashMap<>();
                for (int i = 0; i < sortedDims.size(); i++) {
                    map.put(sortedDims.get(i).id(), prod.get(i));
                }
                result.add(new Combination(map));
            }
        }
        return result;
    }

    @Test
    @DisplayName("Should create feasible solution with complete coverage")
    void testGreedyInitializerFeasibility() {
        GreedyInitializer initializer = new GreedyInitializer();
        List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 2)
        );
        Set<Combination> required = generateCombinations(dimensions, 2);

        PairwiseSolution solution = initializer.initialize(
                dimensions, required, List.of()
        );

        // Should have at least one active row
        long activeCount = solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .count();
        assertThat("Greedy initializer created no active rows",
                activeCount, is(greaterThan(0L)));

        // All combinations should be covered (hard score = 0)
        solution.setRequiredCombinations(new ArrayList<>(required));
        solution.setForbiddenCombinations(List.of());
        PairwiseEasyScoreCalculator calculator = new PairwiseEasyScoreCalculator();
        int hardScore = calculator.calculateScore(solution).hardScore();
        assertThat("Initial solution doesn't cover all tuples",
                hardScore, is(equalTo(0)));
    }

    @Test
    @DisplayName("Should respect forbidden combinations during initialization")
    void testGreedyInitializerRespectsConstraints() {
        GreedyInitializer initializer = new GreedyInitializer();
        List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3)
        );
        List<ForbiddenCombination> forbidden = List.of(
                new ForbiddenCombination(Map.of(0, Set.of('a'), 1, Set.of('b')))
        );
        Set<Combination> required = generateCombinations(dimensions, 2);

        PairwiseSolution solution = initializer.initialize(
                dimensions, required, forbidden
        );

        // No active row should violate forbidden combination
        solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .forEach(run -> {
                    char d0 = run.getAssignmentForDimension(0).getValue();
                    char d1 = run.getAssignmentForDimension(1).getValue();
                    assertThat("Greedy initializer violated forbidden 1a2b in row " + run.getId(),
                            d0 == 'a' && d1 == 'b', is(false));
                });
    }

    @Test
    @DisplayName("Should handle multiple forbidden combinations")
    void testMultipleForbiddenCombinations() {
        GreedyInitializer initializer = new GreedyInitializer();
        List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 3)
        );
        List<ForbiddenCombination> forbidden = List.of(
                new ForbiddenCombination(Map.of(0, Set.of('a'), 1, Set.of('b'))),
                new ForbiddenCombination(Map.of(1, Set.of('c'), 2, Set.of('a')))
        );
        Set<Combination> required = generateCombinations(dimensions, 2);

        PairwiseSolution solution = initializer.initialize(
                dimensions, required, forbidden
        );

        // Verify no violations
        solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .forEach(run -> {
                    char d0 = run.getAssignmentForDimension(0).getValue();
                    char d1 = run.getAssignmentForDimension(1).getValue();
                    char d2 = run.getAssignmentForDimension(2).getValue();

                    assertThat("Violated forbidden 1a2b in row " + run.getId(),
                            !(d0 == 'a' && d1 == 'b'), is(true));
                    assertThat("Violated forbidden 2c3a in row " + run.getId(),
                            !(d1 == 'c' && d2 == 'a'), is(true));
                });
    }

    @Test
    @DisplayName("Should produce deterministic results with same seed")
    void testDeterministicWithSeed() {
        List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 2)
        );
        Set<Combination> required = generateCombinations(dimensions, 2);

        // Run twice with same seed
        GreedyInitializer initializer1 = new GreedyInitializer(42L);
        PairwiseSolution solution1 = initializer1.initialize(
                dimensions, required, List.of()
        );

        GreedyInitializer initializer2 = new GreedyInitializer(42L);
        PairwiseSolution solution2 = initializer2.initialize(
                dimensions, required, List.of()
        );

        // Should have same number of active rows
        long activeCount1 = solution1.getTestRuns().stream()
                .filter(TestRun::getActive).count();
        long activeCount2 = solution2.getTestRuns().stream()
                .filter(TestRun::getActive).count();

        assertThat("Same seed produced different number of rows",
                activeCount1, is(equalTo(activeCount2)));
    }

    @Test
    @DisplayName("Should handle 3-way initialization")
    void test3WayInitialization() {
        GreedyInitializer initializer = new GreedyInitializer();
        List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2)
        );
        Set<Combination> required = generateCombinations(dimensions, 3);

        PairwiseSolution solution = initializer.initialize(
                dimensions, required, List.of()
        );

        // Should have active rows
        long activeCount = solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .count();
        assertThat("3-way initialization created no active rows",
                activeCount, is(greaterThan(0L)));

        // All 3-way combinations should be covered
        solution.setRequiredCombinations(new ArrayList<>(required));
        solution.setForbiddenCombinations(List.of());
        PairwiseEasyScoreCalculator calculator = new PairwiseEasyScoreCalculator();
        int hardScore = calculator.calculateScore(solution).hardScore();
        assertThat("3-way initialization doesn't cover all tuples",
                hardScore, is(equalTo(0)));
    }

    @Test
    @DisplayName("Should initialize all dimension assignments")
    void testAllDimensionsInitialized() {
        GreedyInitializer initializer = new GreedyInitializer();
        List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 2),
                new Dimension(3, 2)
        );
        Set<Combination> required = generateCombinations(dimensions, 2);

        PairwiseSolution solution = initializer.initialize(
                dimensions, required, List.of()
        );

        // Every active row should have all dimensions assigned
        solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .forEach(run -> {
                    assertThat("Row " + run.getId() + " missing dimension assignments",
                            run.getAssignments().size(), is(equalTo(4)));

                    // Verify assignmentMap is populated
                    for (int i = 0; i < 4; i++) {
                        FeatureAssignment fa = run.getAssignmentForDimension(i);
                        assertThat("Row " + run.getId() + " missing assignment for dimension " + i,
                                fa != null, is(true));
                        assertThat("Row " + run.getId() + " has null value for dimension " + i,
                                fa.getValue() != null, is(true));
                    }
                });
    }
}

