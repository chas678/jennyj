package com.pobox.chas66;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

@DisplayName("Jenny-TF Constraint Tests with Hamcrest")
class JennyTFTest {

    @Test
    @DisplayName("Should ensure no active row contains forbidden pair 1a2b")
    void testSimpleWithoutConstraint() {
        List<Integer> dims = List.of(3, 3, 3);
        List<String> withouts = List.of("1a2b");

        PairwiseSolution solution = runTestSolver(2, dims, withouts);
        List<TestRun> activeRuns = solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .toList();

        assertThat("The solver should return at least one valid row",
                activeRuns, is(not(empty())));

        for (TestRun run : activeRuns) {
            char dim1 = run.getAssignmentForDimension(0).getValue();
            char dim2 = run.getAssignmentForDimension(1).getValue();

            // Hamcrest logic: Assert that it is NOT the case that (d1='a' AND d2='b')
            assertThat("Row " + run.getId() + " contains forbidden combination 1a2b",
                    dim1 == 'a' && dim2 == 'b', is(false));
        }
    }

    @Test
    @DisplayName("Should verify Hard Score is zero (All valid tuples covered)")
    void testHardScoreIntegrity() {
        List<Integer> dims = List.of(2, 2, 2);
        List<String> withouts = List.of("1a2a3b");

        PairwiseSolution solution = runTestSolver(2, dims, withouts);

        assertThat("The hard score must be 0, indicating all required tuples are covered",
                solution.getScore().hardScore(), is(equalTo(0)));
    }

    @Test
    @DisplayName("Should result in fewer rows than a naive greedy approach")
    void testOptimizationEfficiency() {
        // 10 dimensions of size 2 (minimum is ~7-10 rows)
        List<Integer> dims = List.of(2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        PairwiseSolution solution = runTestSolver(2, dims, List.of());

        // A naive greedy might produce 15-20; Timefold should hit ~10
        assertThat("Timefold failed to optimize the suite size",
                (int) Math.abs(solution.getScore().mediumScore()), is(lessThanOrEqualTo(12)));
    }

    private PairwiseSolution runTestSolver(int n, List<Integer> dims, List<String> withouts) {
        JennyTF service = new JennyTF();
        // We pass null for the seed to allow for standard exploration
        return service.solve(n, dims, withouts, null);
    }

    @Test
    @DisplayName("Should strictly respect the -w1a2b constraint")
    void testConstraintEnforcement() {
        // Act
        PairwiseSolution solution = runTestSolver(2, List.of(3, 3, 2), List.of("1a2b"));

        // Assert using Hamcrest
        solution.getTestRuns().stream()
                .filter(TestRun::getActive)
                .forEach(run -> {
                    char d1 = run.getAssignmentForDimension(0).getValue();
                    char d2 = run.getAssignmentForDimension(1).getValue();

                    // "Assert that the pair (1a, 2b) is NOT present"
                    assertThat("Constraint violation found in row " + run.getId(),
                            d1 == 'a' && d2 == 'b', is(not(true)));
                });

        assertThat(solution.getScore().hardScore(), is(equalTo(0)));
    }
}