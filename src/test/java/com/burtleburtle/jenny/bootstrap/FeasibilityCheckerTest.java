package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeasibilityCheckerTest {

    @Test
    void everything_is_feasible_when_there_are_no_withouts() {
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(), new Random(0));

        Map<Dimension, Feature> pinned = Map.of(dims.get(0), dims.get(0).feature(0));

        assertTrue(checker.isFeasible(pinned));
    }

    @Test
    void fully_pinned_combo_matching_a_without_is_infeasible() {
        // Single binary dimension, without forbids its only feature 'a' from
        // ever appearing alongside dim2=a. Pinning dim1=a, dim2=a leaves no
        // mutable dimension to escape the violation.
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2));
        Without w = new Without(Map.of(
                dims.get(0), Set.of(dims.get(0).feature(0)),
                dims.get(1), Set.of(dims.get(1).feature(0))));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(w), new Random(0));

        Map<Dimension, Feature> pinned = Map.of(
                dims.get(0), dims.get(0).feature(0),
                dims.get(1), dims.get(1).feature(0));

        assertFalse(checker.isFeasible(pinned));
    }

    @Test
    void pinned_combo_that_avoids_the_without_is_feasible() {
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2));
        Without w = new Without(Map.of(
                dims.get(0), Set.of(dims.get(0).feature(0)),
                dims.get(1), Set.of(dims.get(1).feature(0))));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(w), new Random(0));

        Map<Dimension, Feature> pinned = Map.of(dims.get(0), dims.get(0).feature(1));

        assertTrue(checker.isFeasible(pinned));
    }

    @Test
    void a_pinned_dimension_not_touched_by_any_without_is_always_feasible() {
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2), new Dimension(2, 2));
        // Without forbids dim2=a entirely (both features of dim1 forbidden with it).
        Without w = new Without(Map.of(
                dims.get(1), Set.of(dims.get(1).feature(0))));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(w), new Random(0));

        Map<Dimension, Feature> pinned = Map.of(dims.get(1), dims.get(1).feature(0));

        // dim2=a is globally forbidden regardless of dim1/dim3 -> infeasible.
        assertFalse(checker.isFeasible(pinned));
    }

    @Test
    void minimal_core_shrinks_to_the_single_feature_that_is_globally_forbidden() {
        // 3 binary dims. Without forbids dim3 = a entirely (dim1's both
        // features paired with dim3=a are forbidden), so any tuple containing
        // dim3=a is uncoverable purely because of that single coordinate.
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2), new Dimension(2, 2));
        Without w = new Without(Map.of(
                dims.get(2), Set.of(dims.get(2).feature(0))));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(w), new Random(0));

        List<Feature> tupleFeatures = List.of(dims.get(0).feature(0), dims.get(2).feature(0));

        List<Feature> core = checker.minimalCore(tupleFeatures);

        assertEquals(1, core.size());
        assertEquals(dims.get(2).feature(0), core.get(0));
    }
}
