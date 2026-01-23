package com.pobox.chas66;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

@DisplayName("Jenny-TF Integration Tests")
class JennyTFTest {

    /**
     * Fast test helper using reduced timeout (5s instead of 30s).
     */
    private PairwiseSolution runFastTestSolver(int n, List<Integer> dims, List<String> withouts) {
        JennyTF service = new JennyTF();
        PairwiseSolution initialSolution = service.createInitialSolution(n, dims, withouts, null);

        SolverFactory<PairwiseSolution> solverFactory = SolverFactory.create(TestConfig.createFastTestConfig());
        Solver<PairwiseSolution> solver = solverFactory.buildSolver();

        return solver.solve(initialSolution);
    }

    /**
     * Smoke test helper using very short timeout (2s).
     */
    private PairwiseSolution runSmokeTestSolver(int n, List<Integer> dims, List<String> withouts) {
        JennyTF service = new JennyTF();
        PairwiseSolution initialSolution = service.createInitialSolution(n, dims, withouts, null);

        SolverFactory<PairwiseSolution> solverFactory = SolverFactory.create(TestConfig.createSmokeFastTestConfig());
        Solver<PairwiseSolution> solver = solverFactory.buildSolver();

        return solver.solve(initialSolution);
    }

    @Nested
    @DisplayName("Fast Constraint Validation Tests")
    @Tag("fast")
    class FastConstraintTests {

        @Test
        @DisplayName("Should respect single forbidden combination")
        void testSimpleForbiddenCombination() {
            // Smallest problem: 2x2x2 with one constraint
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(2, 2, 2), List.of("1a2b"));

            solution.getTestRuns().stream()
                    .filter(TestRun::getActive)
                    .forEach(run -> {
                        char d1 = run.getAssignmentForDimension(0).getValue();
                        char d2 = run.getAssignmentForDimension(1).getValue();

                        assertThat("Row " + run.getId() + " violates forbidden 1a2b",
                                !(d1 == 'a' && d2 == 'b'), is(true));
                    });

            assertThat("Hard score must be 0",
                    solution.getScore().hardScore(), is(equalTo(0)));
        }

        @ParameterizedTest
        @DisplayName("Should respect various forbidden patterns")
        @CsvSource({
            "1a2b, 0, a, 1, b",
            "1b2a, 0, b, 1, a",
            "2c3a, 1, c, 2, a"
        })
        void testVariousForbiddenPatterns(String pattern, int dim1, char val1, int dim2, char val2) {
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(3, 3, 3), List.of(pattern));

