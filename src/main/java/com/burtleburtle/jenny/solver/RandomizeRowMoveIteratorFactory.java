package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Factory that generates composite moves, each re-rolling all {@link TestCell}
 * features in a single {@link TestCase} at once. This helps the solver escape
 * local optima when most tuples are already covered.
 *
 * <p>Creates one {@code SelectorBasedCompositeMove} per test case, where each
 * composite contains a {@code ChangeMove} for every cell in that test.
 */
public class RandomizeRowMoveIteratorFactory
        implements MoveIteratorFactory<JennySolution, Move<JennySolution>> {

    @Override
    public long getSize(ScoreDirector<JennySolution> scoreDirector) {
        JennySolution solution = scoreDirector.getWorkingSolution();
        return solution.getTestCases().stream()
                .filter(tc -> !tc.isPinned())
                .count();
    }

    @Override
    public Iterator<Move<JennySolution>> createOriginalMoveIterator(
            ScoreDirector<JennySolution> scoreDirector) {
        JennySolution solution = scoreDirector.getWorkingSolution();

        List<TestCase> unpinnedTestCases = solution.getTestCases().stream()
                .filter(tc -> !tc.isPinned())
                .toList();

        InnerScoreDirector<JennySolution, ?> innerScoreDirector =
                (InnerScoreDirector<JennySolution, ?>) scoreDirector;
        // Original iteration must be deterministic; use a fixed-seed PRNG
        // rather than Math.random (non-deterministic + global lock).
        RandomGenerator deterministicRandom = new Random(0L);

        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < unpinnedTestCases.size();
            }

            @Override
            public Move<JennySolution> next() {
                TestCase testCase = unpinnedTestCases.get(index++);
                return createRandomizeRowMove(testCase, innerScoreDirector, deterministicRandom);
            }
        };
    }

    @Override
    public Iterator<Move<JennySolution>> createRandomMoveIterator(
            ScoreDirector<JennySolution> scoreDirector,
            RandomGenerator workingRandom) {
        JennySolution solution = scoreDirector.getWorkingSolution();

        List<TestCase> unpinnedTestCases = new ArrayList<>(
                solution.getTestCases().stream()
                        .filter(tc -> !tc.isPinned())
                        .toList()
        );

        Collections.shuffle(unpinnedTestCases, workingRandom);

        InnerScoreDirector<JennySolution, ?> innerScoreDirector =
                (InnerScoreDirector<JennySolution, ?>) scoreDirector;

        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < unpinnedTestCases.size();
            }

            @Override
            public Move<JennySolution> next() {
                TestCase testCase = unpinnedTestCases.get(index++);
                return createRandomizeRowMove(testCase, innerScoreDirector, workingRandom);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Move<JennySolution> createRandomizeRowMove(
            TestCase testCase, InnerScoreDirector<JennySolution, ?> scoreDirector, RandomGenerator random) {
        List<Move<JennySolution>> subMoves = new ArrayList<>();

        GenuineVariableDescriptor<JennySolution> featureDescriptor =
            scoreDirector.getSolutionDescriptor()
                .findEntityDescriptor(TestCell.class)
                .getGenuineVariableDescriptor("feature");

        for (TestCell cell : testCase.getCells()) {
            List<Feature> valueRange = cell.getDimension().features();
            if (!valueRange.isEmpty()) {
                Feature randomFeature = valueRange.get(random.nextInt(valueRange.size()));

                Move<JennySolution> changeMove =
                    new SelectorBasedChangeMove<>(featureDescriptor, cell, randomFeature);
                subMoves.add(changeMove);
            }
        }

        return Moves.compose(subMoves);
    }
}
