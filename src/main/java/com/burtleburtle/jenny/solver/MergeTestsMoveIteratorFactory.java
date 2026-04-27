package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.random.RandomGenerator;

/**
 * Emits composite moves that attempt to merge two active, unpinned test
 * cases (A, B): for each dimension where A and B differ, randomly choose to
 * keep A's feature or replace it with B's; then deactivate B. The acceptor
 * decides whether the merge keeps coverage intact.
 *
 * <p>The original iterator emits all ordered pairs (i, j) with i != j. The
 * random iterator samples ordered pairs uniformly with replacement.
 */
public class MergeTestsMoveIteratorFactory
        implements MoveIteratorFactory<JennySolution, Move<JennySolution>> {

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

        return new Iterator<>() {
            private int i = 0;
            private int j = (candidates.size() > 1) ? 1 : 0;

            @Override
            public boolean hasNext() {
                return i < candidates.size() && j < candidates.size();
            }

            @Override
            public Move<JennySolution> next() {
                if (!hasNext()) throw new NoSuchElementException();
                TestCase a = candidates.get(i);
                TestCase b = candidates.get(j);
                advance();
                // Deterministic merge: always keep B's value when they differ.
                return buildMergeMove(a, b, inner, /*keepBOnDiff=*/ true, null);
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
        InnerScoreDirector<JennySolution, ?> inner =
                (InnerScoreDirector<JennySolution, ?>) scoreDirector;

        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return candidates.size() >= 2;
            }

            @Override
            public Move<JennySolution> next() {
                if (!hasNext()) throw new NoSuchElementException();
                int aIdx = workingRandom.nextInt(candidates.size());
                int bIdx;
                do {
                    bIdx = workingRandom.nextInt(candidates.size());
                } while (bIdx == aIdx);
                return buildMergeMove(
                        candidates.get(aIdx), candidates.get(bIdx),
                        inner, /*keepBOnDiff=*/ false, workingRandom);
            }
        };
    }

    private static List<TestCase> activeUnpinned(ScoreDirector<JennySolution> sd) {
        return new ArrayList<>(sd.getWorkingSolution().getTestCases().stream()
                .filter(tc -> !tc.isPinned() && tc.isActiveFlag())
                .toList());
    }

    @SuppressWarnings("unchecked")
    private Move<JennySolution> buildMergeMove(
            TestCase a, TestCase b,
            InnerScoreDirector<JennySolution, ?> inner,
            boolean keepBOnDiff,
            RandomGenerator workingRandom) {
        GenuineVariableDescriptor<JennySolution> featureDescriptor =
                inner.getSolutionDescriptor()
                        .findEntityDescriptor(TestCell.class)
                        .getGenuineVariableDescriptor("feature");
        GenuineVariableDescriptor<JennySolution> activeDescriptor =
                inner.getSolutionDescriptor()
                        .findEntityDescriptor(TestCase.class)
                        .getGenuineVariableDescriptor("active");

        List<Move<JennySolution>> subMoves = new ArrayList<>();
        for (int k = 0; k < a.getCells().size(); k++) {
            TestCell aCell = a.getCells().get(k);
            TestCell bCell = b.getCells().get(k);
            if (aCell.isPinned()) continue;
            Feature aFeat = aCell.getFeature();
            Feature bFeat = bCell.getFeature();
            if (aFeat == null || aFeat.equals(bFeat)) continue;

            boolean takeB = keepBOnDiff || workingRandom.nextBoolean();
            if (takeB) {
                subMoves.add(new SelectorBasedChangeMove<>(featureDescriptor, aCell, bFeat));
            }
        }
        // Always deactivate B as the final sub-move.
        subMoves.add(new SelectorBasedChangeMove<>(activeDescriptor, b, Boolean.FALSE));
        return Moves.compose(subMoves);
    }
}
