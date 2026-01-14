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
                maximizeTupleDensity(factory)
        };
    }

    // LEVEL 1: HARD - 100% Tuple Coverage
    private Constraint mustCoverAllTuples(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                .ifNotExists(TestRun.class,
                        Joiners.filtering((combo, run) -> run.getActive() && isRunCoveringCombo(combo, run)))
                .penalize(HardMediumSoftScore.ofHard(1))
                .asConstraint("Hard: Uncovered Tuple");
    }

    // LEVEL 2: MEDIUM - Suite Size Reduction
    private Constraint minimizeActiveRows(ConstraintFactory factory) {
        return factory.forEach(TestRun.class)
                .filter(TestRun::getActive)
                .penalize(HardMediumSoftScore.ofMedium(500))
                .asConstraint("Medium: Active Row Cost");
    }

    // LEVEL 3: SOFT - Tuple Density (The Gradient)
    private Constraint maximizeTupleDensity(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                .join(TestRun.class,
                        Joiners.filtering((combo, run) -> run.getActive() && isRunCoveringCombo(combo, run)))
                .reward(HardMediumSoftScore.ofSoft(1))
                .asConstraint("Soft: Tuple Density Reward");
    }

    private boolean isRunCoveringCombo(Combination combo, TestRun run) {
        for (var entry : combo.getAssignments().entrySet()) {
            var assignment = run.getAssignmentForDimension(entry.getKey());
            if (assignment == null || !assignment.getValue().equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}