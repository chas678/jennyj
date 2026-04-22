package com.burtleburtle.jenny.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A forbidden combination. A {@link Without} lists, for each dimension it
 * references, the set of features that would be part of the forbidden
 * combination. A test matches the without iff, for every referenced
 * dimension, the test's feature in that dimension is in the without's set
 * for that dimension. See jenny.c:211–241 ({@code count_withouts}).
 *
 * <p>Example: {@code -w1a2cd4ac} forbids the combinations
 * (1a,2c,4a), (1a,2c,4c), (1a,2d,4a), (1a,2d,4c) — dimension 1 must be
 * feature {@code a}, dimension 2 must be {@code c} or {@code d}, dimension 4
 * must be {@code a} or {@code c}.
 */
public final class Without {

    private final Map<Dimension, Set<Feature>> allowedPerDimension;

    public Without(Map<Dimension, Set<Feature>> allowedPerDimension) {
        Objects.requireNonNull(allowedPerDimension, "allowedPerDimension");
        if (allowedPerDimension.isEmpty()) {
            throw new IllegalArgumentException("without must reference at least one dimension");
        }
        Map<Dimension, Set<Feature>> copy = new LinkedHashMap<>();
        for (Map.Entry<Dimension, Set<Feature>> e : allowedPerDimension.entrySet()) {
            if (e.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "without dim " + e.getKey() + " has no features");
            }
            copy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        this.allowedPerDimension = Map.copyOf(copy);
    }

    public Set<Dimension> dimensions() {
        return allowedPerDimension.keySet();
    }

    public Set<Feature> featuresFor(Dimension dimension) {
        Set<Feature> features = allowedPerDimension.get(dimension);
        return features == null ? Set.of() : features;
    }

    /**
     * True iff every dimension the without mentions is present in
     * {@code features} with a feature the without considers forbidden.
     */
    public boolean matches(Map<Dimension, Feature> features) {
        for (Map.Entry<Dimension, Set<Feature>> e : allowedPerDimension.entrySet()) {
            Feature assigned = features.get(e.getKey());
            if (assigned == null || !e.getValue().contains(assigned)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convenience overload for tuple matching — a {@link Collection} of
     * {@link Feature}s where at most one per dimension is expected.
     */
    public boolean matchesTuple(Collection<Feature> tupleFeatures) {
        Map<Dimension, Feature> byDim = new LinkedHashMap<>();
        for (Feature f : tupleFeatures) {
            byDim.put(f.dimension(), f);
        }
        return matches(byDim);
    }

    public List<Feature> asFlatList() {
        return allowedPerDimension.values().stream()
                .flatMap(Set::stream)
                .toList();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("-w");
        for (Map.Entry<Dimension, Set<Feature>> e : allowedPerDimension.entrySet()) {
            sb.append(e.getKey().index() + 1);
            for (Feature f : e.getValue()) {
                sb.append(f.name());
            }
        }
        return sb.toString();
    }
}
