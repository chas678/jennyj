package com.pobox.chas66;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Data
@NoArgsConstructor
@PlanningEntity
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FeatureAssignment {
    @ToString.Exclude
    private TestRun testRun;
    @PlanningId
    @EqualsAndHashCode.Include
    private String id; // Usually a combination of RunID and DimensionID (e.g., "0-1")

    private Dimension dimension;

    @PlanningVariable(valueRangeProviderRefs = "featureRange")
    private Character value;


    public FeatureAssignment(TestRun testRun, String id, Dimension dimension) {
        this.testRun = testRun;
        this.id = id;
        this.dimension = dimension;
    }

    @ValueRangeProvider(id = "featureRange")
    public List<Character> getPossibleValues() {
        return CharacterEncoding.getRangeForSize(dimension.getSize());
    }
}