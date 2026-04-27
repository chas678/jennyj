package com.burtleburtle.jenny.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardSoftScore;

import java.util.List;

@PlanningSolution
public class JennySolution {

    @ProblemFactCollectionProperty
    private List<Dimension> dimensions;

    @ProblemFactCollectionProperty
    private List<AllowedTuple> allowedTuples;

    @ProblemFactCollectionProperty
    private List<Without> withouts;

    @PlanningEntityCollectionProperty
    private List<TestCase> testCases;

    @PlanningEntityCollectionProperty
    private List<TestCell> testCells;

    @PlanningScore
    private HardSoftScore score;

    public JennySolution() {
    }

    public JennySolution(
            List<Dimension> dimensions,
            List<AllowedTuple> allowedTuples,
            List<Without> withouts,
            List<TestCase> testCases,
            List<TestCell> testCells) {
        this.dimensions = dimensions;
        this.allowedTuples = allowedTuples;
        this.withouts = withouts;
        this.testCases = testCases;
        this.testCells = testCells;
    }

    @ValueRangeProvider(id = "boolRange")
    public List<Boolean> boolRange() {
        return List.of(Boolean.TRUE, Boolean.FALSE);
    }

    public List<Dimension> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<Dimension> dimensions) {
        this.dimensions = dimensions;
    }

    public List<AllowedTuple> getAllowedTuples() {
        return allowedTuples;
    }

    public void setAllowedTuples(List<AllowedTuple> allowedTuples) {
        this.allowedTuples = allowedTuples;
    }

    public List<Without> getWithouts() {
        return withouts;
    }

    public void setWithouts(List<Without> withouts) {
        this.withouts = withouts;
    }

    public List<TestCase> getTestCases() {
        return testCases;
    }

    public void setTestCases(List<TestCase> testCases) {
        this.testCases = testCases;
    }

    public List<TestCell> getTestCells() {
        return testCells;
    }

    public void setTestCells(List<TestCell> testCells) {
        this.testCells = testCells;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
}
