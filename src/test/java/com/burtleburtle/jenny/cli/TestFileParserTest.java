package com.burtleburtle.jenny.cli;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TestFileParser}.
 */
class TestFileParserTest {

    @Test
    void parseSimpleTest() throws IOException {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2));

        String input = " 1a 2b 3a \n.\n";
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        // Redirect stdin BEFORE calling parseTestFile("-", ...) — without
        // this, the parser blocks on the surefire JVM's real (empty) stdin.
        List<Map<Dimension, Feature>> tests;
        InputStream originalIn = System.in;
        try {
            System.setIn(in);
            tests = TestFileParser.parseTestFile("-", dims);
        } finally {
            System.setIn(originalIn);
        }

        assertEquals(1, tests.size());
        Map<Dimension, Feature> test = tests.get(0);
        assertEquals(dims.get(0).feature(0), test.get(dims.get(0))); // 1a
        assertEquals(dims.get(1).feature(1), test.get(dims.get(1))); // 2b
        assertEquals(dims.get(2).feature(0), test.get(dims.get(2))); // 3a
    }

    @Test
    void parseMultipleTests() throws IOException {
        List<Dimension> dims = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3));

        String input = " 1a 2b \n 1c 2a \n.\n";
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        InputStream originalIn = System.in;
        try {
            System.setIn(in);
            List<Map<Dimension, Feature>> tests = TestFileParser.parseTestFile("-", dims);

            assertEquals(2, tests.size());

            // First test: 1a 2b
            assertEquals(dims.get(0).feature(0), tests.get(0).get(dims.get(0)));
            assertEquals(dims.get(1).feature(1), tests.get(0).get(dims.get(1)));

            // Second test: 1c 2a
            assertEquals(dims.get(0).feature(2), tests.get(1).get(dims.get(0)));
            assertEquals(dims.get(1).feature(0), tests.get(1).get(dims.get(1)));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void parseEmptyFile() throws IOException {
        List<Dimension> dims = List.of(new Dimension(0, 2));

        InputStream in = new ByteArrayInputStream(".\n".getBytes(StandardCharsets.UTF_8));
        InputStream originalIn = System.in;
        try {
            System.setIn(in);
            List<Map<Dimension, Feature>> tests = TestFileParser.parseTestFile("-", dims);
            assertTrue(tests.isEmpty());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void parseInvalidDimensionIndex() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2));

        String input = " 1a 3b \n.\n"; // 3b is invalid (only 2 dimensions)
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        InputStream originalIn = System.in;
        try {
            System.setIn(in);
            assertThrows(IllegalArgumentException.class,
                    () -> TestFileParser.parseTestFile("-", dims));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void parseInvalidFeature() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),  // Only features 'a' and 'b'
                new Dimension(1, 2));

        String input = " 1c 2a \n.\n"; // 1c is invalid (dimension 1 only has 2 features)
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        InputStream originalIn = System.in;
        try {
            System.setIn(in);
            assertThrows(IllegalArgumentException.class,
                    () -> TestFileParser.parseTestFile("-", dims));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void parseMissingDimension() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2));

        String input = " 1a 2b \n.\n"; // Missing dimension 3
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        InputStream originalIn = System.in;
        try {
            System.setIn(in);
            assertThrows(IllegalArgumentException.class,
                    () -> TestFileParser.parseTestFile("-", dims));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void parseNullOrEmptyPath() throws IOException {
        List<Dimension> dims = List.of(new Dimension(0, 2));

        List<Map<Dimension, Feature>> tests1 = TestFileParser.parseTestFile(null, dims);
        assertTrue(tests1.isEmpty());

        List<Map<Dimension, Feature>> tests2 = TestFileParser.parseTestFile("", dims);
        assertTrue(tests2.isEmpty());
    }
}
