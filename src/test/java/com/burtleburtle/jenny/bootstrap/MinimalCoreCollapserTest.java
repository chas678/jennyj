package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimalCoreCollapserTest {

    @Test
    void collapses_every_uncoverable_tuple_containing_the_forbidden_feature_to_one_core() {
        // 3 binary dims. dim3=a is globally forbidden (paired with either
        // feature of dim1). Every 2-tuple naming dim3=a is uncoverable for
        // that single reason; they should all collapse to one core: {dim3=a}.
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2), new Dimension(2, 2));
        Without w = new Without(Map.of(dims.get(2), Set.of(dims.get(2).feature(0))));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(w), new Random(0));

        AllowedTuple t1 = new AllowedTuple(List.of(dims.get(0).feature(0), dims.get(2).feature(0)));
        AllowedTuple t2 = new AllowedTuple(List.of(dims.get(0).feature(1), dims.get(2).feature(0)));
        AllowedTuple t3 = new AllowedTuple(List.of(dims.get(1).feature(0), dims.get(2).feature(0)));
        AllowedTuple t4 = new AllowedTuple(List.of(dims.get(1).feature(1), dims.get(2).feature(0)));

        List<AllowedTuple> collapsed = MinimalCoreCollapser.collapse(List.of(t1, t2, t3, t4), checker);

        assertEquals(1, collapsed.size(), "all four should collapse to a single core: " + collapsed);
        assertEquals(1, collapsed.get(0).size());
        assertEquals(dims.get(2).feature(0), collapsed.get(0).features().get(0));
    }

    @Test
    void feasible_tuples_pass_through_unchanged() {
        List<Dimension> dims = List.of(new Dimension(0, 2), new Dimension(1, 2));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(), new Random(0));

        AllowedTuple t1 = new AllowedTuple(List.of(dims.get(0).feature(0), dims.get(1).feature(0)));

        List<AllowedTuple> collapsed = MinimalCoreCollapser.collapse(List.of(t1), checker);

        assertEquals(List.of(t1), collapsed);
    }

    @Test
    void distinct_infeasible_causes_produce_distinct_cores() {
        // Two independent globally-forbidden features on two different dims:
        // dim2=a forbidden (paired with dim1), dim4=a forbidden (paired with dim3).
        List<Dimension> dims = List.of(
                new Dimension(0, 2), new Dimension(1, 2),
                new Dimension(2, 2), new Dimension(3, 2));
        Without w1 = new Without(Map.of(dims.get(1), Set.of(dims.get(1).feature(0))));
        Without w2 = new Without(Map.of(dims.get(3), Set.of(dims.get(3).feature(0))));
        FeasibilityChecker checker = new FeasibilityChecker(dims, List.of(w1, w2), new Random(0));

        AllowedTuple t1 = new AllowedTuple(List.of(dims.get(0).feature(0), dims.get(1).feature(0)));
        AllowedTuple t2 = new AllowedTuple(List.of(dims.get(2).feature(0), dims.get(3).feature(0)));

        List<AllowedTuple> collapsed = MinimalCoreCollapser.collapse(List.of(t1, t2), checker);

        assertEquals(2, collapsed.size(), "distinct infeasible causes should not merge: " + collapsed);
        Set<Feature> singleFeatures = Set.of(collapsed.get(0).features().get(0), collapsed.get(1).features().get(0));
        assertTrue(singleFeatures.contains(dims.get(1).feature(0)));
        assertTrue(singleFeatures.contains(dims.get(3).feature(0)));
    }
}
