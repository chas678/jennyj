package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.Without;

public class JennyConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                coverAllTuples(factory),
                respectWithouts(factory),
                minimizeActiveTests(factory),
        };
    }

    /** Every allowed tuple must be covered by at least one active test case. */
    Constraint coverAllTuples(ConstraintFactory factory) {
        return factory.forEach(AllowedTuple.class)
                .ifNotExists(TestCase.class,
                        ai.timefold.solver.core.api.score.stream.Joiners.filtering(
                                (tuple, tc) -> tc.isActive() && tc.coversTuple(tuple)))
                .penalizeLong(HardSoftLongScore.ONE_HARD, (tuple) -> 1L)
                .asConstraint("coverAllTuples");
    }

    /** No active test case may match any forbidden combination. */
    Constraint respectWithouts(ConstraintFactory factory) {
        return factory.forEach(TestCase.class)
                .filter(TestCase::isActive)
                .join(Without.class)
                .filter((tc, without) -> without.matches(tc.getFeaturesByDim()))
                .penalizeLong(HardSoftLongScore.ONE_HARD, (tc, without) -> 1L)
                .asConstraint("respectWithouts");
    }

    /** Minimize the number of active test cases. */
    Constraint minimizeActiveTests(ConstraintFactory factory) {
        return factory.forEach(TestCase.class)
                .filter(TestCase::isActive)
                .penalizeLong(HardSoftLongScore.ONE_SOFT, (tc) -> 1L)
                .asConstraint("minimizeActiveTests");
    }
}
