package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import ai.timefold.solver.core.preview.api.move.Move;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Emits one move per active, unpinned {@link TestCase} that flips
 * {@code active} to {@code false}. The acceptor evaluates whether the
 * deactivation is safe (no tuples become uncovered) and either accepts
 * (soft score improves by 1) or rejects (hard score worsens).
 *
 * <p>Companion to {@link RandomizeRowMoveIteratorFactory}; both target the
 * test-suite-minimization soft score with moves that single-variable
 * ChangeMoves cannot easily reach.
 */
public class DeactivateRedundantMoveIteratorFactory
        implements MoveIteratorFactory<JennySolution, Move<JennySolution>> {

    @Override
    public long getSize(ScoreDirector<JennySolution> scoreDirector) {
        JennySolution solution = scoreDirector.getWorkingSolution();
        return solution.getTestCases().stream()
                .filter(tc -> !tc.isPinned() && tc.isActiveFlag())
                .count();
    }

    @Override
    public Iterator<Move<JennySolution>> createOriginalMoveIterator(
            ScoreDirector<JennySolution> scoreDirector) {
        return buildIterator(scoreDirector, false, null);
    }

    @Override
    public Iterator<Move<JennySolution>> createRandomMoveIterator(
            ScoreDirector<JennySolution> scoreDirector,
            RandomGenerator workingRandom) {
        return buildIterator(scoreDirector, true, workingRandom);
    }

    @SuppressWarnings("unchecked")
    private Iterator<Move<JennySolution>> buildIterator(
            ScoreDirector<JennySolution> scoreDirector,
            boolean shuffle, RandomGenerator workingRandom) {
        JennySolution solution = scoreDirector.getWorkingSolution();
        List<TestCase> candidates = new ArrayList<>(solution.getTestCases().stream()
                .filter(tc -> !tc.isPinned() && tc.isActiveFlag())
                .toList());
        if (shuffle) {
            Collections.shuffle(candidates, workingRandom);
        }

        InnerScoreDirector<JennySolution, ?> innerScoreDirector =
                (InnerScoreDirector<JennySolution, ?>) scoreDirector;
        GenuineVariableDescriptor<JennySolution> activeDescriptor =
                innerScoreDirector.getSolutionDescriptor()
                        .findEntityDescriptor(TestCase.class)
                        .getGenuineVariableDescriptor("active");

        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < candidates.size();
            }

            @Override
            public Move<JennySolution> next() {
                TestCase tc = candidates.get(index++);
                return new SelectorBasedChangeMove<>(activeDescriptor, tc, Boolean.FALSE);
            }
        };
    }
}
