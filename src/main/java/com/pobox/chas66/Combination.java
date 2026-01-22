package com.pobox.chas66;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a required combination (N-tuple) in the pairwise testing problem.
 * A combination specifies specific feature values for a subset of dimensions.
 *
 * For example, for 2-way testing on dimensions [0, 1, 2]:
 * - {0='a', 1='b'} represents the pair where dimension 0 is 'a' and dimension 1 is 'b'
 *
 * This class caches its hashcode at construction time since Combination is immutable
 * and used extensively as a HashMap key. This provides O(1) hashcode computation
 * instead of O(n) where n is the number of dimensions in the combination.
 */
public final class Combination {
    private final Map<Integer, Character> assignments;
    private final int cachedHash;

    /**
     * Creates a new Combination with the given assignments.
     * The hashcode is pre-computed and cached at construction time.
     *
     * @param assignments Map of dimension ID -> required feature character
     * @throws IllegalArgumentException if assignments is null or empty
     */
    public Combination(Map<Integer, Character> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException("Combination assignments cannot be null or empty");
        }

        // Make a defensive immutable copy to ensure true immutability
        this.assignments = Map.copyOf(assignments);

        // Pre-compute hashcode ONCE at construction time
        // This is called thousands of times in HashMap operations during solving
        int hash = 1;
        for (Map.Entry<Integer, Character> entry : this.assignments.entrySet()) {
            hash = 31 * hash + entry.getKey();
            hash = 31 * hash + entry.getValue();
        }
        this.cachedHash = hash;
    }

    /**
     * Returns the dimension assignments for this combination.
     *
     * @return Immutable map of dimension ID -> required feature character
     */
    public Map<Integer, Character> getAssignments() {
        return assignments;
    }

    /**
     * Returns the pre-computed cached hashcode.
     * This is O(1) instead of O(n) for the default Map hashcode implementation.
     *
     * @return The cached hashcode
     */
    @Override
    public int hashCode() {
        return cachedHash;
    }

    /**
     * Compares this combination with another for equality.
     * Two combinations are equal if they have the same assignments.
     *
     * @param obj The object to compare with
     * @return true if equal, false otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Combination)) {
            return false;
        }
        Combination other = (Combination) obj;
        return Objects.equals(this.assignments, other.assignments);
    }

    /**
     * Returns a string representation of this combination.
     *
     * @return String representation showing the assignments
     */
    @Override
    public String toString() {
        return "Combination[assignments=" + assignments + "]";
    }
}
