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
    public static SolverConfig createConfig() {
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
                                                // 1. Standard Value Change - fast, explores individual assignments
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(FeatureAssignment.class)),
                                                // 2. Active Toggle - medium speed, enables row consolidation
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(TestRun.class)),
                                                // 3. Swap moves - slow but essential for finding optimal solutions
                                                new SwapMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(FeatureAssignment.class))
                                        ))
                                )
                );
    }
}