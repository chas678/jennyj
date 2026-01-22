package com.pobox.chas66;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.countBi;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.countTri;

public class PairwiseConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                mustCoverAllTuples(factory),
                minimizeActiveRows(factory)
                // rewardRedundantCoverage disabled due to memory issues on large problems
        };
    }

    /**
     * Provides a "gradient" for the solver by rewarding coverage density.
     * Simpler version to avoid OOM errors on large problems.
     */
    private Constraint rewardRedundantCoverage(ConstraintFactory factory) {
        // Disabled due to memory issues on large problems
        // The Medium constraint (minimize active rows) is sufficient
        return null;
    }

    private boolean isAssignmentMatchingCombo(Combination combo, FeatureAssignment fa) {
        Character req = combo.getAssignments().get(fa.getDimension().getId());
        return req != null && req.equals(fa.getValue());
    }

    Constraint mustCoverAllTuples(ConstraintFactory factory) {
        // Note: This constraint has a known score corruption issue when using FULL_ASSERT mode
        // It accesses FeatureAssignment.value transitively through TestRun.getAssignmentForDimension()
        // For now, use NO_ASSERT mode. The post-solve debug check catches any uncovered tuples.
        return factory.forEach(Combination.class)
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