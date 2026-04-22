package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
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
                        Joiners.filtering(
                                (tuple, tc) -> tc.isActiveFlag() && tc.coversTuple(tuple)))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("coverAllTuples");
    }

    /** No active test case may match any forbidden combination. */
    Constraint respectWithouts(ConstraintFactory factory) {
        return factory.forEach(TestCase.class)
                .filter(TestCase::isActiveFlag)
                .join(Without.class,
                        Joiners.filtering(
                                (tc, without) -> without.matches(tc.featuresByDim())))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("respectWithouts");
    }

    /** Minimize the number of active test cases. */
    Constraint minimizeActiveTests(ConstraintFactory factory) {
        return factory.forEach(TestCase.class)
                .filter(TestCase::isActiveFlag)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("minimizeActiveTests");
    }
}
