package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.domain.variable.VariableListener;
import ai.timefold.solver.core.api.score.director.ScoreDirector;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;

/**
 * Keeps {@link TestCase#getFeaturesByDim()} in sync with the set of
 * {@link TestCell#getFeature()} values belonging to that test case. The
 * shadow map is what {@code JennyConstraintProvider} reads when checking
 * coverage and without violations.
 */
public class FeatureShadowListener implements VariableListener<JennySolution, TestCell> {

    @Override
    public void beforeVariableChanged(ScoreDirector<JennySolution> scoreDirector, TestCell cell) {
        TestCase tc = cell.getTestCase();
        if (tc == null) {
            return;
        }
        Feature old = cell.getFeature();
        if (old == null) {
            return;
        }
        scoreDirector.beforeVariableChanged(tc, "featuresByDim");
        Dimension dim = cell.getDimension();
        Feature current = tc.getFeaturesByDim().get(dim);
        if (old.equals(current)) {
            tc.getFeaturesByDim().remove(dim);
        }
        scoreDirector.afterVariableChanged(tc, "featuresByDim");
    }

    @Override
    public void afterVariableChanged(ScoreDirector<JennySolution> scoreDirector, TestCell cell) {
        TestCase tc = cell.getTestCase();
        if (tc == null) {
            return;
        }
        Feature now = cell.getFeature();
        scoreDirector.beforeVariableChanged(tc, "featuresByDim");
        if (now != null) {
            tc.getFeaturesByDim().put(cell.getDimension(), now);
        }
        scoreDirector.afterVariableChanged(tc, "featuresByDim");
    }

    @Override
    public void beforeEntityAdded(ScoreDirector<JennySolution> scoreDirector, TestCell cell) {
    }

    @Override
    public void afterEntityAdded(ScoreDirector<JennySolution> scoreDirector, TestCell cell) {
        TestCase tc = cell.getTestCase();
        if (tc == null || cell.getFeature() == null) {
            return;
        }
        scoreDirector.beforeVariableChanged(tc, "featuresByDim");
        tc.getFeaturesByDim().put(cell.getDimension(), cell.getFeature());
        scoreDirector.afterVariableChanged(tc, "featuresByDim");
    }

    @Override
    public void beforeEntityRemoved(ScoreDirector<JennySolution> scoreDirector, TestCell cell) {
        TestCase tc = cell.getTestCase();
        if (tc == null) {
            return;
        }
        scoreDirector.beforeVariableChanged(tc, "featuresByDim");
        tc.getFeaturesByDim().remove(cell.getDimension());
        scoreDirector.afterVariableChanged(tc, "featuresByDim");
    }

    @Override
    public void afterEntityRemoved(ScoreDirector<JennySolution> scoreDirector, TestCell cell) {
    }
}
