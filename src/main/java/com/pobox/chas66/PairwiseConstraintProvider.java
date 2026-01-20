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
                rewardRedundantCoverage(factory)
        };
    }

    /**
     * Provides a "gradient" for the solver.
     * It rewards the solver for covering a combination in multiple rows.
     * This encourages the solver to move assignments until one row
     * is completely redundant and can be deactivated by the Medium constraint.
     */
    private Constraint rewardRedundantCoverage(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                // Join every Combination to every FeatureAssignment to track coverage
                .join(FeatureAssignment.class)
                .filter((combo, fa) -> fa.getTestRun().getActive() && isAssignmentMatchingCombo(combo, fa))
                // Reward for every match found beyond the first one
                .reward(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("Soft: Redundant Coverage Bonus");
    }

    private boolean isAssignmentMatchingCombo(Combination combo, FeatureAssignment fa) {
        Character req = combo.getAssignments().get(fa.getDimension().getId());
        return req != null && req.equals(fa.getValue());
    }

    Constraint mustCoverAllTuples(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                // Join TestRun directly to ensure the 'active' variable is tracked
                .ifNotExists(TestRun.class,
                        Joiners.filtering((combo, run) -> run.getActive() && isRunCoveringCombo(combo, run)))
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