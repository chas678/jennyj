package com.burtleburtle.jenny.cli;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses jenny's {@code -w} grammar: an alternation of {@code <number>
 * <features>} blocks where {@code <number>} is a 1-based dimension index
 * and {@code <features>} is a non-empty run of a–z / A–Z feature names.
 *
 * <p>Example: {@code "1a2cd4ac"} represents "the first feature of dim 1
 * combined with the third or fourth feature of dim 2 combined with the
 * first or third feature of dim 4 is forbidden". The forbidden product is
 * Cartesian across each block.
 *
 * <p>Matches jenny.c:808–913 ({@code parse_w}).
 */
public final class WithoutParser {

    private WithoutParser() {
    }

    public static Without parse(String argument, List<Dimension> dimensions) {
        if (argument == null || argument.isEmpty()) {
            throw new IllegalArgumentException("-w argument is empty");
        }
        Map<Dimension, Set<Feature>> perDim = new LinkedHashMap<>();
        int i = 0;
        int n = argument.length();
        while (i < n) {
            int numStart = i;
            while (i < n && isDigit(argument.charAt(i))) {
                i++;
            }
            if (i == numStart) {
                throw new IllegalArgumentException(
                        "-w at offset " + i + ": expected a dimension number");
            }
            int oneBased;
            try {
                oneBased = Integer.parseInt(argument.substring(numStart, i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "-w at offset " + numStart + ": invalid number");
            }
            if (oneBased < 1 || oneBased > dimensions.size()) {
                throw new IllegalArgumentException(
                        "-w: dimension " + oneBased + " out of range [1, "
                                + dimensions.size() + "]");
            }
            Dimension dim = dimensions.get(oneBased - 1);
            if (perDim.containsKey(dim)) {
                throw new IllegalArgumentException(
                        "-w: dimension " + oneBased + " given twice in a single without");
            }

            int featStart = i;
            Set<Feature> featureSet = new LinkedHashSet<>();
            while (i < n && isFeatureChar(argument.charAt(i))) {
                int featureIndex = Feature.indexOfName(argument.charAt(i));
                if (featureIndex >= dim.size()) {
                    throw new IllegalArgumentException(
                            "-w: feature '" + argument.charAt(i)
                                    + "' does not exist in dimension " + oneBased);
                }
                featureSet.add(dim.feature(featureIndex));
                i++;
            }
            if (i == featStart) {
                throw new IllegalArgumentException(
                        "-w at offset " + i + ": expected at least one feature after dim "
                                + oneBased);
            }
            perDim.put(dim, featureSet);
        }
        return new Without(perDim);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isFeatureChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
