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
 * The single source of truth for jenny's solver pipeline — used both by the CLI
 * ({@link com.burtleburtle.jenny.cli.JennyCli}) and by every integration/unit test.
 * (There is no longer a parallel {@code solverConfig.xml}; it was removed to
 * eliminate config drift.) The phase list is:
 *
 * <pre>
 *   LS consolidate    (Tabu Search + full move union, incl. row-eviction)
 *   -&gt; CustomPhase:  {@link ShrinkPhaseCommand} (removeRedundant + localSearchReduce)
 *   -&gt; LS polish      (Hill Climbing, coverage-preserving)
 *   -&gt; LS repair      (Tabu Search, bestScoreFeasible — trades a residual Without
 *                        violation for an uncovered tuple, then re-covers)
 *   -&gt; CustomPhase:  {@link ShrinkPhaseCommand} + {@link RecoverPhaseCommand}
 * </pre>
 *
 * <p>The three local-search phases' budgets are derived from the requested
 * {@code --time-limit-seconds}, so a larger limit gives the search more time; the
 * custom shrink/recover phases are deterministic and run to completion.
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
        long consolidateSpent = Math.max(1L, Math.round(budget * 0.45));
        long consolidateUnimproved = Math.max(1L, Math.round(budget * 0.25));
        long polishSpent = Math.max(1L, Math.round(budget * 0.25));
        long polishUnimproved = Math.max(1L, Math.round(budget * 0.15));
        long repairSpent = Math.max(1L, Math.round(budget * 0.20));
        long repairUnimproved = Math.max(1L, Math.round(budget * 0.12));

        List<PhaseConfig> phases = new java.util.ArrayList<>();
        phases.add(buildConsolidate(consolidateSpent, consolidateUnimproved));
        if (withCustomPhases) {
            phases.add(shrinkPhase());
        }
        phases.add(buildPolish(polishSpent, polishUnimproved));
        // Feasibility-repair LS runs in both the full pipeline and the LS-only
        // control (withCustomPhases=false); only the shrink/recover custom phases
        // are gated.
        phases.add(buildFeasibilityRepair(repairSpent, repairUnimproved));
        if (withCustomPhases) {
            phases.add(shrinkAndRecoverPhase());
        }

        return new SolverConfig()
                .withSolutionClass(JennySolution.class)
                .withEntityClasses(TestCase.class, TestCell.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        // SP2: hand-rolled incremental calculator replaces the
                        // unindexed constraint streams. The declarative
                        // JennyConstraintProvider is validated as the assertion
                        // score director under FULL_ASSERT in
                        // IncrementalScoreParityIT (a non-null
                        // assertionScoreDirectorFactory is only legal under an
                        // assert environmentMode, so it is not wired here).
                        .withIncrementalScoreCalculatorClass(JennyIncrementalScoreCalculator.class))
                // SP4: deterministic shrink/recover custom phases interleaved
                // between the local-search phases (see createConfig javadoc).
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

    /**
     * Feasibility-repair Tabu Search (formerly solverConfig.xml "Phase 3"). The
     * 2-hard {@code respectWithouts} vs 1-hard {@code coverAllTuples} asymmetry lets
     * a step trade a residual Without violation (-2) for an uncovered tuple (-1) — a
     * strict improvement the tabu acceptor keeps — and then re-cover. Terminates as
     * soon as the best solution is feasible.
     */
    private static LocalSearchPhaseConfig buildFeasibilityRepair(long spent, long unimproved) {
        UnionMoveSelectorConfig union = new UnionMoveSelectorConfig()
                .withMoveSelectorList(List.of(cellChangeMove(null), caseChangeMove(null)));
        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withSecondsSpentLimit(spent)
                        .withUnimprovedSecondsSpentLimit(unimproved)
                        .withBestScoreFeasible(true))
                .withMoveSelectorConfig(union)
                .withAcceptorConfig(new LocalSearchAcceptorConfig()
                        .withEntityTabuSize(5))
                .withForagerConfig(new LocalSearchForagerConfig()
                        .withAcceptedCountLimit(5));
    }

    /** Deterministic shrink (removeRedundant + localSearchReduce). */
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
                        mergeTests(3.0),
                        evictRow(3.0)));
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

    private static MoveIteratorFactoryConfig evictRow(Double weight) {
        MoveIteratorFactoryConfig cfg = new MoveIteratorFactoryConfig()
                .withMoveIteratorFactoryClass(EvictRowMoveIteratorFactory.class);
        if (weight != null) {
            cfg.setFixedProbabilityWeight(weight);
        }
        return cfg;
    }

}
