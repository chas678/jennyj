package com.burtleburtle.jenny.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

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

    /** Recomputed on each call; see class javadoc. */
    public Map<Dimension, Feature> featuresByDim() {
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
        outer:
        for (Feature wanted : tuple.features()) {
            for (TestCell cell : cells) {
                if (cell.getDimension().equals(wanted.dimension())
                        && wanted.equals(cell.getFeature())) {
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "TestCase#" + id + "{active=" + active + ", cells=" + cells.size() + "}";
    }
}
