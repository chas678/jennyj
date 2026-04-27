package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TupleEnumeratorTest {

    @Test
    void pairs_of_three_binary_dimensions_gives_twelve_tuples() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2));

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, List.of());

        assertEquals(12, tuples.size(), "C(3,2) * 2*2 = 12");
        tuples.forEach(t -> assertEquals(2, t.size()));
    }

    @Test
    void singletons_equal_total_feature_count() {
        List<Dimension> dims = List.of(
                new Dimension(0, 3),
                new Dimension(1, 2),
                new Dimension(2, 5));

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 1, List.of());

        assertEquals(3 + 2 + 5, tuples.size());
    }

    @Test
    void without_removes_matching_tuples() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2));
        Without w = new Without(java.util.Map.of(
                dims.get(0), java.util.Set.of(dims.get(0).feature(0)),
                dims.get(1), java.util.Set.of(dims.get(1).feature(0))));

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, List.of(w));

        assertEquals(3, tuples.size(), "4 combinations minus the forbidden (1a,2a) = 3");
        assertTrue(tuples.stream().noneMatch(t ->
                t.features().get(0).featureIndex() == 0
                        && t.features().get(1).featureIndex() == 0));
    }

    @Test
    void jenny_example_line_50_has_expected_tuple_count() {
        // jenny -n3 2 3 8 3 2 2 5 3 2 2   (restrictions dropped for this check)
        int[] sizes = {2, 3, 8, 3, 2, 2, 5, 3, 2, 2};
        List<Dimension> dims = new java.util.ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            dims.add(new Dimension(i, sizes[i]));
        }

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 3, List.of());

        // Sum over all 3-subsets of {dims} of product of their sizes.
        long expected = 0;
        int n = sizes.length;
        for (int i = 0; i < n - 2; i++) {
            for (int j = i + 1; j < n - 1; j++) {
                for (int k = j + 1; k < n; k++) {
                    expected += (long) sizes[i] * sizes[j] * sizes[k];
                }
            }
        }
        assertEquals(expected, tuples.size());
    }
}
