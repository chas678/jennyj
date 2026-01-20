package com.pobox.chas66;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@PlanningSolution
public class PairwiseSolution {

    @ProblemFactCollectionProperty
    private List<Dimension> dimensions;

    @ProblemFactCollectionProperty
    private List<Combination> requiredCombinations;

    @PlanningEntityCollectionProperty
    private List<TestRun> testRuns;

    @PlanningScore
    private HardMediumSoftScore score;

    @ValueRangeProvider(id = "activeRange")
    public List<Boolean> getActiveRange() {
        return List.of(Boolean.TRUE, Boolean.FALSE);
    }

    @ProblemFactCollectionProperty
    private List<ForbiddenCombination> forbiddenCombinations = new ArrayList<>();

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
}