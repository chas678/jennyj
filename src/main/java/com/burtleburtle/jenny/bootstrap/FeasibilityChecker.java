package com.burtleburtle.jenny.bootstrap;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Heuristically decides whether some without-obeying complete assignment
 * exists that pins a given partial combination of (dimension, feature)
 * pairs. Used to tell a genuinely uncoverable tuple (no legal completion
 * exists) apart from a tuple the solver simply failed to cover in the time
 * budget, and to shrink an uncoverable tuple to a minimal infeasible core.
 *
 * <p>Ports the Go implementation's random-probe-plus-repair approach
 * ({@code feasible}/{@code trySatisfy}/{@code obeyWithouts} in
 * cmd/jennygo/jenny.go) rather than an exact SAT-style search: cheap, and
 * good enough for reporting, at the cost of possible false negatives (see
 * {@link #isFeasible}).
 */
public final class FeasibilityChecker {

    /** Random restarts per feasibility probe. Mirrors Go's {@code feasProbe}. */
    private static final int PROBE_ITERATIONS = 48;

    /** Hill-climb passes tolerated without progress before giving up on a restart. */
    private static final int MAX_NO_PROGRESS = 2;

    private final List<Dimension> dimensions;
    private final List<Without> withouts;
    private final Map<Dimension, List<Without>> byDim;
    private final Random rng;

    public FeasibilityChecker(List<Dimension> dimensions, List<Without> withouts, Random rng) {
        this.dimensions = dimensions;
        this.withouts = withouts;
        this.rng = rng;
        this.byDim = new LinkedHashMap<>();
        for (Dimension d : dimensions) {
            byDim.put(d, new ArrayList<>());
        }
        for (Without w : withouts) {
            for (Dimension d : w.dimensions()) {
                byDim.get(d).add(w);
            }
        }
    }

    /**
     * Reports (heuristically) whether some restriction-obeying complete
     * assignment exists that agrees with {@code pinned} on every dimension it
     * specifies. A {@code false} result is trustworthy (backed by an
     * exhaustive-enough probe budget for reporting purposes) but, like the Go
     * port's {@code feasible}, this can have false negatives on adversarial
     * inputs — it is a heuristic, not an exact solver.
     */
    public boolean isFeasible(Map<Dimension, Feature> pinned) {
        if (withouts.isEmpty()) {
            return true;
        }
        return trySatisfy(pinned, PROBE_ITERATIONS);
    }

    private boolean trySatisfy(Map<Dimension, Feature> pinned, int iters) {
        for (int iter = 0; iter < iters; iter++) {
            Map<Dimension, Feature> assignment = new LinkedHashMap<>(pinned);
            for (Dimension d : dimensions) {
                if (!assignment.containsKey(d)) {
                    assignment.put(d, d.feature(rng.nextInt(d.size())));
                }
            }
            if (obeyWithouts(pinned, assignment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hill-climbs the mutable (non-pinned) dimensions to drive the number of
     * violated withouts to zero, mirroring the Go port's {@code obeyWithouts}.
     */
    private boolean obeyWithouts(Map<Dimension, Feature> pinned, Map<Dimension, Feature> assignment) {
        if (violatedCount(assignment) == 0) {
            return true;
        }
        List<Dimension> mutable = new ArrayList<>();
        for (Dimension d : dimensions) {
            if (!pinned.containsKey(d) && !byDim.get(d).isEmpty()) {
                mutable.add(d);
            }
        }
        if (mutable.isEmpty()) {
            // No dimension left to vary (either fully pinned, or every
            // remaining dimension is untouched by any without) yet the
            // violation above is nonzero: this is unfixable. Without this
            // check the loop below defaults its "allZero" flag to true and
            // never actually re-examines the assignment, wrongly reporting
            // feasible.
            return false;
        }
        int noProgress = 0;
        while (noProgress < MAX_NO_PROGRESS) {
            Collections.shuffle(mutable, rng);
            boolean progressed = false;
            for (Dimension d : mutable) {
                // Greedily minimize the violation count as seen from this one
                // dimension's own withouts (min-conflicts style local move).
                // This is only a per-dimension proxy: a without can be
                // violated purely through OTHER (pinned or mutable)
                // dimensions and never show up in d's own touching count, so
                // this cannot be used on its own to decide global
                // satisfaction — see the violatedCount(assignment) check
                // below, which is the ground truth.
                int count = countTouching(assignment, d);
                List<Feature> best = new ArrayList<>();
                int bestCount = count + 1;
                for (Feature f : d.features()) {
                    assignment.put(d, f);
                    int c = countTouching(assignment, d);
                    if (c < bestCount) {
                        bestCount = c;
                        best.clear();
                        best.add(f);
                    } else if (c == bestCount) {
                        best.add(f);
                    }
                }
                if (bestCount < count) {
                    progressed = true;
                }
                assignment.put(d, best.get(rng.nextInt(best.size())));
            }
            if (violatedCount(assignment) == 0) {
                return true;
            }
            noProgress = progressed ? 0 : noProgress + 1;
        }
        return violatedCount(assignment) == 0;
    }

    private int violatedCount(Map<Dimension, Feature> assignment) {
        int c = 0;
        for (Without w : withouts) {
            if (w.matches(assignment)) {
                c++;
            }
        }
        return c;
    }

    private int countTouching(Map<Dimension, Feature> assignment, Dimension d) {
        int c = 0;
        for (Without w : byDim.get(d)) {
            if (w.matches(assignment)) {
                c++;
            }
        }
        return c;
    }

    /**
     * Reduces an uncoverable tuple's features to a minimal subset that is
     * still uncoverable, so a single "Could not cover" report can stand in
     * for every tuple that contains it (jenny's deduced-restriction idea;
     * see the Go port's {@code minimalCore}). Never shrinks below a single
     * feature, even when the whole problem is globally infeasible.
     */
    public List<Feature> minimalCore(List<Feature> tupleFeatures) {
        List<Feature> core = new ArrayList<>(tupleFeatures);
        for (int i = 0; i < core.size() && core.size() > 1; ) {
            List<Feature> trial = new ArrayList<>(core);
            trial.remove(i);
            if (!isFeasible(toMap(trial))) {
                core = trial; // feature i was unnecessary for infeasibility
            } else {
                i++; // feature i is needed
            }
        }
        return core;
    }

    private static Map<Dimension, Feature> toMap(List<Feature> features) {
        Map<Dimension, Feature> m = new LinkedHashMap<>();
        for (Feature f : features) {
            m.put(f.dimension(), f);
        }
        return m;
    }
}
