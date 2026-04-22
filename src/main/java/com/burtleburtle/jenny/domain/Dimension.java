package com.burtleburtle.jenny.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Dimension {

    public static final int MAX_FEATURES = 52;

    private final int index;
    private final int size;
    private final List<Feature> features;

    public Dimension(int index, int size) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (size < 2 || size > MAX_FEATURES) {
            throw new IllegalArgumentException(
                    "dimension size must be in [2, " + MAX_FEATURES + "], got " + size);
        }
        this.index = index;
        this.size = size;
        List<Feature> fs = new ArrayList<>(size);
        for (int f = 0; f < size; f++) {
            fs.add(new Feature(this, f));
        }
        this.features = List.copyOf(fs);
    }

    public int index() {
        return index;
    }

    public int size() {
        return size;
    }

    public List<Feature> features() {
        return features;
    }

    public Feature feature(int featureIndex) {
        return features.get(featureIndex);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dimension other && other.index == this.index;
    }

    @Override
    public int hashCode() {
        return Objects.hash(index);
    }

    @Override
    public String toString() {
        return "dim" + (index + 1) + "(size=" + size + ")";
    }
}
