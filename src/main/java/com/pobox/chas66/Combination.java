package com.pobox.chas66;

import java.util.Map;

/**
 * Represents a required combination (N-tuple) in the pairwise testing problem.
 * A combination specifies specific feature values for a subset of dimensions.
 *
 * For example, for 2-way testing on dimensions [0, 1, 2]:
 * - {0='a', 1='b'} represents the pair where dimension 0 is 'a' and dimension 1 is 'b'
 *
 * @param assignments Map of dimension ID -> required feature character
 */
public record Combination(Map<Integer, Character> assignments) {

    /**
     * Compact constructor with validation.
     */
    public Combination {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException("Combination assignments cannot be null or empty");
        }
    }

    /**
     * Backward compatibility getter for assignments.
     */
    public Map<Integer, Character> getAssignments() {
        return assignments;
    }
}
