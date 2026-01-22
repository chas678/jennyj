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

    /**
     * Checks if a partial row (with a new candidate feature) violates this specific "without" rule.
     * This is optimized to avoid creating a full temporary TestRun object.
     */
    public boolean isViolatedByPartialRow(Map<Integer, Character> partialRow, int newDimId, char newFeature) {
        for (Map.Entry<Integer, Set<Character>> restrictionEntry : restrictions.entrySet()) {
            int restrictedDimId = restrictionEntry.getKey();
            Set<Character> forbiddenChars = restrictionEntry.getValue();

            Character valueInPartialRow = partialRow.get(restrictedDimId);

            // If the current restriction matches the new candidate feature
            if (restrictedDimId == newDimId) {
                if (!forbiddenChars.contains(newFeature)) {
                    return false; // New feature doesn't match forbidden char, so this restriction is not violated
                }
            }
            // If the current restriction is already in the partial row
            else if (valueInPartialRow != null) {
                if (!forbiddenChars.contains(valueInPartialRow)) {
                    return false; // Existing feature doesn't match forbidden char, so this restriction is not violated
                }
            }
            // If the restricted dimension is not yet in partialRow and not the new candidate,
            // then this rule cannot be violated by the current partialRow + new candidate.
            else {
                return false;
            }
        }
        return true; // All restrictions were matched by either partialRow or new candidate
    }
}