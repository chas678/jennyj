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
     * extracted from both sides, and Timefold offers no "contains-all/superset"
     * indexed joiner (only {@code containing}/{@code containedIn}/
     * {@code containingAnyOf}).
     *
     * <p>The indexed decomposition — {@code forEach(TestCase).filter(active)
     * .join(TestCell).join(AllowedTuple, containedIn(cell.feature,
     * tuple.features)).groupBy((tuple, testCase), count()).filter(count ==
     * tuple.size())}, anti-joined against {@code forEach(AllowedTuple)} — was
     * fully implemented and validated behaviour-equivalent (FULL_ASSERT clean),
     * then BENCHMARKED on 2026-06-17 and found to be ~6.8x SLOWER on the
     * self-test (~594 vs ~4076 moves/sec): the {@code groupBy} over the
     * (testCase, cell, tuple) tri-stream materialises far more incremental
     * state than the per-pair {@code coversTuple()} scan it replaced
     * (7214 tuples x 152 test cases x 12 cells). Rejected, not merely deferred —
     * {@link Joiners#filtering} is the faster shape for this problem.
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
