package com.burtleburtle.jenny.cli;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithoutParserTest {

    private static List<Dimension> dims(int... sizes) {
        java.util.List<Dimension> result = new java.util.ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            result.add(new Dimension(i, sizes[i]));
        }
        return result;
    }

    @Test
    void simple_single_feature_per_dimension() {
        List<Dimension> dims = dims(2, 3, 4);

        Without w = WithoutParser.parse("1a2b3c", dims);

        assertEquals(3, w.dimensions().size());
        assertEquals(Set.of(dims.get(0).feature(0)), w.featuresFor(dims.get(0)));
        assertEquals(Set.of(dims.get(1).feature(1)), w.featuresFor(dims.get(1)));
        assertEquals(Set.of(dims.get(2).feature(2)), w.featuresFor(dims.get(2)));
    }

    @Test
    void multi_feature_block_expands_cartesian() {
        // jenny.c doc example: -w1a2cd4ac forbids
        //   (1a,2c,4a), (1a,2c,4c), (1a,2d,4a), (1a,2d,4c)
        List<Dimension> dims = dims(2, 4, 2, 4);

        Without w = WithoutParser.parse("1a2cd4ac", dims);

        assertEquals(3, w.dimensions().size());
        assertEquals(Set.of(dims.get(0).feature(0)),
                w.featuresFor(dims.get(0)));
        assertEquals(Set.of(dims.get(1).feature(2), dims.get(1).feature(3)),
                w.featuresFor(dims.get(1)));
        assertEquals(Set.of(dims.get(3).feature(0), dims.get(3).feature(2)),
                w.featuresFor(dims.get(3)));
    }

    @Test
    void matches_detects_forbidden_combination() {
        List<Dimension> dims = dims(2, 4);
        Without w = WithoutParser.parse("1a2bc", dims);

        Feature f1a = dims.get(0).feature(0);
        Feature f1b = dims.get(0).feature(1);
        Feature f2b = dims.get(1).feature(1);
        Feature f2c = dims.get(1).feature(2);
        Feature f2d = dims.get(1).feature(3);

        assertTrue(w.matchesTuple(List.of(f1a, f2b)));
        assertTrue(w.matchesTuple(List.of(f1a, f2c)));
        assertEquals(false, w.matchesTuple(List.of(f1a, f2d)));
        assertEquals(false, w.matchesTuple(List.of(f1b, f2b)));
    }

    @Test
    void rejects_bad_dimension_number() {
        List<Dimension> dims = dims(2, 2);
        assertThrows(IllegalArgumentException.class, () -> WithoutParser.parse("99a", dims));
    }

    @Test
    void rejects_duplicate_dimension() {
        List<Dimension> dims = dims(2, 2);
        assertThrows(IllegalArgumentException.class, () -> WithoutParser.parse("1a1b", dims));
    }

    @Test
    void rejects_missing_features() {
        List<Dimension> dims = dims(2, 2);
        assertThrows(IllegalArgumentException.class, () -> WithoutParser.parse("12", dims));
    }

    @Test
    void rejects_feature_out_of_range() {
        List<Dimension> dims = dims(2, 2);
        assertThrows(IllegalArgumentException.class, () -> WithoutParser.parse("1c", dims));
    }
}
