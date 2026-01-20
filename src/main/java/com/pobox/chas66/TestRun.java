package com.pobox.chas66;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@PlanningEntity
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TestRun {

    @PlanningId
    @EqualsAndHashCode.Include
    private int id;

    @PlanningVariable(valueRangeProviderRefs = "activeRange")
    private Boolean active = true;

    @PlanningEntityCollectionProperty
    private List<FeatureAssignment> assignments;

    // This is NOT a planning variable. It's a helper for the ConstraintProvider.
    private Map<Integer, FeatureAssignment> assignmentMap = Collections.emptyMap();
    private int version = 0;
    /**
     * Whenever the solver or the initializer sets the assignments,
     * we rebuild the map for O(1) lookups.
     */
    public void setAssignments(List<FeatureAssignment> assignments) {
        this.assignments = assignments;
        this.version++;
        if (assignments != null) {
            this.assignmentMap = assignments.stream()
                    .collect(Collectors.toMap(
                            fa -> fa.getDimension().getId(),
                            Function.identity()
                    ));
        } else {
            this.assignmentMap = Collections.emptyMap();
        }
    }

    /**
     * Efficient lookup for the Constraint Stream.
     */
    public FeatureAssignment getAssignmentForDimension(int dimensionId) {
        return assignmentMap.get(dimensionId);
    }
}