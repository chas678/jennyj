package com.burtleburtle.jenny.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.domain.variable.ShadowSources;
import ai.timefold.solver.core.api.domain.variable.ShadowVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row in the covering array. Holds an {@code active} planning variable
 * and a non-planning {@link TestCell} list populated once at solution-build
 * time. Coverage and without checks read the current per-dimension feature
 * values by iterating {@link #cells}; this is re-done on every call rather
 * than held as a shadow variable (see T26 in TASKS.md for the deferred
 * shadow-variable optimisation).
 *
 * <p>If {@code active} is {@code false}, the test case is not considered by
 * coverage or without constraints (it's unused capacity). This lets the
 * solver minimise the number of real test cases via the soft score.
 */
@PlanningEntity
public class TestCase {

    @PlanningId
    private Long id;

    @PlanningPin
    private boolean pinned;

    @PlanningVariable(valueRangeProviderRefs = "boolRange")
    private Boolean active;

    private List<TestCell> cells = List.of();

    /**
     * O(1) dimension → feature lookup, used by {@link #coversTuple} and the
     * constraint provider. Maintained as a {@link ShadowVariable} so Timefold
     * automatically rebuilds it whenever any cell's feature changes — no
     * manual upkeep on the setter path.
     */
    @ShadowVariable(supplierName = "recomputeFeaturesByDim")
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

    public boolean isActiveFlag() {
        return Boolean.TRUE.equals(active);
    }

    public List<TestCell> getCells() {
        return cells;
    }

    public void setCells(List<TestCell> cells) {
        this.cells = cells;
    }

    public Map<Dimension, Feature> getFeaturesByDim() {
        return featuresByDim;
    }

    public void setFeaturesByDim(Map<Dimension, Feature> featuresByDim) {
        this.featuresByDim = featuresByDim;
    }

    /**
     * Supplier for the shadow variable {@link #featuresByDim}. Timefold
     * re-invokes this whenever any {@link TestCell#getFeature()} on this
     * test case's {@link #cells} list changes — the dependency is
     * declared via {@link ShadowSources}.
     */
    @ShadowSources("cells[].feature")
    public Map<Dimension, Feature> recomputeFeaturesByDim() {
        Map<Dimension, Feature> result = new LinkedHashMap<>(cells.size() * 2);
        for (TestCell cell : cells) {
            Feature f = cell.getFeature();
            if (f != null) {
                result.put(cell.getDimension(), f);
            }
        }
        return result;
    }

    public boolean coversTuple(AllowedTuple tuple) {
        for (Feature wanted : tuple.features()) {
            Feature assigned = featuresByDim.get(wanted.dimension());
            if (assigned == null || !assigned.equals(wanted)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "TestCase#" + id + "{active=" + active + ", cells=" + cells.size() + "}";
    }
}
