package com.burtleburtle.jenny.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.domain.variable.ShadowVariable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row in the covering array. Holds an {@code active} planning variable
 * and a derived {@code featuresByDim} shadow map that's kept in sync by
 * {@link com.burtleburtle.jenny.solver.FeatureShadowListener} whenever a
 * {@link TestCell} belonging to this test case changes.
 *
 * <p>If {@code active} is {@code false}, the test case is not considered by
 * coverage or without constraints (it's unused capacity). This lets the
 * solver minimize the number of real test cases via the soft score.
 */
@PlanningEntity
public class TestCase {

    @PlanningId
    private Long id;

    @PlanningPin
    private boolean pinned;

    @PlanningVariable(valueRangeProviderRefs = "boolRange")
    private Boolean active;

    @ShadowVariable(
            variableListenerClass = com.burtleburtle.jenny.solver.FeatureShadowListener.class,
            sourceVariableName = "feature",
            sourceEntityClass = TestCell.class)
    private Map<Dimension, Feature> featuresByDim = new LinkedHashMap<>();

    public TestCase() {
    }

    public TestCase(long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }

    public Map<Dimension, Feature> getFeaturesByDim() {
        return featuresByDim;
    }

    public void setFeaturesByDim(Map<Dimension, Feature> featuresByDim) {
        this.featuresByDim = featuresByDim;
    }

    public boolean coversTuple(AllowedTuple tuple) {
        for (Feature f : tuple.features()) {
            Feature assigned = featuresByDim.get(f.dimension());
            if (assigned == null || !assigned.equals(f)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "TestCase#" + id + "{active=" + active + ", cells=" + featuresByDim.size() + "}";
    }
}
