package com.pobox.chas66;

import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

import java.util.List;

public class PairwiseSolverFactory {
    /**
     * Creates a solver config with default parameters (backward compatible).
     */
    public static SolverConfig createConfig() {
        // Use medium problem size defaults
        return createConfig(100);
    }

    /**
     * Creates a problem-size-aware solver config with tuned parameters.
     *
     * @param problemSize The number of combinations to cover (tune parameters based on this)
     */
    public static SolverConfig createConfig(int problemSize) {
        // Tune tabu size: smaller for small problems, larger for big problems
        int tabuSize = Math.max(20, Math.min(100, problemSize / 10));

        // Tune late acceptance size: balances exploration vs exploitation
        int lateAcceptanceSize = Math.max(100, Math.min(500, problemSize));

        return new SolverConfig()
                .withSolutionClass(PairwiseSolution.class)
                .withEntityClasses(TestRun.class, FeatureAssignment.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(PairwiseIncrementalScoreCalculator.class))
                .withTerminationConfig(new TerminationConfig()
                        .withUnimprovedSecondsSpentLimit(30L)
                        .withSecondsSpentLimit(90L))
                .withPhases(
                        new LocalSearchPhaseConfig()
                                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                                        .withEntityTabuSize(tabuSize)
                                        .withLateAcceptanceSize(lateAcceptanceSize))
                                .withMoveSelectorConfig(createWeightedMoveSelector())
                );
    }

    /**
     * Creates a weighted move selector that prioritizes effective moves.
     *
     * Distribution:
     * - 50% FeatureAssignment changes (fast, effective for local optimization)
     * - 20% TestRun active toggles (medium speed, enables consolidation)
     * - 30% Swap moves (slow but powerful for finding global optimum)
     */
    private static UnionMoveSelectorConfig createWeightedMoveSelector() {
        return new UnionMoveSelectorConfig()
                .withMoveSelectorList(List.of(
                        // 1. Standard Value Change - fast, explores individual assignments
                        new ChangeMoveSelectorConfig()
                                .withEntitySelectorConfig(new EntitySelectorConfig()
                                        .withEntityClass(FeatureAssignment.class))
                                .withFixedProbabilityWeight(0.5), // 50% of moves
                        // 2. Active Toggle - medium speed, enables row consolidation
                        new ChangeMoveSelectorConfig()
                                .withEntitySelectorConfig(new EntitySelectorConfig()
                                        .withEntityClass(TestRun.class))
                                .withFixedProbabilityWeight(0.2), // 20% of moves
                        // 3. Swap moves - slow but essential for finding optimal solutions
                        new SwapMoveSelectorConfig()
                                .withEntitySelectorConfig(new EntitySelectorConfig()
                                        .withEntityClass(FeatureAssignment.class))
                                .withFixedProbabilityWeight(0.3)  // 30% of moves
                ));
    }

    /**
     * Creates a solver config without swap moves (faster but potentially lower quality).
     * Useful for benchmarking to measure the impact of swap moves.
     */
    public static SolverConfig createConfigWithoutSwaps() {
        return new SolverConfig()
                .withSolutionClass(PairwiseSolution.class)
                .withEntityClasses(TestRun.class, FeatureAssignment.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(PairwiseIncrementalScoreCalculator.class))
                .withTerminationConfig(new TerminationConfig()
                        .withUnimprovedSecondsSpentLimit(30L)
                        .withSecondsSpentLimit(90L))
                .withPhases(
                        new LocalSearchPhaseConfig()
                                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                                        .withEntityTabuSize(40)
                                        .withLateAcceptanceSize(300))
                                .withMoveSelectorConfig(new UnionMoveSelectorConfig()
                                        .withMoveSelectorList(List.of(
                                                // 1. Standard Value Change
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(FeatureAssignment.class)),
                                                // 2. Active Toggle
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(TestRun.class))
                                                // Note: no swap moves
                                        ))
                                )
                );
    }
}