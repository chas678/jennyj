package com.pobox.chas66;

import ai.timefold.solver.core.api.domain.common.ComparatorFactory;
import java.util.Comparator;

/**
 * Modern (non-deprecated) way to sort entities by difficulty.
 */
public class FeatureAssignmentDifficultyComparatorFactory
        implements ComparatorFactory<PairwiseSolution, FeatureAssignment> {

    @Override
    public Comparator<FeatureAssignment> createComparator(PairwiseSolution solution) {
        // We prioritize assignments where the dimension has MORE features.
        // These are harder to fit into existing rows, so CH should handle them first.
        return Comparator.comparingInt((FeatureAssignment fa) -> fa.getDimension().getSize())
                .reversed() // Descending order: largest dimensions first
                .thenComparing(FeatureAssignment::getId);
    }
}