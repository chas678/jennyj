package com.burtleburtle.jenny.cli;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses jenny test file format: one test per line, space-separated
 * {@code <dim><feature>} tokens (1-indexed dimensions), terminated by ".".
 *
 * <p>Example file:
 * <pre>
 *  1a 2b 3c
 *  1b 2a 3c
 * .
 * </pre>
 *
 * <p>See jenny.c:427–471 ({@code input_tests}) for the C reference.
 */
public final class TestFileParser {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)([a-zA-Z])");

    private TestFileParser() {
    }

    /**
     * Parses test cases from a file. Returns a list of maps, where each map
     * represents one test case: {@code Dimension -> Feature}.
     *
     * @param filePath path to file, or "-" for stdin
     * @param dimensions the dimension schema (needed to resolve feature indices)
     * @return list of test cases as dimension->feature maps
     * @throws IOException if file cannot be read
     * @throws IllegalArgumentException if file format is invalid
     */
    public static List<Map<Dimension, Feature>> parseTestFile(
            String filePath, List<Dimension> dimensions) throws IOException {

        if (filePath == null || filePath.isEmpty()) {
            return List.of();
        }

        if ("-".equals(filePath)) {
            return parseFromStream(System.in, dimensions);
        } else {
            Path path = Path.of(filePath);
            try (InputStream in = Files.newInputStream(path)) {
                return parseFromStream(in, dimensions);
            }
        }
    }

    private static List<Map<Dimension, Feature>> parseFromStream(
            InputStream inputStream, List<Dimension> dimensions) throws IOException {

        List<Map<Dimension, Feature>> tests = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Terminator
                if (".".equals(line.trim())) {
                    break;
                }

                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Parse test line
                Map<Dimension, Feature> test = parseTestLine(line, dimensions, lineNumber);
                if (test != null) {
                    tests.add(test);
                }
            }
        }

        return tests;
    }

    private static Map<Dimension, Feature> parseTestLine(
            String line, List<Dimension> dimensions, int lineNumber) {

        Map<Dimension, Feature> assignments = new LinkedHashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(line);

        while (matcher.find()) {
            int dimIndex1Based = Integer.parseInt(matcher.group(1));
            char featureName = matcher.group(2).charAt(0);

            // Convert to 0-based index
            int dimIndex = dimIndex1Based - 1;

            if (dimIndex < 0 || dimIndex >= dimensions.size()) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": dimension index " + dimIndex1Based
                                + " out of range (expected 1-" + dimensions.size() + ")");
            }

            Dimension dimension = dimensions.get(dimIndex);

            // Find the feature by name
            int featureIndex = Feature.indexOfName(featureName);
            if (featureIndex >= dimension.size()) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": feature '" + featureName
                                + "' not valid for dimension " + dimIndex1Based
                                + " (size " + dimension.size() + ")");
            }

            Feature feature = dimension.feature(featureIndex);

            if (assignments.containsKey(dimension)) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": duplicate assignment for dimension "
                                + dimIndex1Based);
            }

            assignments.put(dimension, feature);
        }

        // Verify all dimensions are assigned
        if (assignments.size() != dimensions.size()) {
            throw new IllegalArgumentException(
                    "Line " + lineNumber + ": expected " + dimensions.size()
                            + " dimension assignments, found " + assignments.size());
        }

        return assignments;
    }
}
