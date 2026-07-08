package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.phase.PhaseCommand;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.AcceptorType;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.localsearch.decider.forager.LocalSearchForagerConfig;
import ai.timefold.solver.core.config.phase.PhaseConfig;
import ai.timefold.solver.core.config.phase.custom.CustomPhaseConfig;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;

import java.util.List;

/**
 * Programmatic {@link SolverConfig} builder mirroring the static
 * {@code solverConfig.xml} pipeline, now with the deterministic post-processing
 * custom phases interleaved:
 *
 * <pre>
 *   LS consolidate (tabu + full move union)
 *   -&gt; CustomPhase: {@link ShrinkPhaseCommand} (removeRedundant + localSearchReduce)
 *   -&gt; LS polish (hill climbing)
 *   -&gt; CustomPhase: {@link ShrinkPhaseCommand} + {@link RecoverPhaseCommand}
 * </pre>
 *
 * <p>Per-phase local-search budgets are derived from the requested
 * {@code --time-limit-seconds} instead of the old hard-coded 60s/30s caps, so a
 * larger time limit actually gives the local-search phases more time.
 */
public final class JennySolverFactory {

    /** Default budget when the no-arg factory is used (benchmark app / tests). */
    private static final long DEFAULT_TIME_LIMIT_SECONDS = 60L;

    private JennySolverFactory() {
    }

    /** Backward-compatible entry point using the default budget. */
    public static SolverConfig createConfig() {
        return createConfig(DEFAULT_TIME_LIMIT_SECONDS);
    }

    /**
     * Builds the solver config with per-phase budgets scaled from
     * {@code timeLimitSeconds}. Consolidation gets ~60% of the budget, polish
     * ~30%; the custom shrink/recover phases are deterministic and run to
     * completion (no phase time budget). The caller is expected to also set a
     * solver-level spent limit as the global ceiling.
     */
    public static SolverConfig createConfig(long timeLimitSeconds) {
        return createConfig(timeLimitSeconds, true);
    }

    /**
     * Builds the config with per-phase budgets scaled from {@code timeLimitSeconds}.
     * When {@code withCustomPhases} is {@code false} the deterministic
     * shrink/recover custom phases are omitted, yielding the LS-only control
     * pipeline used by the SP4 A/B measurement.
     */
    static SolverConfig createConfig(long timeLimitSeconds, boolean withCustomPhases) {
        long budget = Math.max(1L, timeLimitSeconds);
        long consolidateSpent = Math.max(1L, Math.round(budget * 0.60));
        long consolidateUnimproved = Math.max(1L, Math.round(budget * 0.30));
        long polishSpent = Math.max(1L, Math.round(budget * 0.30));
        long polishUnimproved = Math.max(1L, Math.round(budget * 0.18));

        List<PhaseConfig> phases = new java.util.ArrayList<>();
        phases.add(buildConsolidate(consolidateSpent, consolidateUnimproved));
        if (withCustomPhases) {
            phases.add(shrinkPhase());
        }
        phases.add(buildPolish(polishSpent, polishUnimproved));
        if (withCustomPhases) {
            phases.add(shrinkAndRecoverPhase());
        }

        return new SolverConfig()
                .withSolutionClass(JennySolution.class)
                .withEntityClasses(TestCase.class, TestCell.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withConstraintProviderClass(JennyConstraintProvider.class))
                .withPhaseList(phases);
    }

    /** Phase 1: Tabu Search with the full move union (build + shrink). */
    private static LocalSearchPhaseConfig buildConsolidate(long spent, long unimproved) {
        // entityTabuSize=7 and acceptedCountLimit=10 are the values
        // benchmark-validated by JennyBeatsBenchmarkIT.
        int tabuSize = 7;
        int acceptedCountLimit = 10;

        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withSecondsSpentLimit(spent)
                        .withUnimprovedSecondsSpentLimit(unimproved))
                .withMoveSelectorConfig(buildConsolidateMoveUnion())
                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                        .withEntityTabuSize(tabuSize))
                .withForagerConfig(new LocalSearchForagerConfig()
                        .withAcceptedCountLimit(acceptedCountLimit));
    }

    /** Phase 3: Hill Climbing polish on single-variable moves. */
    private static LocalSearchPhaseConfig buildPolish(long spent, long unimproved) {
        UnionMoveSelectorConfig union = new UnionMoveSelectorConfig()
                .withMoveSelectorList(List.of(
                        cellChangeMove(null),
                        caseChangeMove(null),
                        randomizeRow(null)));
        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withSecondsSpentLimit(spent)
                        .withUnimprovedSecondsSpentLimit(unimproved))
                .withMoveSelectorConfig(union)
                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                        .withAcceptorTypeList(List.of(AcceptorType.HILL_CLIMBING)))
                .withForagerConfig(new LocalSearchForagerConfig()
                        .withAcceptedCountLimit(1));
    }

    /** Phase 2: deterministic shrink (removeRedundant + localSearchReduce). */
    private static CustomPhaseConfig shrinkPhase() {
        return new CustomPhaseConfig()
                .withCustomPhaseCommandClassList(
                        List.<Class<? extends PhaseCommand>>of(ShrinkPhaseCommand.class));
    }

    /** Phase 4: shrink again, then recover any coverable-but-uncovered tuple. */
    private static CustomPhaseConfig shrinkAndRecoverPhase() {
        return new CustomPhaseConfig()
                .withCustomPhaseCommandClassList(List.<Class<? extends PhaseCommand>>of(
                        ShrinkPhaseCommand.class, RecoverPhaseCommand.class));
    }

    private static UnionMoveSelectorConfig buildConsolidateMoveUnion() {
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

}
