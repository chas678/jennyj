package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.CoverageUtil;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Greedy construction algorithm to build initial test suite that covers all tuples
 * while respecting without constraints. Mimics C jenny's greedy set cover approach.
 */
public class GreedyInitializer {

    /**
     * Build initial test cases using greedy set cover algorithm.
     * Returns a list of test cases that should cover most/all tuples while respecting withouts.
     */
    public static List<Map<Dimension, Feature>> buildInitialTests(
            List<Dimension> dimensions,
            List<AllowedTuple> tuples,
            List<Without> withouts,
            Random random) {

        List<Map<Dimension, Feature>> tests = new ArrayList<>();
        // Order tuples by rarity (least-common features first) so the greedy
        // search seeds from the hardest-to-cover combinations rather than
        // picking arbitrary ones. A LinkedHashSet preserves the rarity order
        // for the random-seed step in buildGreedyTest.
        Set<AllowedTuple> uncovered = orderByRarity(tuples);

        // Build tests greedily until all tuples covered or max iterations reached
        int maxTests = Math.min(500, tuples.size() * 2);
        int maxIterations = tuples.size() * 50; // Many more iterations for hard problems
        int iterations = 0;
        int stuckCount = 0;
        int lastUncoveredSize = uncovered.size();

        while (!uncovered.isEmpty() && tests.size() < maxTests && iterations < maxIterations) {
            iterations++;

            // Try to build a test that covers many uncovered tuples
            Map<Dimension, Feature> bestTest = buildGreedyTest(
                    dimensions, new ArrayList<>(uncovered), withouts, tests, random);

            if (bestTest == null) {
                // Can't build any valid test - try with more randomization
                stuckCount++;
                if (stuckCount > 20) {
                    break; // Really stuck
                }
                continue;
            }

            // Add this test
            tests.add(bestTest);

            // Mark tuples as covered
            uncovered.removeIf(tuple -> CoverageUtil.covers(bestTest, tuple));

            // Check if we're making progress
            if (uncovered.size() < lastUncoveredSize) {
                stuckCount = 0; // Reset stuck counter when making progress
                lastUncoveredSize = uncovered.size();
            } else {
                stuckCount++;
                if (stuckCount > 20) {
                    break; // Not making progress, stop trying
                }
            }
        }

        return tests;
    }

    /**
     * Build one test greedily by trying to cover as many uncovered tuples as possible.
     */
    private static Map<Dimension, Feature> buildGreedyTest(
            List<Dimension> dimensions,
            List<AllowedTuple> uncovered,
            List<Without> withouts,
            List<Map<Dimension, Feature>> existingTests,
            Random random) {

        // Start with a random uncovered tuple as seed
        if (uncovered.isEmpty()) {
            return null;
        }

        // Try multiple seeds to find the best one
        Map<Dimension, Feature> bestTest = null;
        int bestCoverage = 0;

        int attempts = Math.min(5, uncovered.size());
        for (int attempt = 0; attempt < attempts; attempt++) {
            AllowedTuple seed = uncovered.get(random.nextInt(uncovered.size()));
            Map<Dimension, Feature> test = new HashMap<>();

            // Fill in dimensions from seed tuple
            for (var entry : seed.asMap().entrySet()) {
                test.put(entry.getKey(), entry.getValue());
            }

        // Fill remaining dimensions trying to maximize coverage
        for (Dimension d : dimensions) {
            if (test.containsKey(d)) {
                continue; // Already set by seed tuple
            }

            // Try each feature for this dimension, pick one that covers most tuples
            Feature bestFeature = null;
            int bestFeatureCoverage = -1;

            for (int f = 0; f < d.size(); f++) {
                Feature candidate = d.feature(f);
                test.put(d, candidate);

                // Check if this violates any withouts
                if (violatesWithouts(test, withouts)) {
                    test.remove(d);
                    continue;
                }

                // Count how many uncovered tuples this would cover
                int coverage = 0;
                for (AllowedTuple tuple : uncovered) {
                    if (CoverageUtil.covers(test, tuple)) {
                        coverage++;
                    }
                }

                if (coverage > bestFeatureCoverage) {
                    bestFeatureCoverage = coverage;
                    bestFeature = candidate;
                }

                test.remove(d);
            }

            if (bestFeature != null) {
                test.put(d, bestFeature);
            } else {
                // No valid feature found - pick first one
                test.put(d, d.feature(0));
            }
        }

            // Final check: does this test violate withouts?
            if (violatesWithouts(test, withouts)) {
                continue; // Try next seed
            }

            // Count coverage
            int coverage = 0;
            for (AllowedTuple tuple : uncovered) {
                if (CoverageUtil.covers(test, tuple)) {
                    coverage++;
                }
            }

            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                bestTest = new HashMap<>(test);
            }
        }

        return bestTest;
    }

    private static boolean violatesWithouts(Map<Dimension, Feature> test, List<Without> withouts) {
        for (Without without : withouts) {
            if (without.matches(test)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reorder tuples by rarity score so the search prioritises hard-to-cover
     * combinations first. Score = sum of feature frequencies across all
     * tuples; a low score means the tuple's features show up rarely overall
     * (and therefore are constrained by fewer other tuples), so covering it
     * early avoids painting ourselves into a corner.
     */
    private static Set<AllowedTuple> orderByRarity(List<AllowedTuple> tuples) {
        Map<Feature, Integer> frequency = new HashMap<>();
        for (AllowedTuple t : tuples) {
            for (Feature f : t.features()) {
                frequency.merge(f, 1, Integer::sum);
            }
        }
        List<AllowedTuple> ordered = new ArrayList<>(tuples);
        ordered.sort(Comparator.comparingInt(t -> rarityScore(t, frequency)));
        return new LinkedHashSet<>(ordered);
    }

    private static int rarityScore(AllowedTuple tuple, Map<Feature, Integer> frequency) {
        int score = 0;
        for (Feature f : tuple.features()) {
            score += frequency.getOrDefault(f, 0);
        }
        return score;
    }
}
