package com.pobox.chas66;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionSorter;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class FeatureAssignmentDifficultySorter implements SelectionSorter<PairwiseSolution, FeatureAssignment> {

    // Define the comparator once for consistency
    private static final Comparator<FeatureAssignment> DIFFICULTY_COMPARATOR =
            Comparator.comparingInt((FeatureAssignment fa) -> fa.getDimension().getSize())
                    .reversed() // Hardest (largest) dimensions first
                    .thenComparing(FeatureAssignment::getId);

    @Override
    public void sort(PairwiseSolution pairwiseSolution, List<FeatureAssignment> list) {
        list.sort(DIFFICULTY_COMPARATOR);
    }

    @Override
    public SortedSet<FeatureAssignment> sort(PairwiseSolution pairwiseSolution, Set<FeatureAssignment> set) {
        SortedSet<FeatureAssignment> sortedSet = new TreeSet<>(DIFFICULTY_COMPARATOR);
        sortedSet.addAll(set);
        return sortedSet;
    }
}