package com.burtleburtle.jenny.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import java.util.List;

/**
 * One (test, dimension) slot. The {@code feature} field is the planning
 * variable, with a per-entity value range drawn from the cell's dimension.
 */
@PlanningEntity
public class TestCell {

    @PlanningId
    private Long id;

    private TestCase testCase;

    private Dimension dimension;

    @PlanningPin
    private boolean pinned;

    @PlanningVariable(valueRangeProviderRefs = "featuresForCell")
    private Feature feature;

    public TestCell() {
    }

    public TestCell(long id, TestCase testCase, Dimension dimension) {
        this.id = id;
        this.testCase = testCase;
        this.dimension = dimension;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
    }

    public Dimension getDimension() {
        return dimension;
    }

    public void setDimension(Dimension dimension) {
        this.dimension = dimension;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public Feature getFeature() {
        return feature;
    }

    public void setFeature(Feature feature) {
        this.feature = feature;
    }

    @ValueRangeProvider(id = "featuresForCell")
    public List<Feature> featureValueRange() {
        return dimension.features();
    }

    @Override
    public String toString() {
        return "TestCell#" + id + "[tc=" + testCase.getId()
                + ", dim=" + dimension.index() + ", feature=" + feature + "]";
    }
}
