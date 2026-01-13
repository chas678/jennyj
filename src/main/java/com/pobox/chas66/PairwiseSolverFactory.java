package com.pobox.chas66;

import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.value.ValueSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

import java.util.List;

public class PairwiseSolverFactory {
    public static SolverConfig createConfig() {
        return new SolverConfig()
                .withSolutionClass(PairwiseSolution.class)
                .withEntityClasses(TestRun.class, FeatureAssignment.class)
                .withConstraintProviderClass(PairwiseConstraintProvider.class)
                .withTerminationConfig(new TerminationConfig()
                        .withUnimprovedSecondsSpentLimit(10L) // Stop early if no better suite found
                        .withSecondsSpentLimit(60L))
                .withPhases(
                        // PHASE 1: Stronger CH
                        new ConstructionHeuristicPhaseConfig(),

                        // PHASE 2: Local Search
                        new LocalSearchPhaseConfig()
                                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                                        .withEntityTabuSize(7) // Prevent flipping the same row too often
                                        .withLateAcceptanceSize(500)
                                )
                                .withMoveSelectorConfig(new UnionMoveSelectorConfig()
                                        .withMoveSelectorList(List.of(
                                                // Change a feature value
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(FeatureAssignment.class)),
                                                // Toggle a row ON/OFF
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(TestRun.class))
                                                        .withValueSelectorConfig(new ValueSelectorConfig("active"))
                                        ))
                                )
                );
    }
}
