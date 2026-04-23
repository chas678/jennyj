package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.cli.WithoutParser;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Profile the greedy initializer to identify bottlenecks.
 */
class GreedyInitializerProfilingTest {

    @Test
    void profileGreedyInitializer() {
        System.out.println("\n=== PROFILING: Greedy Initializer Bottleneck Analysis ===\n");

        // Build jenny self-test problem
        List<Dimension> dimensions = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4)
        );

        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"
        };

        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }

        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, 3, withouts);

        System.out.println("Problem: " + dimensions.size() + " dims, " + tuples.size() + " tuples, " +
                withouts.size() + " withouts\n");

        // Run multiple times to get average
        int runs = 5;
        long totalTime = 0;
        int totalTests = 0;

        for (int run = 0; run < runs; run++) {
            Random rnd = new Random(run);
            long start = System.currentTimeMillis();
            List<Map<Dimension, Feature>> tests = GreedyInitializer.buildInitialTests(
                    dimensions, tuples, withouts, rnd);
            long elapsed = System.currentTimeMillis() - start;
            totalTime += elapsed;
            totalTests += tests.size();
            System.out.println("Run " + (run + 1) + ": " + tests.size() + " tests in " + elapsed + "ms");
        }

        double avgTime = totalTime / (double) runs;
        double avgTests = totalTests / (double) runs;
        System.out.println("\nAverage: " + String.format("%.1f", avgTests) + " tests in " +
                String.format("%.0f", avgTime) + "ms");
        System.out.println("Rate: " + String.format("%.1f", avgTests * 1000 / avgTime) + " tests/sec\n");

        // Analyze theoretical complexity
        int avgDimSize = dimensions.stream().mapToInt(Dimension::size).sum() / dimensions.size();
        System.out.println("=== Complexity Analysis ===");
        System.out.println("Per-test work:");
        System.out.println("  - Try " + 5 + " seed tuples");
        System.out.println("  - For each seed, fill " + dimensions.size() + " dimensions");
        System.out.println("  - For each dimension, try " + avgDimSize + " features");
        System.out.println("  - For each feature, check withouts: O(" + withouts.size() + ")");
        System.out.println("  - For each feature, count coverage: O(" + tuples.size() + ")");
        System.out.println("\nPer-test complexity: ~" + (5 * dimensions.size() * avgDimSize * tuples.size()) + " tuple coverage checks");
        System.out.println("Total for " + String.format("%.0f", avgTests) + " tests: ~" +
                String.format("%.0f", avgTests * 5 * dimensions.size() * avgDimSize * tuples.size()) + " operations");

        System.out.println("\n=== Profiling Complete ===\n");
    }
}