            solution.getTestRuns().stream()
                    .filter(TestRun::getActive)
                    .forEach(run -> {
                        char d1 = run.getAssignmentForDimension(dim1).getValue();
                        char d2 = run.getAssignmentForDimension(dim2).getValue();

                        assertThat("Row violates forbidden " + pattern,
                                !(d1 == val1 && d2 == val2), is(true));
                    });
        }

        @Test
        @DisplayName("Should handle multiple overlapping constraints")
        void testMultipleConstraints() {
            List<String> withouts = List.of("1a2b", "1b2a", "2c3a");
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(3, 3, 3), withouts);

            solution.getTestRuns().stream()
                    .filter(TestRun::getActive)
                    .forEach(run -> {
                        char d1 = run.getAssignmentForDimension(0).getValue();
                        char d2 = run.getAssignmentForDimension(1).getValue();
                        char d3 = run.getAssignmentForDimension(2).getValue();

                        assertThat("Constraint 1a2b violated", !(d1 == 'a' && d2 == 'b'), is(true));
                        assertThat("Constraint 1b2a violated", !(d1 == 'b' && d2 == 'a'), is(true));
                        assertThat("Constraint 2c3a violated", !(d2 == 'c' && d3 == 'a'), is(true));
                    });
        }

        @Test
        @DisplayName("Should handle complex multi-feature constraint")
        void testComplexConstraint() {
            // Pattern: 1ab2cd means dim1 ∈ {a,b} AND dim2 ∈ {c,d} is forbidden
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(4, 4, 2), List.of("1ab2cd"));

            solution.getTestRuns().stream()
                    .filter(TestRun::getActive)
                    .forEach(run -> {
                        char d1 = run.getAssignmentForDimension(0).getValue();
                        char d2 = run.getAssignmentForDimension(1).getValue();

                        boolean d1InSet = (d1 == 'a' || d1 == 'b');
                        boolean d2InSet = (d2 == 'c' || d2 == 'd');

                        assertThat("Complex constraint violated", !(d1InSet && d2InSet), is(true));
                    });
        }
    }

    @Nested
    @DisplayName("Score Calculator Correctness Tests")
    @Tag("fast")
    class ScoreCalculatorTests {

        @Test
        @DisplayName("Incremental calculator should match Easy calculator")
        void testScoreCalculatorEquivalence() {
            // Small problem for fast execution
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(2, 2, 2), List.of("1a2b"));

            // Recalculate with Easy calculator
            PairwiseEasyScoreCalculator easyCalc = new PairwiseEasyScoreCalculator();
            HardMediumSoftScore easyScore = easyCalc.calculateScore(solution);

            assertThat("Incremental score doesn't match Easy score",
                    solution.getScore(), is(equalTo(easyScore)));
        }

        @ParameterizedTest
        @DisplayName("Should match across various problem sizes")
        @CsvSource({
            "2, 2, 2",
            "2, 3, 2",
            "3, 2, 2",
            "3, 3, 2"
        })
        void testScoreEquivalenceAcrossSizes(int d1, int d2, int d3) {
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(d1, d2, d3), List.of());

            PairwiseEasyScoreCalculator easyCalc = new PairwiseEasyScoreCalculator();
            HardMediumSoftScore easyScore = easyCalc.calculateScore(solution);

            assertThat("Score mismatch for problem " + d1 + "x" + d2 + "x" + d3,
                    solution.getScore(), is(equalTo(easyScore)));
        }

        @Test
        @DisplayName("Hard score must be zero (complete coverage)")
        void testCompleteCoverage() {
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(2, 2, 2), List.of());

            assertThat("Hard score must be 0 for complete coverage",
                    solution.getScore().hardScore(), is(equalTo(0)));

            assertThat("Should have active rows",
                    solution.getTestRuns().stream().filter(TestRun::getActive).count(),
                    is(greaterThan(0L)));
        }
    }

    @Nested
    @DisplayName("N-way Testing Support")
    @Tag("fast")
    class NWayTests {

        @ParameterizedTest
        @DisplayName("Should handle various N-way strengths")
        @ValueSource(ints = {2, 3, 4})
        void testNWayStrengths(int n) {
            // Small problem sizes for fast execution
            PairwiseSolution solution = runSmokeTestSolver(n, List.of(2, 2, 2, 2), List.of());

            assertThat(n + "-way testing failed",
                    solution.getScore().hardScore(), is(equalTo(0)));

            long activeCount = solution.getTestRuns().stream()
                    .filter(TestRun::getActive)
                    .count();
            assertThat(n + "-way testing produced no rows",
                    activeCount, is(greaterThan(0L)));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    @Tag("fast")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle large dimension size")
        void testLargeDimensionSize() {
            // 20 features instead of 40 for faster test
            PairwiseSolution solution = runSmokeTestSolver(2, List.of(20, 2), List.of());

            assertThat("Large dimension size failed",
                    solution.getScore().hardScore(), is(equalTo(0)));
        }

        @ParameterizedTest
        @DisplayName("Should handle various dimension counts")
        @ValueSource(ints = {2, 3, 4, 5})
        void testVariousDimensionCounts(int dimensionCount) {
            List<Integer> dimensions = IntStream.range(0, dimensionCount)
                    .mapToObj(i -> 2)
                    .toList();

            PairwiseSolution solution = runSmokeTestSolver(2, dimensions, List.of());

            assertThat("Failed with " + dimensionCount + " dimensions",
                    solution.getScore().hardScore(), is(equalTo(0)));
        }
    }

    @Nested
    @DisplayName("Optimization Quality Tests")
    @Tag("slow")
    class OptimizationTests {

        @Test
        @DisplayName("Should optimize better than naive greedy")
        void testOptimizationEfficiency() {
            // Moderate problem for reasonable test time with fast config
            List<Integer> dims = List.of(2, 2, 2, 2, 2, 2);
            PairwiseSolution solution = runFastTestSolver(2, dims, List.of());

            // With optimization, should achieve good compression
            assertThat("Optimization failed to reduce suite size",
                    (int) Math.abs(solution.getScore().mediumScore()),
                    is(lessThanOrEqualTo(8)));
        }

        @Test
        @DisplayName("Should find optimal solutions on small problems")
        void testOptimalSolutions() {
            // Known optimal: 2x2x2 = 4 rows
            PairwiseSolution solution = runFastTestSolver(2, List.of(2, 2, 2), List.of());

            long activeRows = solution.getTestRuns().stream()
                    .filter(TestRun::getActive)
                    .count();

            assertThat("Did not find optimal solution for 2x2x2",
                    activeRows, is(equalTo(4L)));
        }
    }

    @Nested
    @DisplayName("Random Problem Generator Tests")
    @Tag("slow")
    class RandomTests {

        @Test
        @DisplayName("Should handle randomly generated problems")
        void testRandomProblems() {
            Random random = new Random(42);

            for (int i = 0; i < 3; i++) {  // Reduced from 5 to 3
                List<Integer> dims = IntStream.range(0, 3)
                        .mapToObj(j -> 2 + random.nextInt(2))  // 2-3 features
                        .toList();

                PairwiseSolution solution = runSmokeTestSolver(2, dims, List.of());

                assertThat("Random problem " + i + " failed coverage",
                        solution.getScore().hardScore(), is(equalTo(0)));

                // Verify score calculators match
                PairwiseEasyScoreCalculator easyCalc = new PairwiseEasyScoreCalculator();
                HardMediumSoftScore easyScore = easyCalc.calculateScore(solution);

                assertThat("Score mismatch on random problem " + i,
                        solution.getScore(), is(equalTo(easyScore)));
            }
        }
    }
}
