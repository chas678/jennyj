package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Feature;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses uncovered tuples that are genuinely uncoverable (infeasible
 * under the withouts, per {@link FeasibilityChecker}) into minimal
 * infeasible cores, so one report line stands in for every uncovered tuple
 * that contains a given core. Mirrors the Go port's
 * {@code reportAndExclude}/{@code minimalCore} (cmd/jennygo/jenny.go,
 * jenny's deduced-restriction idea) and produces fewer output lines than
 * one-line-per-tuple.
 *
 * <p>Tuples that are merely unlucky solver misses (still feasible — some
 * legal complete test exists, the solver just didn't find/keep it) pass
 * through unchanged; that's a solver-completeness gap, out of scope here.
 */
public final class MinimalCoreCollapser {

    private MinimalCoreCollapser() {
    }

    public static List<AllowedTuple> collapse(List<AllowedTuple> uncovered, FeasibilityChecker checker) {
        List<AllowedTuple> result = new ArrayList<>();
        List<AllowedTuple> cores = new ArrayList<>();
        for (AllowedTuple tuple : uncovered) {
            if (checker.isFeasible(tuple.asMap())) {
                result.add(tuple);
                continue;
            }
            List<Feature> coreFeatures = checker.minimalCore(tuple.features());
            AllowedTuple core = coreFeatures.size() == tuple.size()
                    ? tuple
                    : new AllowedTuple(coreFeatures);
            addDistinctCore(cores, core);
        }
        result.addAll(cores);
        return result;
    }

    /**
     * Adds {@code core} unless an existing core already subsumes it (same
     * features, or a strict subset); drops any existing core that {@code core}
     * itself subsumes. Keeps {@code cores} free of duplicate/redundant
     * supersets regardless of the order tuples are processed in.
     */
    private static void addDistinctCore(List<AllowedTuple> cores, AllowedTuple core) {
        for (AllowedTuple existing : cores) {
            if (contains(existing, core)) {
                return;
            }
        }
        cores.removeIf(existing -> contains(core, existing));
        cores.add(core);
    }

    /** True iff every feature of {@code inner} appears in {@code outer}. */
    private static boolean contains(AllowedTuple outer, AllowedTuple inner) {
        return outer.features().containsAll(inner.features());
    }
}
