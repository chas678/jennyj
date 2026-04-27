package com.burtleburtle.jenny.cli;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.TestCase;

import java.util.List;
import java.util.Map;

/**
 * Reproduces jenny's output format byte-for-byte: one test case per line,
 * leading space, {@code <dimIdx1-based><feature-letter>} pairs separated
 * by single spaces, trailing space, newline. See jenny.c:1094–1110.
 *
 * <p>Example line: {@code " 1a 2b 3a \n"}.
 */
public final class OutputFormatter {

    private OutputFormatter() {
    }

    public static String formatTest(TestCase tc, List<Dimension> dimensions) {
        Map<Dimension, Feature> assignments = tc.getFeaturesByDim();
        StringBuilder sb = new StringBuilder(dimensions.size() * 5);
        for (Dimension d : dimensions) {
            Feature f = assignments.get(d);
            if (f == null) {
                throw new IllegalStateException(
                        "TestCase " + tc.getId() + " has no feature assigned to "
                                + d + "; cannot format");
            }
            sb.append(' ').append(d.index() + 1).append(f.name());
        }
        sb.append(' ').append('\n');
        return sb.toString();
    }

    public static String formatUncoveredTupleLine(AllowedTuple tuple) {
        StringBuilder sb = new StringBuilder("Could not cover tuple");
        for (Feature f : tuple.features()) {
            sb.append(' ').append(f.dimension().index() + 1).append(f.name());
        }
        sb.append(' ').append('\n');
        return sb.toString();
    }
}
