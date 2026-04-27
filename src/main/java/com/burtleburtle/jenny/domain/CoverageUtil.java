package com.burtleburtle.jenny.domain;

import java.util.Map;

/**
 * Single-source coverage check used by the constraint provider, the greedy
 * initializer, and any in-test recount logic.
 *
 * <p>An {@link AllowedTuple} is "covered" by a feature assignment iff every
 * feature in the tuple matches the assignment for that dimension. Two
 * variants are exposed: one against a {@link TestCase} (uses its
 * {@code featuresByDim} shadow map), one against a raw
 * {@code Map<Dimension, Feature>} (used by the greedy initializer where no
 * planning entity exists yet).
 */
public final class CoverageUtil {

    private CoverageUtil() {
    }

    /**
     * True iff {@code testCase} is active and covers every feature in
     * {@code tuple}.
     */
    public static boolean covers(TestCase testCase, AllowedTuple tuple) {
        return testCase.isActiveFlag() && testCase.coversTuple(tuple);
    }

    /**
     * True iff {@code featuresByDim} matches every feature in {@code tuple}.
     * Active-flag agnostic.
     */
    public static boolean covers(Map<Dimension, Feature> featuresByDim, AllowedTuple tuple) {
        for (Feature wanted : tuple.features()) {
            Feature assigned = featuresByDim.get(wanted.dimension());
            if (assigned == null || !assigned.equals(wanted)) {
                return false;
            }
        }
        return true;
    }
}
