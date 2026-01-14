package com.pobox.chas66;

import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.pillar.PillarSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.PillarSwapMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

import java.util.List;

public class PairwiseSolverFactory {
    public static SolverConfig createConfig() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(PairwiseSolution.class)
                .withEntityClasses(TestRun.class, FeatureAssignment.class)
                .withConstraintProviderClass(PairwiseConstraintProvider.class)
                .withTerminationConfig(new TerminationConfig()
                        .withUnimprovedSecondsSpentLimit(10L)
                        .withSecondsSpentLimit(60L))
                .withPhases(
                        new LocalSearchPhaseConfig()
                                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                                        .withEntityTabuSize(7)
                                        .withLateAcceptanceSize(1000))
                                .withMoveSelectorConfig(new UnionMoveSelectorConfig()
                                        .withMoveSelectorList(List.of(
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(FeatureAssignment.class)),
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(TestRun.class))
                                        ))
                                )
                );
        return solverConfig;
    }
}