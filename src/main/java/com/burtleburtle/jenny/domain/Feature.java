package com.burtleburtle.jenny.domain;

import java.util.Objects;

/**
 * One feature of a {@link Dimension}, indexed 0..size-1. Identity is by
 * {@code (dimension.index, featureIndex)} — two {@code Feature}s with the
 * same indices compare equal even if their {@code Dimension} instances
 * differ. Hash uses the perfect-hash {@code dim.index * 64 + featureIndex},
 * which is faster than the auto-generated {@code Objects.hash} on a hot
 * constraint-stream path.
 */
public record Feature(Dimension dimension, int featureIndex) {

    private static final String NAMES =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public Feature {
        Objects.requireNonNull(dimension, "dimension");
        if (featureIndex < 0 || featureIndex >= NAMES.length()) {
            throw new IllegalArgumentException(
                    "featureIndex must be in [0, " + NAMES.length() + "), got " + featureIndex);
        }
    }

    public char name() {
        return NAMES.charAt(featureIndex);
    }

    public static int indexOfName(char name) {
        int i = NAMES.indexOf(name);
        if (i < 0) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a valid feature name (expected a-z, A-Z)");
        }
        return i;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Feature other
                && other.dimension.index() == this.dimension.index()
                && other.featureIndex == this.featureIndex;
    }

    @Override
    public int hashCode() {
        return dimension.index() * 64 + featureIndex;
    }

    @Override
    public String toString() {
        return (dimension.index() + 1) + String.valueOf(name());
    }
}
