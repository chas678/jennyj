package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
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
     * <p>Indexed rewrite (replaces the former {@link Joiners#filtering}
     * cartesian scan that dominated runtime — {@code CoverageUtil.covers}
     * was profiled at ~172M calls on the self-test). Coverage is a
     * multi-dimensional subset relation, decomposed here into indexed
     * primitives:
     *
     * <ol>
     *   <li>Start from {@code forEach(TestCase).filter(isActiveFlag)} so the
     *       incremental score engine tracks the {@code active} planning
     *       variable, then join in each owning {@link TestCell} (tracking the
     *       {@code feature} planning variable). Rooting the stream at the
     *       entities whose variables drive coverage is what keeps incremental
     *       scoring consistent with score-from-scratch — reading a foreign
     *       entity's planning variable inside a {@code filter} would not be
     *       tracked and corrupts the score under assertion.</li>
     *   <li>The indexed {@link Joiners#containedIn} joiner matches each cell's
     *       assigned feature to every {@link AllowedTuple} whose feature list
     *       contains that exact {@code (dimension, featureIndex)} feature.
     *       Because a feature uniquely identifies its dimension and a tuple
     *       holds at most one feature per dimension, each tuple feature is
     *       matched by at most one cell of a given test case — so the count
     *       below is exact.</li>
     *   <li>Group by {@code (tuple, testCase)} and count matched features.
     *       A test case fully covers a tuple iff the count equals the tuple's
     *       arity ({@code tuple.features().size()}).</li>
     *   <li>Reduce the fully-covering pairs to the distinct set of covered
     *       tuples.</li>
     *   <li>Penalize every {@link AllowedTuple} that is <em>not</em> in that
     *       covered set via {@link UniConstraintStream#ifNotExists} against
     *       the derived stream, joined on tuple identity ({@code equal}).</li>
     * </ol>
     *
     * <p>Behaviour-equivalent to the old constraint: exactly one ONE_HARD
     * penalty per uncovered allowed tuple, zero when covered by any active
     * test case, and inactive test cases never contribute (filtered out in
     * step 1).
     */
    Constraint coverAllTuples(ConstraintFactory factory) {
        UniConstraintStream<AllowedTuple> coveredTuples = factory.forEach(TestCase.class)
                .filter(TestCase::isActiveFlag)
                .join(TestCell.class,
                        Joiners.equal(tc -> tc, TestCell::getTestCase))
                .filter((testCase, cell) -> cell.getFeature() != null)
                .join(AllowedTuple.class,
                        Joiners.containedIn((testCase, cell) -> cell.getFeature(),
                                AllowedTuple::features))
                .groupBy((testCase, cell, tuple) -> tuple,
                        (testCase, cell, tuple) -> testCase,
                        ConstraintCollectors.countTri())
                .filter((tuple, testCase, matchCount) -> matchCount == tuple.features().size())
                .groupBy((tuple, testCase, matchCount) -> tuple);

        return factory.forEach(AllowedTuple.class)
                .ifNotExists(coveredTuples, Joiners.equal())
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
