package com.pobox.chas66;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Planning solution for pairwise/n-way test generation.
 *
 * Supports both eager and lazy combination generation:
 * - Eager: Pass requiredCombinations as a List (backward compatible)
 * - Lazy: Pass a CombinationIterator which generates combinations on-demand
 */
@Data
@NoArgsConstructor
@PlanningSolution
public class PairwiseSolution {

    private static final Logger log = LoggerFactory.getLogger(PairwiseSolution.class);
    private static final List<Boolean> ACTIVE_RANGE = List.of(Boolean.TRUE, Boolean.FALSE);

    @ProblemFactCollectionProperty
    private List<Dimension> dimensions;

    @ProblemFactCollectionProperty
    private List<Combination> requiredCombinations;

    // Optional: lazy combination generation (not a planning property)
    private transient CombinationIterator combinationIterator;

    @PlanningEntityCollectionProperty
    private List<TestRun> testRuns;

    @PlanningScore
    private HardMediumSoftScore score;

    @ProblemFactCollectionProperty
    private List<ForbiddenCombination> forbiddenCombinations = new ArrayList<>();

    /**
     * Constructor for eager combination generation (backward compatible).
     */
    public PairwiseSolution(List<Dimension> dimensions,
                           List<Combination> requiredCombinations,
                           List<TestRun> testRuns,
                           HardMediumSoftScore score,
                           List<ForbiddenCombination> forbiddenCombinations) {
        this.dimensions = dimensions;
        this.requiredCombinations = requiredCombinations;
        this.testRuns = testRuns;
        this.score = score;
        this.forbiddenCombinations = forbiddenCombinations != null ? forbiddenCombinations : new ArrayList<>();
        this.combinationIterator = null; // Eager mode
    }

    /**
     * Constructor for lazy combination generation (memory efficient).
     * Combinations are generated on-demand when first accessed.
     */
    public PairwiseSolution(List<Dimension> dimensions,
                           CombinationIterator combinationIterator,
                           List<TestRun> testRuns,
                           HardMediumSoftScore score,
                           List<ForbiddenCombination> forbiddenCombinations) {
        this.dimensions = dimensions;
        this.requiredCombinations = null; // Lazy - will be populated on first access
        this.combinationIterator = combinationIterator;
        this.testRuns = testRuns;
        this.score = score;
        this.forbiddenCombinations = forbiddenCombinations != null ? forbiddenCombinations : new ArrayList<>();
    }

    /**
     * Gets required combinations, lazy-loading from iterator if necessary.
     * This ensures transparency - callers don't need to know if lazy or eager mode is used.
     */
    public List<Combination> getRequiredCombinations() {
        // If already populated, return it
        if (requiredCombinations != null) {
            return requiredCombinations;
        }

        // If we have an iterator, populate from it (lazy generation)
        if (combinationIterator != null) {
            log.info("Lazy-generating combinations from iterator (memory efficient mode)");
            long startTime = System.currentTimeMillis();

            requiredCombinations = combinationIterator.toList(true);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Generated {} combinations in {}ms ({} per sec)",
                requiredCombinations.size(),
                duration,
                duration > 0 ? (requiredCombinations.size() * 1000 / duration) : "N/A");

            return requiredCombinations;
        }

        // Neither populated nor iterator available - return empty list
        log.warn("No combinations available (neither eager list nor lazy iterator provided)");
        return new ArrayList<>();
    }

    /**
     * Sets required combinations (disables lazy mode).
     */
    public void setRequiredCombinations(List<Combination> requiredCombinations) {
        this.requiredCombinations = requiredCombinations;
        this.combinationIterator = null; // Disable lazy mode
    }

    @ValueRangeProvider(id = "activeRange")
    public List<Boolean> getActiveRange() {
        return ACTIVE_RANGE;
    }

    /**
     * Helper to get all assignments across all runs.
     * While TestRun holds the assignments, Timefold sometimes needs
     * a flattened view to perform certain move types.
     */
    @PlanningEntityCollectionProperty
    public List<FeatureAssignment> getFlattenedAssignments() {
        List<FeatureAssignment> all = new ArrayList<>();
        if (testRuns != null) {
            for (TestRun run : testRuns) {
                if (run.getAssignments() != null) {
                    all.addAll(run.getAssignments());
                }
            }
        }
        return all;
    }

    /**
     * Returns memory usage statistics if using lazy mode.
     */
    public String getCombinationMemoryStats() {
        if (combinationIterator != null) {
            return combinationIterator.getMemoryStats();
        }
        if (requiredCombinations != null) {
            return String.format("Eager mode: %d combinations loaded", requiredCombinations.size());
        }
        return "No combinations loaded";
    }
}