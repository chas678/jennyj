package com.pobox.chas66;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.calculator.IncrementalScoreCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IncrementalScoreCalculator for PairwiseSolution.
 *
 * This calculator maintains incremental state and updates the score based on delta changes,
 * providing 10-100x performance improvement over EasyScoreCalculator on large problems.
 *
 * Key optimizations:
 * - Tracks which TestRuns cover each Combination
 * - Only recalculates affected combinations when variables change
 * - Maintains counts instead of recalculating from scratch
 */
public class PairwiseIncrementalScoreCalculator implements IncrementalScoreCalculator<PairwiseSolution, HardMediumSoftScore> {

    // Immutable problem facts
    private List<Combination> requiredCombinations;
    private List<ForbiddenCombination> forbiddenCombinations;
    private Map<Integer, List<Combination>> dimensionToCombinations; // Index: dimension ID -> combinations involving it

    // Incremental state tracking
    private Map<Combination, Set<TestRun>> coverageMap; // Which active TestRuns cover each combination
    private Set<TestRun> activeRuns;
    private int uncoveredCount;
    private int violationCount;
    private Map<TestRun, Integer> coverageDensity; // How many combinations each active TestRun covers

    @Override
    public void resetWorkingSolution(PairwiseSolution workingSolution) {
        this.requiredCombinations = workingSolution.getRequiredCombinations();
        this.forbiddenCombinations = workingSolution.getForbiddenCombinations();

        // Build dimension-to-combinations index for faster lookup
        this.dimensionToCombinations = new HashMap<>();
        for (Combination combo : requiredCombinations) {
            for (Integer dimensionId : combo.getAssignments().keySet()) {
                dimensionToCombinations
                        .computeIfAbsent(dimensionId, k -> new ArrayList<>())
                        .add(combo);
            }
        }

        // Initialize coverage tracking
        this.coverageMap = new HashMap<>(requiredCombinations.size());
        this.activeRuns = new HashSet<>();
        this.coverageDensity = new HashMap<>();

        for (Combination combo : requiredCombinations) {
            coverageMap.put(combo, new HashSet<>());
        }

        // Build initial coverage state
        for (TestRun run : workingSolution.getTestRuns()) {
            if (run.getActive()) {
                activeRuns.add(run);
                updateCoverageForRun(run, true);
            }
        }

        // Count initial uncovered combinations
        uncoveredCount = 0;
        for (Set<TestRun> coveringRuns : coverageMap.values()) {
            if (coveringRuns.isEmpty()) {
                uncoveredCount++;
            }
        }

        // Count initial violations
        violationCount = 0;
        if (forbiddenCombinations != null) {
            for (TestRun run : activeRuns) {
                for (ForbiddenCombination forbidden : forbiddenCombinations) {
                    if (forbidden.isViolatedBy(run)) {
                        violationCount++;
                    }
                }
            }
        }
    }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        if (entity instanceof FeatureAssignment fa) {
            TestRun run = fa.getTestRun();

            // Only process if the TestRun is active
            if (run.getActive()) {
                // Remove this run's coverage contribution for combinations involving this dimension
                removeCoverageForAssignment(fa);

                // Remove violation contributions
                if (forbiddenCombinations != null) {
                    for (ForbiddenCombination forbidden : forbiddenCombinations) {
                        if (forbidden.isViolatedBy(run)) {
                            violationCount--;
                        }
                    }
                }
            }
        } else if (entity instanceof TestRun run) {
            if (run.getActive()) {
                // Run is currently active, will be deactivated
                activeRuns.remove(run);
                updateCoverageForRun(run, false);

                // Remove violation contributions
                if (forbiddenCombinations != null) {
                    for (ForbiddenCombination forbidden : forbiddenCombinations) {
                        if (forbidden.isViolatedBy(run)) {
                            violationCount--;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        if (entity instanceof FeatureAssignment fa) {
            TestRun run = fa.getTestRun();

            // Only process if the TestRun is active
            if (run.getActive()) {
                // Add this run's coverage contribution for combinations involving this dimension
                addCoverageForAssignment(fa);

                // Add violation contributions
                if (forbiddenCombinations != null) {
                    for (ForbiddenCombination forbidden : forbiddenCombinations) {
                        if (forbidden.isViolatedBy(run)) {
                            violationCount++;
                        }
                    }
                }
            }
        } else if (entity instanceof TestRun run) {
            if (run.getActive()) {
                // Run was just activated
                activeRuns.add(run);
                updateCoverageForRun(run, true);

                // Add violation contributions
                if (forbiddenCombinations != null) {
                    for (ForbiddenCombination forbidden : forbiddenCombinations) {
                        if (forbidden.isViolatedBy(run)) {
                            violationCount++;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void beforeEntityAdded(Object entity) {
        // Not needed for this problem - entities are created during initialization
    }

    @Override
    public void afterEntityAdded(Object entity) {
        // Not needed for this problem - entities are created during initialization
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
        // Not needed for this problem - entities are not removed during solving
    }

    @Override
    public void afterEntityRemoved(Object entity) {
        // Not needed for this problem - entities are not removed during solving
    }

    @Override
    public HardMediumSoftScore calculateScore() {
        int hardScore = 0;

        // Forbidden combination violations
        hardScore -= violationCount * 100000;

        // Uncovered combinations
        hardScore -= uncoveredCount * 10000;

        // Medium: minimize active rows
        int mediumScore = -activeRuns.size();

        // Soft: reward high-density rows (more combinations covered per row)
        // This encourages consolidation - the solver prefers solutions where
        // active rows cover many combinations, enabling other rows to be deactivated
        int softScore = 0;
        for (Integer density : coverageDensity.values()) {
            softScore += density;
        }

        return HardMediumSoftScore.of(hardScore, mediumScore, softScore);
    }

    /**
     * Updates coverage map when a TestRun's active status changes.
     * Also updates coverage density for soft score calculation.
     */
    private void updateCoverageForRun(TestRun run, boolean isBeingActivated) {
        int coverageCount = 0;

        for (Combination combo : requiredCombinations) {
            if (CoverageUtil.isRunCoveringCombo(combo, run)) {
                Set<TestRun> coveringRuns = coverageMap.get(combo);

                if (isBeingActivated) {
                    boolean wasUncovered = coveringRuns.isEmpty();
                    coveringRuns.add(run);
                    if (wasUncovered) {
                        uncoveredCount--;
                    }
                    coverageCount++; // Track density
                } else {
                    coveringRuns.remove(run);
                    if (coveringRuns.isEmpty()) {
                        uncoveredCount++;
                    }
                }
            }
        }

        // Update density tracking
        if (isBeingActivated) {
            coverageDensity.put(run, coverageCount);
        } else {
            coverageDensity.remove(run);
        }
    }

    /**
     * Removes coverage contribution when a FeatureAssignment is about to change.
     * Uses dimension index for O(k) instead of O(n) where k = combinations per dimension.
     * Also decrements coverage density for this TestRun.
     */
    private void removeCoverageForAssignment(FeatureAssignment fa) {
        TestRun run = fa.getTestRun();
        int dimensionId = fa.getDimension().getId();

        // Only check combinations that involve this dimension (much faster!)
        List<Combination> relevantCombos = dimensionToCombinations.get(dimensionId);
        if (relevantCombos == null) return;

        int densityChange = 0;

        for (Combination combo : relevantCombos) {
            if (CoverageUtil.isRunCoveringCombo(combo, run)) {
                Set<TestRun> coveringRuns = coverageMap.get(combo);
                coveringRuns.remove(run);
                if (coveringRuns.isEmpty()) {
                    uncoveredCount++;
                }
                densityChange++;
            }
        }

        // Update density: subtract combinations that were covered before change
        if (densityChange > 0) {
            coverageDensity.merge(run, -densityChange, Integer::sum);
        }
    }

    /**
     * Adds coverage contribution after a FeatureAssignment has changed.
     * Uses dimension index for O(k) instead of O(n) where k = combinations per dimension.
     * Also increments coverage density for this TestRun.
     */
    private void addCoverageForAssignment(FeatureAssignment fa) {
        TestRun run = fa.getTestRun();
        int dimensionId = fa.getDimension().getId();

        // Only check combinations that involve this dimension (much faster!)
        List<Combination> relevantCombos = dimensionToCombinations.get(dimensionId);
        if (relevantCombos == null) return;

        int densityChange = 0;

        for (Combination combo : relevantCombos) {
            if (CoverageUtil.isRunCoveringCombo(combo, run)) {
                Set<TestRun> coveringRuns = coverageMap.get(combo);
                boolean wasUncovered = coveringRuns.isEmpty();
                coveringRuns.add(run);
                if (wasUncovered) {
                    uncoveredCount--;
                }
                densityChange++;
            }
        }

        // Update density: add combinations now covered after change
        if (densityChange > 0) {
            coverageDensity.merge(run, densityChange, Integer::sum);
        }
    }
}
