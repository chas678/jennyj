package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.AcceptorType;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.localsearch.decider.forager.LocalSearchForagerConfig;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;

import java.util.List;

/**
 * Programmatic, problem-size-aware {@link SolverConfig} builder. Mirrors the
 * static {@code solverConfig.xml} two-phase setup but scales tabu and
 * forager parameters from the problem size so tiny problems don't pay
 * big-problem exploration costs and vice versa.
 *
 * <p>Use this from {@link com.burtleburtle.jenny.cli.JennyCli} (which knows
 * the tuple count up front). Unit tests of move factories continue to load
 * the XML config directly.
 */
public final class JennySolverFactory {

    private JennySolverFactory() {
    }

    /**
     * Build a config tuned for a problem with the given number of allowed
     * tuples to cover.
     */
    public static SolverConfig createConfig(int problemSize) {
        return new SolverConfig()
                .withSolutionClass(JennySolution.class)
                .withEntityClasses(TestCase.class, TestCell.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withConstraintProviderClass(JennyConstraintProvider.class))
                .withPhases(buildPhase1(problemSize), buildPhase2());
    }

    /** Phase 1: Tabu Search with the full move union (build + shrink). */
    private static LocalSearchPhaseConfig buildPhase1(int problemSize) {
        // Smaller problems converge with shorter tabu memory; larger ones
        // need wider exploration to escape local optima. Bounds keep us in
        // the empirically-validated 5..15 range.
        int tabuSize = clamp(problemSize / 200, 5, 15);
        // Forager candidate breadth scales with problem size — small
        // problems can be greedy (low limit), big problems benefit from
        // sampling more candidates per step.
        int acceptedCountLimit = clamp(problemSize / 50, 5, 20);

        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withSecondsSpentLimit(60L)
                        .withUnimprovedSecondsSpentLimit(30L))
                .withMoveSelectorConfig(buildPhase1MoveUnion())
                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                        .withEntityTabuSize(tabuSize))
                .withForagerConfig(new LocalSearchForagerConfig()
                        .withAcceptedCountLimit(acceptedCountLimit));
    }

    /** Phase 2: Hill Climbing polish on single-variable moves. */
    private static LocalSearchPhaseConfig buildPhase2() {
        UnionMoveSelectorConfig union = new UnionMoveSelectorConfig()
                .withMoveSelectorList(List.of(
                        cellChangeMove(null),
                        caseChangeMove(null),
                        randomizeRow(null)));
        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withSecondsSpentLimit(60L)
                        .withUnimprovedSecondsSpentLimit(30L))
                .withMoveSelectorConfig(union)
                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                        .withAcceptorTypeList(List.of(AcceptorType.HILL_CLIMBING)))
                .withForagerConfig(new LocalSearchForagerConfig()
                        .withAcceptedCountLimit(1));
    }

    private static UnionMoveSelectorConfig buildPhase1MoveUnion() {
        // Baseline T28 weights — kept after S8 A/B confirmed the 50/20/30
        // alternative regressed the benchmark.
        return new UnionMoveSelectorConfig()
                .withMoveSelectorList(List.of(
                        cellChangeMove(2.5),
                        caseChangeMove(1.5),
                        randomizeRow(1.5),
                        deactivateRedundant(3.0),
                        mergeTests(3.0)));
    }

    private static ChangeMoveSelectorConfig cellChangeMove(Double weight) {
        ChangeMoveSelectorConfig cfg = new ChangeMoveSelectorConfig()
                .withEntitySelectorConfig(new EntitySelectorConfig()
                        .withEntityClass(TestCell.class));
        if (weight != null) {
            cfg.setFixedProbabilityWeight(weight);
        }
        return cfg;
    }

    private static ChangeMoveSelectorConfig caseChangeMove(Double weight) {
        ChangeMoveSelectorConfig cfg = new ChangeMoveSelectorConfig()
                .withEntitySelectorConfig(new EntitySelectorConfig()
                        .withEntityClass(TestCase.class));
        if (weight != null) {
            cfg.setFixedProbabilityWeight(weight);
        }
        return cfg;
    }

    private static MoveIteratorFactoryConfig randomizeRow(Double weight) {
        MoveIteratorFactoryConfig cfg = new MoveIteratorFactoryConfig()
                .withMoveIteratorFactoryClass(RandomizeRowMoveIteratorFactory.class);
        if (weight != null) {
            cfg.setFixedProbabilityWeight(weight);
        }
        return cfg;
    }

    private static MoveIteratorFactoryConfig deactivateRedundant(Double weight) {
        MoveIteratorFactoryConfig cfg = new MoveIteratorFactoryConfig()
                .withMoveIteratorFactoryClass(DeactivateRedundantMoveIteratorFactory.class);
        if (weight != null) {
            cfg.setFixedProbabilityWeight(weight);
        }
        return cfg;
    }

    private static MoveIteratorFactoryConfig mergeTests(Double weight) {
        MoveIteratorFactoryConfig cfg = new MoveIteratorFactoryConfig()
                .withMoveIteratorFactoryClass(MergeTestsMoveIteratorFactory.class);
        if (weight != null) {
            cfg.setFixedProbabilityWeight(weight);
        }
        return cfg;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
