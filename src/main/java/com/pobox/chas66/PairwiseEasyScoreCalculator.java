package com.pobox.chas66;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.calculator.EasyScoreCalculator;

import java.util.HashSet;
import java.util.Set;

/**
 * EasyScoreCalculator for PairwiseSolution.
 *
 * This calculator recalculates the full score from scratch for each solution state,
 * avoiding the score corruption issues that occur with constraint streams when
 * planning variables are accessed transitively.
 *
 * Scoring:
 * - Hard Score: -100000 per forbidden combination violation (from -w constraints)
 * - Hard Score: -10000 per required combination not covered by any active TestRun
 * - Medium Score: -1 for each active TestRun (minimizes test suite size)
 * - Soft Score: Currently unused (was redundant coverage bonus, disabled due to performance)
 */
public class PairwiseEasyScoreCalculator implements EasyScoreCalculator<PairwiseSolution, HardMediumSoftScore> {

    @Override
    public HardMediumSoftScore calculateScore(PairwiseSolution solution) {
        int hardScore = 0;
        int mediumScore = 0;
        int softScore = 0;

        // Get all active test runs
        Set<TestRun> activeRuns = new HashSet<>();
        for (TestRun run : solution.getTestRuns()) {
            if (run.getActive()) {
                activeRuns.add(run);
            }
        }

        // Medium Score: Penalize each active row
        mediumScore = -activeRuns.size();

        // Hard Score: Penalize forbidden combinations (violations of -w constraints)
        int violationCount = 0;
        if (solution.getForbiddenCombinations() != null) {
            for (TestRun run : activeRuns) {
                for (ForbiddenCombination forbidden : solution.getForbiddenCombinations()) {
                    if (forbidden.isViolatedBy(run)) {
                        violationCount++;
                    }
                }
            }
        }
        hardScore -= violationCount * 100000; // Higher penalty than uncovered tuples

        // Hard Score: Check coverage of all required combinations
        Set<Combination> uncovered = new HashSet<>();
        for (Combination combo : solution.getRequiredCombinations()) {
            boolean covered = false;

            // Check if any active TestRun covers this combination
            for (TestRun run : activeRuns) {
                if (isRunCoveringCombo(combo, run)) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                uncovered.add(combo);
            }
        }

        // Penalize each uncovered combination
        hardScore -= uncovered.size() * 10000;

        return HardMediumSoftScore.of(hardScore, mediumScore, softScore);
    }

    /**
     * Checks if a TestRun covers all dimensions required by a Combination.
     */
    private boolean isRunCoveringCombo(Combination combo, TestRun run) {
        for (var entry : combo.getAssignments().entrySet()) {
            FeatureAssignment assignment = run.getAssignmentForDimension(entry.getKey());
            if (assignment == null || !assignment.getValue().equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
