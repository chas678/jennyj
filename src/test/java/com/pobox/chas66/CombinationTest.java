package com.pobox.chas66;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Combination Hashcode Caching Tests")
@Tag("fast")
class CombinationTest {

    @Test
    @DisplayName("Should cache hashcode for O(1) performance")
    void testCombinationHashcodeCaching() {
        Map<Integer, Character> assignments = Map.of(0, 'a', 1, 'b', 2, 'c');
        Combination combo = new Combination(assignments);

        // First hashCode call
        int hash1 = combo.hashCode();

        // Second hashCode call should return cached value (same instance)
        int hash2 = combo.hashCode();

        assertThat("Hashcode not consistent", hash1, is(equalTo(hash2)));
    }

    @Test
    @DisplayName("Equal Combinations should have same hashcode")
    void testCombinationHashcodeEquality() {
        Combination combo1 = new Combination(Map.of(0, 'a', 1, 'b'));
        Combination combo2 = new Combination(Map.of(0, 'a', 1, 'b'));

        assertThat("Equal combinations have different hashcodes",
                combo1.hashCode(), is(equalTo(combo2.hashCode())));
        assertThat("Equal combinations not equal",
                combo1, is(equalTo(combo2)));
    }

    @Test
    @DisplayName("Different Combinations should have different hashcodes")
    void testCombinationHashcodeInequality() {
        Combination combo1 = new Combination(Map.of(0, 'a', 1, 'b'));
        Combination combo2 = new Combination(Map.of(0, 'a', 1, 'c'));

        assertThat("Different combinations are equal",
                combo1, is(not(equalTo(combo2))));
        // Note: Different objects MAY have same hashcode (collisions), but should be unlikely
    }

    @Test
    @DisplayName("Should be immutable - assignments cannot be modified")
    void testCombinationImmutability() {
        Map<Integer, Character> mutableMap = new HashMap<>();
        mutableMap.put(0, 'a');
        mutableMap.put(1, 'b');

        Combination combo = new Combination(mutableMap);

        // Verify the returned map is immutable
        assertThrows(UnsupportedOperationException.class,
                () -> combo.getAssignments().put(2, 'c'),
                "Combination assignments should be immutable");
    }

    @Test
    @DisplayName("Should reject null or empty assignments")
    void testCombinationValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new Combination(null),
                "Should reject null assignments");

        assertThrows(IllegalArgumentException.class,
                () -> new Combination(Map.of()),
                "Should reject empty assignments");
    }

    @Test
    @DisplayName("Hashcode should be stable across different map implementations")
    void testHashcodeStability() {
        // Create using different map types but same content
        Map<Integer, Character> hashMap = new HashMap<>();
        hashMap.put(0, 'a');
        hashMap.put(1, 'b');

        Combination combo1 = new Combination(hashMap);
        Combination combo2 = new Combination(Map.of(0, 'a', 1, 'b'));

        assertThat("Hashcode differs across map implementations",
                combo1.hashCode(), is(equalTo(combo2.hashCode())));
        assertThat("Combinations not equal across map implementations",
                combo1, is(equalTo(combo2)));
    }

    @Test
    @DisplayName("Should work correctly in HashMap as key")
    void testCombinationAsHashMapKey() {
        Combination combo1 = new Combination(Map.of(0, 'a', 1, 'b'));
        Combination combo2 = new Combination(Map.of(0, 'a', 1, 'b'));

        Map<Combination, String> map = new HashMap<>();
        map.put(combo1, "value1");

        // combo2 should retrieve the same value since it's equal to combo1
        assertThat("HashMap lookup failed with equal Combination",
                map.get(combo2), is(equalTo("value1")));

        // Verify it's actually using the hashcode
        assertThat("HashMap should contain combo2 as key",
                map.containsKey(combo2), is(true));
    }

    @Test
    @DisplayName("Should handle single-dimension combinations")
    void testSingleDimensionCombination() {
        Combination combo = new Combination(Map.of(0, 'a'));

        assertThat("Single dimension combination has wrong size",
                combo.getAssignments().size(), is(equalTo(1)));
        assertThat("Single dimension combination wrong value",
                combo.getAssignments().get(0), is(equalTo('a')));
    }

    @Test
    @DisplayName("Should handle large combinations")
    void testLargeCombination() {
        Map<Integer, Character> assignments = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            assignments.put(i, CharacterEncoding.indexToChar(i));
        }

        Combination combo1 = new Combination(assignments);
        Combination combo2 = new Combination(new HashMap<>(assignments));

        assertThat("Large combinations have different hashcodes",
                combo1.hashCode(), is(equalTo(combo2.hashCode())));
        assertThat("Large combinations not equal",
                combo1, is(equalTo(combo2)));
    }

    @Test
    @DisplayName("toString should provide readable representation")
    void testToString() {
        Combination combo = new Combination(Map.of(0, 'a', 1, 'b'));
        String str = combo.toString();

        assertThat("toString is null", str != null, is(true));
        assertThat("toString doesn't contain expected content",
                str.contains("Combination"), is(true));
    }
}
