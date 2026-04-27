package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.random.RandomGenerator;

/**
 * Emits composite moves that attempt to merge two active, unpinned test
 * cases (A, B): for each dimension where A and B differ, randomly choose to
 * keep A's feature or replace it with B's; then deactivate B.
 *
 * <p>Random mode pre-filters merges that would create a {@link Without}
 * violation by simulating the merged feature map up front and skipping
 * pairs whose result would match a forbidden combination. Original mode
 * never pre-filters — its deterministic enumeration is a contract callers
 * may rely on.
 *
 * <p>The original iterator emits all ordered pairs (i, j) with i != j. The
 * random iterator samples ordered pairs uniformly with replacement.
 */
public class MergeTestsMoveIteratorFactory
        implements MoveIteratorFactory<JennySolution, Move<JennySolution>> {

    /**
     * Random iterator gives up after this many consecutive Without-violating
     * candidates. Beyond this, returning a violating move is preferable to
     * starving the solver — Hill Climbing will reject the violation cheaply.
     */
    private static final int RANDOM_RETRY_BUDGET = 8;

    @Override
    public long getSize(ScoreDirector<JennySolution> scoreDirector) {
        long n = scoreDirector.getWorkingSolution().getTestCases().stream()
                .filter(tc -> !tc.isPinned() && tc.isActiveFlag())
                .count();
        return n * (n - 1);
    }

    @Override
    public Iterator<Move<JennySolution>> createOriginalMoveIterator(
            ScoreDirector<JennySolution> scoreDirector) {
        List<TestCase> candidates = activeUnpinned(scoreDirector);
        InnerScoreDirector<JennySolution, ?> inner =
                (InnerScoreDirector<JennySolution, ?>) scoreDirector;
        GenuineVariableDescriptor<JennySolution> featureDescriptor = featureDescriptor(inner);
        GenuineVariableDescriptor<JennySolution> activeDescriptor = activeDescriptor(inner);

        return new Iterator<>() {
            private int i = 0;
            private int j = 1;

            @Override
            public boolean hasNext() {
                return candidates.size() >= 2 && i < candidates.size() && j < candidates.size();
            }

            @Override
            public Move<JennySolution> next() {
                if (!hasNext()) throw new NoSuchElementException();
                TestCase a = candidates.get(i);
                TestCase b = candidates.get(j);
                advance();
                // Deterministic merge: always keep B's value when they differ.
                return buildMergeMove(a, b, featureDescriptor, activeDescriptor, /*takeBOnDiff*/ null);
            }

            private void advance() {
                j++;
                if (j == i) j++;
                if (j >= candidates.size()) {
                    i++;
                    j = (i == 0) ? 1 : 0;
                }
            }
        };
    }

    @Override
    public Iterator<Move<JennySolution>> createRandomMoveIterator(
            ScoreDirector<JennySolution> scoreDirector,
            RandomGenerator workingRandom) {
        List<TestCase> candidates = activeUnpinned(scoreDirector);
        List<Without> withouts = scoreDirector.getWorkingSolution().getWithouts();
        InnerScoreDirector<JennySolution, ?> inner =
                (InnerScoreDirector<JennySolution, ?>) scoreDirector;
        GenuineVariableDescriptor<JennySolution> featureDescriptor = featureDescriptor(inner);
        GenuineVariableDescriptor<JennySolution> activeDescriptor = activeDescriptor(inner);

        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return candidates.size() >= 2;
            }

            @Override
            public Move<JennySolution> next() {
                if (!hasNext()) throw new NoSuchElementException();
                // Try up to RANDOM_RETRY_BUDGET pairs to find a merge whose
                // post-state doesn't violate any Without. Pre-roll the
                // takeB decisions per attempt and reuse them for both the
                // prediction (mergeWouldViolateWithout) and the actual move
                // construction (buildMergeMove) so the prediction is exact.
                for (int attempt = 0; attempt < RANDOM_RETRY_BUDGET; attempt++) {
                    int aIdx = workingRandom.nextInt(candidates.size());
                    int bIdx;
                    do {
                        bIdx = workingRandom.nextInt(candidates.size());
                    } while (bIdx == aIdx);
                    TestCase a = candidates.get(aIdx);
                    TestCase b = candidates.get(bIdx);
                    boolean[] takeB = rollTakeBDecisions(a, workingRandom);
                    if (mergeWouldViolateWithout(a, b, takeB, withouts)) {
                        continue;
                    }
                    return buildMergeMove(a, b, featureDescriptor, activeDescriptor, takeB);
                }
                // Budget exhausted — emit a final attempt; the acceptor will
                // reject if it violates.
                int aIdx = workingRandom.nextInt(candidates.size());
                int bIdx;
                do {
                    bIdx = workingRandom.nextInt(candidates.size());
                } while (bIdx == aIdx);
                TestCase a = candidates.get(aIdx);
                TestCase b = candidates.get(bIdx);
                return buildMergeMove(a, b, featureDescriptor, activeDescriptor,
                        rollTakeBDecisions(a, workingRandom));
            }
        };
    }

    /** Pre-roll a takeB decision per cell of A. Index aligned with {@code a.getCells()}. */
    private static boolean[] rollTakeBDecisions(TestCase a, RandomGenerator workingRandom) {
        boolean[] takeB = new boolean[a.getCells().size()];
        for (int k = 0; k < takeB.length; k++) {
            takeB[k] = workingRandom.nextBoolean();
        }
        return takeB;
    }

    /**
     * Predict the post-merge feature map for A and check whether any
     * {@link Without} matches it. Mirrors the per-cell decision logic in
     * {@link #buildMergeMove} so the prediction matches the move's effect.
     */
    private static boolean mergeWouldViolateWithout(
            TestCase a, TestCase b, boolean[] takeB, List<Without> withouts) {
        if (withouts.isEmpty()) {
            return false;
        }
        Map<Dimension, Feature> merged = new HashMap<>(a.getCells().size() * 2);
        for (int k = 0; k < a.getCells().size(); k++) {
            TestCell aCell = a.getCells().get(k);
            TestCell bCell = b.getCells().get(k);
            Feature aFeat = aCell.getFeature();
            Feature bFeat = bCell.getFeature();
            if (aFeat == null) {
                continue;
            }
            // Match buildMergeMove: pinned cells keep A's value; equal cells
            // are no-ops; otherwise honor the pre-rolled takeB decision.
            Feature chosen = aFeat;
            if (!aCell.isPinned() && !aFeat.equals(bFeat) && takeB[k]) {
                chosen = bFeat;
            }
            merged.put(aCell.getDimension(), chosen);
        }
        for (Without w : withouts) {
            if (w.matches(merged)) {
                return true;
            }
        }
        return false;
    }

    private static List<TestCase> activeUnpinned(ScoreDirector<JennySolution> sd) {
        return new ArrayList<>(sd.getWorkingSolution().getTestCases().stream()
                .filter(tc -> !tc.isPinned() && tc.isActiveFlag())
                .toList());
    }

    @SuppressWarnings("unchecked")
    private static GenuineVariableDescriptor<JennySolution> featureDescriptor(
            InnerScoreDirector<JennySolution, ?> inner) {
        return inner.getSolutionDescriptor()
                .findEntityDescriptor(TestCell.class)
                .getGenuineVariableDescriptor("feature");
    }

    @SuppressWarnings("unchecked")
    private static GenuineVariableDescriptor<JennySolution> activeDescriptor(
            InnerScoreDirector<JennySolution, ?> inner) {
        return inner.getSolutionDescriptor()
                .findEntityDescriptor(TestCase.class)
                .getGenuineVariableDescriptor("active");
    }

    /**
     * @param takeB null = deterministic mode (always take B when differing),
     *              non-null = pre-rolled per-cell decision aligned with
     *              {@code a.getCells()}.
     */
    private Move<JennySolution> buildMergeMove(
            TestCase a, TestCase b,
            GenuineVariableDescriptor<JennySolution> featureDescriptor,
            GenuineVariableDescriptor<JennySolution> activeDescriptor,
            boolean[] takeB) {
        List<Move<JennySolution>> subMoves = new ArrayList<>();
        for (int k = 0; k < a.getCells().size(); k++) {
            TestCell aCell = a.getCells().get(k);
            TestCell bCell = b.getCells().get(k);
            if (aCell.isPinned()) continue;
            Feature aFeat = aCell.getFeature();
            Feature bFeat = bCell.getFeature();
            if (aFeat == null || aFeat.equals(bFeat)) continue;

            boolean shouldTakeB = (takeB == null) || takeB[k];
            if (shouldTakeB) {
                subMoves.add(new SelectorBasedChangeMove<>(featureDescriptor, aCell, bFeat));
            }
        }
        // Always deactivate B as the final sub-move.
        subMoves.add(new SelectorBasedChangeMove<>(activeDescriptor, b, Boolean.FALSE));
        return Moves.compose(subMoves);
    }
}
