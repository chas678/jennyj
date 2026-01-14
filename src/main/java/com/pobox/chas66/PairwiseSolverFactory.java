package com.pobox.chas66;

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
                        .withUnimprovedSecondsSpentLimit(30L)
                        .withSecondsSpentLimit(120L))
                .withPhases(
                        // PHASE 1: Local Search (The Squeeze)
                        // We start directly here because the solution is already feasible.
                        new LocalSearchPhaseConfig()
                                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                                        .withEntityTabuSize(7)
                                        .withLateAcceptanceSize(500))
                                .withMoveSelectorConfig(new UnionMoveSelectorConfig()
                                        .withMoveSelectorList(List.of(
                                                // Move 1: Mutate characters to maintain coverage
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(FeatureAssignment.class))
                                                        .withValueSelectorConfig(new ValueSelectorConfig("value")),

                                                // Move 2: Toggle "active" to reduce suite size
                                                new ChangeMoveSelectorConfig()
                                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                                                .withEntityClass(TestRun.class))
                                                        .withValueSelectorConfig(new ValueSelectorConfig("active"))
                                        ))));
    }
}