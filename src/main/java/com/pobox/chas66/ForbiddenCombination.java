package com.pobox.chas66;

import lombok.Value;

import java.util.Map;
import java.util.Set;

/**
 * Represents a rule from the -w parameter.
 * Example: -w 1a2b means dimension 1 cannot be 'a' while dimension 2 is 'b'.
 */
@Value
public class ForbiddenCombination {
    // Map of Dimension ID -> Set of forbidden characters for that dimension in this rule
    Map<Integer, Set<Character>> restrictions;

    /**
     * Checks if a TestRun violates this specific "without" rule.
     * A violation occurs only if ALL dimensions in the restrictions map
     * match the values in the TestRun.
     */
    public boolean isViolatedBy(TestRun run) {
        if (!run.getActive()) return false;
        for (Map.Entry<Integer, Set<Character>> entry : restrictions.entrySet()) {
            FeatureAssignment assignment = run.getAssignmentForDimension(entry.getKey());
            if (assignment == null || !entry.getValue().contains(assignment.getValue())) {
                // If even one dimension doesn't match the forbidden char, the rule isn't violated
                return false;
            }
        }
        return true;
    }
}