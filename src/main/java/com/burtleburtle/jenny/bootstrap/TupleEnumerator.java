package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enumerates every allowed n-tuple of features across {@code n} of the
 * given dimensions. Replaces jenny.c's manual {@code next_builder} loop
 * (jenny.c:1122–1148).
 *
 * <p>Dimension selection uses {@link Sets#combinations(Set, int)}; the
 * feature-level Cartesian expansion uses
 * {@link Lists#cartesianProduct(List)}. Any tuple matching any
 * {@link Without} is filtered out before being emitted.
 */
public final class TupleEnumerator {

    /**
     * Bounds up-front enumeration so pathological inputs (large {@code -n}
     * with big dimensions) fail cleanly instead of exhausting memory. Matches
     * the Go port's {@code tupleCap} (cmd/jennygo/jenny.go).
     */
    public static final int TUPLE_CAP = 20_000_000;

    private TupleEnumerator() {
    }

    public static List<AllowedTuple> enumerate(
            List<Dimension> dimensions,
            int tupleSize,
            Collection<Without> withouts) {
        return enumerate(dimensions, tupleSize, withouts, TUPLE_CAP);
    }

    /**
     * Package-private overload with an explicit cap, so tests can exercise
     * the {@link TupleEnumerationTooLargeException} path without actually
     * enumerating {@link #TUPLE_CAP} tuples.
     */
    static List<AllowedTuple> enumerate(
            List<Dimension> dimensions,
            int tupleSize,
            Collection<Without> withouts,
            int cap) {

        if (tupleSize < 1) {
            throw new IllegalArgumentException("tupleSize must be >= 1");
        }
        if (tupleSize > dimensions.size()) {
            throw new IllegalArgumentException(
                    "tupleSize " + tupleSize + " exceeds dimension count " + dimensions.size());
        }

        Set<Dimension> dimensionSet = new LinkedHashSet<>(dimensions);
        List<AllowedTuple> result = new ArrayList<>();

        for (Set<Dimension> combo : Sets.combinations(dimensionSet, tupleSize)) {
            List<List<Feature>> perDim = combo.stream()
                    .map(Dimension::features)
                    .toList();
            for (List<Feature> features : Lists.cartesianProduct(perDim)) {
                boolean forbidden = false;
                for (Without w : withouts) {
                    if (w.matchesTuple(features)) {
                        forbidden = true;
                        break;
                    }
                }
                if (!forbidden) {
                    if (result.size() >= cap) {
                        throw new TupleEnumerationTooLargeException(
                                "jenny: enumeration of allowed " + tupleSize
                                        + "-tuples exceeded " + cap
                                        + "; reduce -n or the number/size of dimensions");
                    }
                    result.add(new AllowedTuple(features));
                }
            }
        }
        return result;
    }
}
