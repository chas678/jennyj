package com.pobox.chas66;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

public class PairwiseConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                mustCoverAllTuples(factory),
                minimizeActiveRows(factory),
        };
    }

    Constraint mustCoverAllTuples(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                // Join against FeatureAssignment to ensure Timefold "watches" the value variable
                .ifNotExists(FeatureAssignment.class,
                        Joiners.filtering((combo, fa) ->
                                fa.getTestRun().getActive() && isRunCoveringCombo(combo, fa.getTestRun())))
                .penalize(HardMediumSoftScore.ofHard(10000))
                .asConstraint("Hard: Uncovered Tuple");
    }

    private Constraint minimizeActiveRows(ConstraintFactory factory) {
        return factory.forEach(TestRun.class)
                .filter(TestRun::getActive)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("Medium: Active Row Cost");
    }

    private boolean isRunCoveringCombo(Combination combo, TestRun run) {
        // Safe O(1) lookup using the assignmentMap built in TestRun [cite: 140, 147]
        for (var entry : combo.getAssignments().entrySet()) {
            var assignment = run.getAssignmentForDimension(entry.getKey());
            if (assignment == null || !assignment.getValue().equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}