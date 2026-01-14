package com.pobox.chas66;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class PairwiseConstraintProviderTest {

    ConstraintVerifier<PairwiseConstraintProvider, PairwiseSolution> constraintVerifier =
            ConstraintVerifier.build(new PairwiseConstraintProvider(), PairwiseSolution.class, TestRun.class, FeatureAssignment.class);

    @Test
    void mustCoverAllTuples_ChangingValuePenalizes() {
        // 1. Setup dimensions
        Dimension d1 = new Dimension(0, 2); // Features 'a', 'b'
        Dimension d2 = new Dimension(1, 2); // Features 'a', 'b'

        // 2. Define a required combination: Dimension 0='a' and Dimension 1='b'
        Combination requiredCombo = new Combination(Map.of(0, 'a', 1, 'b'));

        // 3. Create a TestRun that matches the combination
        TestRun run = new TestRun();
        run.setId(0);
        run.setActive(true);

        FeatureAssignment fa1 = new FeatureAssignment(run, "0-0", d1);
        fa1.setValue('a'); // Matches combo
        FeatureAssignment fa2 = new FeatureAssignment(run, "0-1", d2);
        fa2.setValue('b'); // Matches combo

        run.setAssignments(List.of(fa1, fa2));

        // 4. Verify that the current state has 0 Hard penalty
        constraintVerifier.verifyThat(PairwiseConstraintProvider::mustCoverAllTuples)
                .given(requiredCombo, run, fa1, fa2)
                .penalizesBy(0);

        // 5. Change fa1 from 'a' to 'b'. Now the combo (0a, 1b) is uncovered.
        fa1.setValue('b');

        // 6. Assert that it now penalizes (proving the solver "watches" FeatureAssignment changes)
        constraintVerifier.verifyThat(PairwiseConstraintProvider::mustCoverAllTuples)
                .given(requiredCombo, run, fa1, fa2)
                .penalizesBy(1); // Expect 1 penalty (10,000 hard points)
    }
}