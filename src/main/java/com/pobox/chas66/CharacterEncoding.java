package com.pobox.chas66;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Utility class for converting between feature indices and character representations.
 * Characters encode features as: a-z (indices 0-25), then A-Z (indices 26-51).
 *
 * This class caches character ranges to avoid repeated allocations, significantly
 * improving performance for value range providers.
 */
public final class CharacterEncoding {

    private CharacterEncoding() {
        // Prevent instantiation
    }

    /**
     * Cache for character ranges, indexed by (size - 1).
     * Pre-allocated for all valid sizes (1-52).
     */
    private static final List<Character>[] CACHED_RANGES = new List[52];

    /**
     * Converts a feature index to its character representation.
     *
     * @param index Feature index (0-51)
     * @return Character representation (a-z for 0-25, A-Z for 26-51)
     */
    public static char indexToChar(int index) {
        if (index < 0 || index > 51) {
            throw new IllegalArgumentException("Index must be 0-51, got: " + index);
        }
        return (index < 26) ? (char) ('a' + index) : (char) ('A' + (index - 26));
    }

    /**
     * Returns all valid characters for a dimension of given size as an immutable List.
     * Results are cached for performance.
     *
     * @param size Dimension size (1-52)
     * @return Immutable list of valid characters
     */
    public static List<Character> getRangeForSize(int size) {
        if (size <= 0 || size > 52) {
            throw new IllegalArgumentException("Dimension size must be 1-52, got: " + size);
        }

        int cacheIndex = size - 1;
        if (CACHED_RANGES[cacheIndex] == null) {
            CACHED_RANGES[cacheIndex] = IntStream.range(0, size)
                    .mapToObj(CharacterEncoding::indexToChar)
                    .toList();
        }
        return CACHED_RANGES[cacheIndex];
    }

    /**
     * Returns all valid characters for a dimension of given size as a Set.
     * Maintains insertion order via LinkedHashSet.
     *
     * @param size Dimension size (1-52)
     * @return Set of valid characters in order
     */
    public static Set<Character> getRangeAsSet(int size) {
        return new LinkedHashSet<>(getRangeForSize(size));
    }
}
