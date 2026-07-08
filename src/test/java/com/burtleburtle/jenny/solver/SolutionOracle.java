package com.burtleburtle.jenny.solver;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ground-truth verification for a solved {@link JennySolution}, independent of
 * the solver's own {@code HardSoftScore}.
 *
 * <p>The score is what the solver optimizes against and is a fast, convenient
 * signal, but it is not proof: a bug in {@link JennyConstraintProvider} or in
 * incremental score maintenance could report {@code hardScore == 0} for a
 * solution that actually leaves a tuple uncovered or breaks a without. This
 * class recomputes both properties directly from {@link TestCase} cell state
 * — the same ground truth jenny.c's own {@code confirm()} pass checks —
 * so tests can assert against reality rather than against the score's opinion
 * of reality.
 *
 * <p>Shared by {@link JennyBeatsBenchmarkIT} and {@link SolutionVerificationTest}
 * so both verify against one oracle rather than duplicating (and risking
 * divergent) recomputation logic.
 */
final class SolutionOracle {

    private SolutionOracle() {
    }

    static List<TestCase> activeTests(JennySolution solution) {
        return solution.getTestCases().stream().filter(TestCase::isActiveFlag).toList();
    }

    /** Allowed tuples not covered by any active test case. Empty means full coverage. */
    static Set<AllowedTuple> uncoveredTuples(JennySolution solution) {
        List<TestCase> active = activeTests(solution);
        Set<AllowedTuple> uncovered = new LinkedHashSet<>();
        for (AllowedTuple tuple : solution.getAllowedTuples()) {
            boolean covered = active.stream().anyMatch(tc -> tc.coversTuple(tuple));
            if (!covered) {
                uncovered.add(tuple);
            }
        }
        return uncovered;
    }

    /**
     * Human-readable description of every (active test, without) pair that
     * violates a constraint. Empty means no active row breaks any without.
     */
    static List<String> withoutViolations(JennySolution solution) {
        List<String> violations = new ArrayList<>();
        for (TestCase tc : activeTests(solution)) {
            for (Without without : solution.getWithouts()) {
                if (without.matches(tc.getFeaturesByDim())) {
                    violations.add("Test " + tc.getId() + " violates " + without);
                }
            }
        }
        return violations;
    }
}
