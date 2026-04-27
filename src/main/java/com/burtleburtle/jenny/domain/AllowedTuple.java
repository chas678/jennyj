package com.burtleburtle.jenny.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An n-tuple of features (one feature per dimension, each feature's
 * dimension distinct) that must be covered by at least one test case.
 * Pre-computed: any tuple matching a {@link Without} is filtered out before
 * becoming an {@code AllowedTuple}.
 */
public final class AllowedTuple {

    private final List<Feature> features;
    private final int cachedHash;

    public AllowedTuple(List<Feature> features) {
        Objects.requireNonNull(features, "features");
        if (features.isEmpty()) {
            throw new IllegalArgumentException("tuple must contain at least one feature");
        }
        List<Feature> sorted = features.stream()
                .sorted(Comparator.comparingInt(f -> f.dimension().index()))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).dimension().equals(sorted.get(i - 1).dimension())) {
                throw new IllegalArgumentException(
                        "tuple has two features from the same dimension: " + features);
            }
        }
        this.features = sorted;
        this.cachedHash = sorted.hashCode();
    }

    public List<Feature> features() {
        return features;
    }

    public int size() {
        return features.size();
    }

    public Map<Dimension, Feature> asMap() {
        return features.stream()
                .collect(Collectors.toUnmodifiableMap(Feature::dimension, f -> f));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AllowedTuple other && other.features.equals(this.features);
    }

    @Override
    public int hashCode() {
        return cachedHash;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Feature f : features) {
            sb.append(' ').append(f);
        }
        return sb.append(' ').toString();
    }
}
