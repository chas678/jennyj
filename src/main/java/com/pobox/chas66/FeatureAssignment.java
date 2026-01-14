package com.pobox.chas66;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@PlanningEntity
public class FeatureAssignment {
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TestRun testRun;
    @PlanningId
    private String id; // Usually a combination of RunID and DimensionID (e.g., "0-1")

    private Dimension dimension;

    @PlanningVariable(valueRangeProviderRefs = "featureRange")
    private Character value;


    public FeatureAssignment(TestRun testRun, String id, Dimension dimension) {
        this.testRun = testRun;
        this.id = id;
        this.dimension = dimension;
    }
}