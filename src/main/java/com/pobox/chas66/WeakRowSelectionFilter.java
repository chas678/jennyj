package com.pobox.chas66;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;

public class WeakRowSelectionFilter implements SelectionFilter<PairwiseSolution, FeatureAssignment> {

    @Override
    public boolean accept(ScoreDirector<PairwiseSolution> scoreDirector, FeatureAssignment selection) {
        TestRun run = selection.getTestRun();

        // 1. Only bother with active rows
        if (!run.getActive()) {
            return false;
        }

        // 2. Optimization: Heuristic-based filtering.
        // We prioritize moves on rows with higher IDs.
        // In our GreedyInitializer, higher IDs were created last and
        // typically cover the fewest 'uncovered' tuples (the "tail" of the problem).
        PairwiseSolution solution = scoreDirector.getWorkingSolution();
        int totalActive = (int) solution.getTestRuns().stream().filter(TestRun::getActive).count();

        // Focus on the bottom 30% of rows (the most likely candidates for deletion)
        return run.getId() > (totalActive * 0.7);
    }
}