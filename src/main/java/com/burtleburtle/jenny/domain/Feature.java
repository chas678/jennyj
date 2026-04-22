package com.burtleburtle.jenny.domain;

import java.util.Objects;

public final class Feature {

    private static final String NAMES =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final Dimension dimension;
    private final int featureIndex;

    public Feature(Dimension dimension, int featureIndex) {
        if (featureIndex < 0 || featureIndex >= NAMES.length()) {
            throw new IllegalArgumentException(
                    "featureIndex must be in [0, " + NAMES.length() + "), got " + featureIndex);
        }
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.featureIndex = featureIndex;
    }

    public Dimension dimension() {
        return dimension;
    }

    public int featureIndex() {
        return featureIndex;
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
