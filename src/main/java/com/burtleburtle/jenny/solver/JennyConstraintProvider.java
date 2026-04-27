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

    /**
     * Every allowed tuple must be covered by at least one active test case.
     *
     * <p>Uses {@link Joiners#filtering} rather than an indexed
     * {@link Joiners#equal} join because tuple coverage is a multi-dimensional
     * subset-match — there is no single (Dimension, Feature) key that can be
     * extracted from both sides. An indexed rewrite would require flattening
     * tuples to {@code (tuple, dim, feature)} entries, joining against
     * {@link com.burtleburtle.jenny.domain.TestCell} on (dim, feature),
     * grouping by {@code (tuple, testCase)} with {@code count()}, and
     * filtering to {@code count == tuple.size()} — a substantial constraint
     * model refactor with risk of regression on the working benchmark.
     * Investigated 2026-04-27 and deferred.
     */
    Constraint coverAllTuples(ConstraintFactory factory) {
        return factory.forEach(AllowedTuple.class)
                .ifNotExists(TestCase.class,
                        Joiners.filtering(
                                (tuple, tc) -> tc.isActiveFlag() && tc.coversTuple(tuple)))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("coverAllTuples");
    }

    /**
     * No active test case may match any forbidden combination.
     *
     * <p>Same {@link Joiners#filtering} caveat as {@link #coverAllTuples} —
     * Without is a per-dimension forbidden-set, indexed-join decomposition
     * is non-trivial for the same multi-dimensional reason.
     */
    Constraint respectWithouts(ConstraintFactory factory) {
        return factory.forEach(TestCase.class)
                .filter(TestCase::isActiveFlag)
                .join(Without.class,
                        Joiners.filtering(
                                (tc, without) -> without.matches(tc.getFeaturesByDim())))
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
