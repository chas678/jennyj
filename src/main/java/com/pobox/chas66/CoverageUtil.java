package com.pobox.chas66;

/**
 * Utility class for checking if TestRuns cover Combinations.
 * Centralizes the coverage checking logic to avoid duplication across
 * score calculators, initializers, and main solver code.
 */
public final class CoverageUtil {

    private CoverageUtil() {
        // Prevent instantiation
    }

    /**
     * Checks if a TestRun covers all dimensions required by a Combination.
     * Uses the O(1) assignmentMap for fast lookups.
     *
     * @param combo The combination to check coverage for
     * @param run The test run to check
     * @return true if the run covers the combination, false otherwise
     */
    public static boolean isRunCoveringCombo(Combination combo, TestRun run) {
        // Inactive runs cannot cover any combinations
        if (!run.getActive()) {
            return false;
        }

        var assignmentMap = run.getAssignmentMap();
        if (assignmentMap == null) {
            return false;
        }

        for (var entry : combo.getAssignments().entrySet()) {
            FeatureAssignment assignment = assignmentMap.get(entry.getKey());
            if (assignment == null || !assignment.getValue().equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
