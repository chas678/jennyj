package com.pobox.chas66;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@DisplayName("CombinationIterator Tests")
@Tag("fast")
class CombinationIteratorTest {

    @Nested
    @DisplayName("Basic Iteration Tests")
    class BasicIterationTests {

        @Test
        @DisplayName("Should generate correct number of pairwise combinations")
        void testPairwiseCount() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            // C(3,2) = 3 dimension pairs
            // Pair 1 (dims 0,1): 3x3 = 9 combinations
            // Pair 2 (dims 0,2): 3x2 = 6 combinations
            // Pair 3 (dims 1,2): 3x2 = 6 combinations
            // Total: 9 + 6 + 6 = 21

            int count = iterator.generateAll();
            assertThat("Incorrect pairwise combination count", count, is(equalTo(21)));
        }

        @Test
        @DisplayName("Should generate correct number of 3-way combinations")
        void test3WayCount() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2),
                new Dimension(3, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 3);

            // C(4,3) = 4 dimension triples
            // Each triple: 2x2x2 = 8 combinations
            // Total: 4 * 8 = 32

            int count = iterator.generateAll();
            assertThat("Incorrect 3-way combination count", count, is(equalTo(32)));
        }

        @Test
        @DisplayName("Should handle single dimension pair")
        void testSinglePair() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            // C(2,2) = 1 dimension pair
            // 3x2 = 6 combinations

            int count = iterator.generateAll();
            assertThat("Incorrect single pair count", count, is(equalTo(6)));
        }
    }

    @Nested
    @DisplayName("Lazy Generation Tests")
    class LazyGenerationTests {

        @Test
        @DisplayName("Should not generate all combinations until accessed")
        void testLazyBehavior() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 3)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            // No combinations should be cached yet
            assertThat("Combinations cached before iteration",
                    iterator.getCachedCount(), is(equalTo(0)));
            assertThat("Marked as fully generated before iteration",
                    iterator.isFullyGenerated(), is(false));
        }

        @Test
        @DisplayName("Should cache combinations as they are generated")
        void testCachingDuringIteration() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            Iterator<Combination> iter = iterator.iterator();

            // Read first combination
            iter.next();
            assertThat("First combination not cached",
                    iterator.getCachedCount(), is(greaterThanOrEqualTo(1)));

            // Read all combinations
            while (iter.hasNext()) {
                iter.next();
            }

            assertThat("Not fully generated after exhausting iterator",
                    iterator.isFullyGenerated(), is(true));
        }

        @Test
        @DisplayName("Should support multiple iterations over same data")
        void testMultipleIterations() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            // First iteration
            Set<Combination> firstPass = new HashSet<>();
            for (Combination combo : iterator) {
                firstPass.add(combo);
            }

            // Second iteration (should use cache)
            Set<Combination> secondPass = new HashSet<>();
            for (Combination combo : iterator) {
                secondPass.add(combo);
            }

            assertThat("Multiple iterations produced different results",
                    firstPass, is(equalTo(secondPass)));
            assertThat("Combination count changed between iterations",
                    firstPass.size(), is(equalTo(secondPass.size())));
        }
    }

    @Nested
    @DisplayName("Correctness Tests")
    class CorrectnessTests {

        @Test
        @DisplayName("Should generate all valid pairwise combinations")
        void testPairwiseCompleteness() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),  // Features: a, b
                new Dimension(1, 2)   // Features: a, b
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);
            Set<Combination> combinations = iterator.toSet(true);

            // Expected: {0:a,1:a}, {0:a,1:b}, {0:b,1:a}, {0:b,1:b}
            assertThat("Wrong number of combinations", combinations.size(), is(equalTo(4)));

            // Verify all expected combinations exist
            assertThat("Missing combination 0:a,1:a",
                    containsCombination(combinations, Map.of(0, 'a', 1, 'a')), is(true));
            assertThat("Missing combination 0:a,1:b",
                    containsCombination(combinations, Map.of(0, 'a', 1, 'b')), is(true));
            assertThat("Missing combination 0:b,1:a",
                    containsCombination(combinations, Map.of(0, 'b', 1, 'a')), is(true));
            assertThat("Missing combination 0:b,1:b",
                    containsCombination(combinations, Map.of(0, 'b', 1, 'b')), is(true));
        }

        @Test
        @DisplayName("Should generate combinations with correct dimension IDs")
        void testDimensionIdsCorrect() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);
            Set<Combination> combinations = iterator.toSet(true);

            for (Combination combo : combinations) {
                Map<Integer, Character> assignments = combo.getAssignments();

                // Each combination should have exactly 2 dimensions (pairwise)
                assertThat("Wrong number of dimensions in combination",
                        assignments.size(), is(equalTo(2)));

                // All dimension IDs should be valid (0, 1, or 2)
                for (Integer dimId : assignments.keySet()) {
                    assertThat("Invalid dimension ID",
                            dimId, is(allOf(greaterThanOrEqualTo(0), lessThan(3))));
                }
            }
        }

        @Test
        @DisplayName("Should respect dimension subset size")
        void testNWaySubsetSize() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2),
                new Dimension(3, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 3);
            Set<Combination> combinations = iterator.toSet(true);

            // Every combination should involve exactly 3 dimensions
            for (Combination combo : combinations) {
                assertThat("Combination doesn't have exactly 3 dimensions",
                        combo.getAssignments().size(), is(equalTo(3)));
            }
        }

        private boolean containsCombination(Set<Combination> combinations, Map<Integer, Character> expected) {
            return combinations.stream()
                    .anyMatch(c -> c.getAssignments().equals(expected));
        }
    }

    @Nested
    @DisplayName("Memory Efficiency Tests")
    class MemoryEfficiencyTests {

        @Test
        @DisplayName("Should provide accurate memory statistics")
        void testMemoryStats() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            String statsBefore = iterator.getMemoryStats();
            assertThat("Stats should show 0 cached before generation",
                    statsBefore, containsString("cached=0"));

            iterator.generateAll();

            String statsAfter = iterator.getMemoryStats();
            assertThat("Stats should show fullyGenerated=true after generation",
                    statsAfter, containsString("fullyGenerated=true"));
            assertThat("Stats should show cached count after generation",
                    statsAfter, containsString("cached=9"));
        }

        @Test
        @DisplayName("Should estimate total combinations accurately")
        void testEstimation() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            long estimate = iterator.estimateTotalCount();
            int actual = iterator.generateAll();

            // Estimate should be close to actual (within reason for small problems)
            // C(3,2) = 3, each with 2x2 = 4 combinations = 12 total
            assertThat("Estimate far from actual", estimate, is(greaterThanOrEqualTo((long) actual)));
        }

        @Test
        @DisplayName("Should not duplicate combinations in cache")
        void testNoDuplicatesInCache() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);
            List<Combination> combinations = iterator.toList(true);

            // Check for duplicates using Set
            Set<Combination> uniqueCombos = new HashSet<>(combinations);
            assertThat("Iterator produced duplicate combinations",
                    uniqueCombos.size(), is(equalTo(combinations.size())));
        }
    }

    @Nested
    @DisplayName("Conversion Tests")
    class ConversionTests {

        @Test
        @DisplayName("toSet should generate all combinations when requested")
        void testToSetFullGeneration() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);
            Set<Combination> combinations = iterator.toSet(true);

            assertThat("toSet(true) should generate all combinations",
                    iterator.isFullyGenerated(), is(true));
            assertThat("Set size doesn't match cached count",
                    combinations.size(), is(equalTo(iterator.getCachedCount())));
        }

        @Test
        @DisplayName("toList should maintain order")
        void testToListOrder() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            // Generate twice to ensure order is consistent
            List<Combination> list1 = iterator.toList(true);
            List<Combination> list2 = iterator.toList(false); // Should use cache

            assertThat("toList order not consistent between calls",
                    list1, is(equalTo(list2)));
        }

        @Test
        @DisplayName("toSet without full generation should return only cached")
        void testToSetPartialCache() {
            List<Dimension> dimensions = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2)
            );

            CombinationIterator iterator = new CombinationIterator(dimensions, 2);

            // Partially generate
            Iterator<Combination> iter = iterator.iterator();
            iter.next();
            iter.next();

            Set<Combination> partialSet = iterator.toSet(false);

            assertThat("toSet(false) should only return cached combinations",
                    partialSet.size(), is(equalTo(iterator.getCachedCount())));
            assertThat("toSet(false) should not trigger full generation",
                    iterator.isFullyGenerated(), is(false));
        }
    }
}
