package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.cli.WithoutParser;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.CoverageUtil;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent verification of the greedy initializer output on the jenny
 * self-test (n=3, the constrained 12-dim instance). Confirms the three
 * invariants the SP5 spike must uphold, WITHOUT going through the solver:
 * every row is complete, no row violates a without, and every allowed tuple
 * is covered by at least one row.
 */
class GreedyParityIT {

    @Test
    void greedyOutputIsComplete_legal_andCovers() {
        List<Dimension> dimensions = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4));
        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"};

        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, 3, withouts);

        int totalRows = 0;
        int totalViolations = 0;
        // Sweep several seeds; every one must be violation-free and cover all.
        for (int seed = 0; seed < 5; seed++) {
            List<Map<Dimension, Feature>> rows = GreedyInitializer.buildInitialTests(
                    dimensions, tuples, withouts, new Random(seed));
            totalRows += rows.size();

            for (Map<Dimension, Feature> row : rows) {
                // (1) complete: every dimension assigned.
                assertEquals(dimensions.size(), row.size(),
                        "row is not complete: " + row);
                // (2) legal: violates no without.
                for (Without w : withouts) {
                    if (w.matches(row)) {
                        totalViolations++;
                    }
                }
            }

            // (3) coverage: every allowed tuple covered by some row.
            long uncovered = tuples.stream()
                    .filter(t -> rows.stream().noneMatch(r -> CoverageUtil.covers(r, t)))
                    .count();
            assertEquals(0, uncovered,
                    "seed " + seed + ": greedy left " + uncovered + " tuples uncovered");
            System.out.printf("seed %d: rows=%d%n", seed, rows.size());
        }

        System.out.printf("GreedyParityIT: avg rows=%.1f, without-violations=%d%n",
                totalRows / 5.0, totalViolations);
        assertEquals(0, totalViolations,
                "greedy emitted " + totalViolations + " without-violating rows");
    }
}
