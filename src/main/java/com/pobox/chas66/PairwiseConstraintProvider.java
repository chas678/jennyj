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
                forbiddenCombinations(factory), // Enforcement of -w
                minimizeActiveRows(factory),
                maximizeTupleDensity(factory)
        };
    }

    // HARD: All required tuples must be present in at least one active row
    private Constraint mustCoverAllTuples(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                .ifNotExists(TestRun.class,
                        Joiners.filtering((combo, run) -> run.getActive() && isRunCoveringCombo(combo, run)))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Uncovered Tuple");
    }

    private Constraint forbiddenCombinations(ConstraintFactory factory) {
        return factory.forEach(TestRun.class)
                .filter(TestRun::getActive)
                // Join with the problem facts
                .join(ForbiddenCombination.class)
                .filter((run, forbidden) -> forbidden.isViolatedBy(run))
                .penalize(HardMediumSoftScore.ofHard(100))
                .asConstraint("Hard: Forbidden Combination (-w)");
    }

    private Constraint minimizeActiveRows(ConstraintFactory factory) {
        return factory.forEach(TestRun.class)
                .filter(TestRun::getActive)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("Active Row Cost");
    }

    private Constraint maximizeTupleDensity(ConstraintFactory factory) {
        return factory.forEach(Combination.class)
                .join(TestRun.class,
                        Joiners.filtering((combo, run) -> run.getActive() && isRunCoveringCombo(combo, run)))
                .reward(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("Tuple Density Reward");
    }

    private boolean isRunCoveringCombo(Combination combo, TestRun run) {
        for (var entry : combo.getAssignments().entrySet()) {
            Character val = run.getAssignmentMap().get(entry.getKey()).getValue();
            if (val == null || !val.equals(entry.getValue())) return false;
        }
        return true;
    }

    private boolean isRunCoveringForbidden(ForbiddenCombination forbidden, TestRun run) {
        for (var entry : forbidden.getRestrictions().entrySet()) {
            Character val = run.getAssignmentMap().get(entry.getKey()).getValue();
            if (val == null || !entry.getValue().contains(val)) return false;
        }
        return true;
    }
}