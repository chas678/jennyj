package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory;
import ai.timefold.solver.core.preview.api.move.Move;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeTestsMoveIteratorFactoryTest {

    @Test
    void sizeIsActivePairCount() {
        // 4 total, pin 1 -> 3 active unpinned, ordered pairs (i!=j) = 3*2 = 6
        JennySolution problem = buildSimpleProblem(4, 2);
        problem.getTestCases().get(0).setPinned(true);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        assertEquals(6L, factory.getSize(sd));
        sd.close();
    }

    @Test
    void randomIteratorEmitsNonNullCompositeMoves() {
        JennySolution problem = buildSimpleProblem(4, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        RandomGenerator rnd = RandomGeneratorFactory.getDefault().create(0L);

        Iterator<Move<JennySolution>> it = factory.createRandomMoveIterator(sd, rnd);
        for (int i = 0; i < 3; i++) {
            assertTrue(it.hasNext(), "random iterator should have moves available");
            Move<JennySolution> move = it.next();
            assertNotNull(move, "emitted move must not be null");
        }
        sd.close();
    }

    @Test
    void iteratorsAreEmptyWhenFewerThanTwoCandidates() {
        // n=1: cannot merge a TestCase with itself.
        JennySolution problem = buildSimpleProblem(1, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        assertEquals(0L, factory.getSize(sd));
        assertEquals(false, factory.createOriginalMoveIterator(sd).hasNext());
        assertEquals(false, factory.createRandomMoveIterator(sd,
                RandomGeneratorFactory.getDefault().create(0L)).hasNext());
        sd.close();
    }

    @Test
    void originalIteratorEmitsAllOrderedPairs() {
        // 3 active unpinned -> 3*2 = 6 ordered pairs
        JennySolution problem = buildSimpleProblem(3, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        Iterator<Move<JennySolution>> it = factory.createOriginalMoveIterator(sd);

        int count = 0;
        while (it.hasNext()) {
            Move<JennySolution> move = it.next();
            assertNotNull(move);
            count++;
        }
        assertEquals(6, count, "must emit one move per ordered (A,B) pair where A != B");
        sd.close();
    }

    private static JennySolution buildSimpleProblem(int testCount, int dimSize) {
        List<Dimension> dimensions = List.of(new Dimension(0, dimSize), new Dimension(1, dimSize));
        List<AllowedTuple> tuples = new ArrayList<>();
        for (int a = 0; a < dimSize; a++) {
            for (int b = 0; b < dimSize; b++) {
                tuples.add(new AllowedTuple(List.of(
                        dimensions.get(0).feature(a), dimensions.get(1).feature(b))));
            }
        }
        List<Without> withouts = List.of();

        List<TestCase> testCases = new ArrayList<>(testCount);
        List<TestCell> testCells = new ArrayList<>(testCount * dimensions.size());
        long cellId = 0;
        Random seedRnd = new Random(7L);
        for (int i = 0; i < testCount; i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(seedRnd.nextInt(d.size())));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
        return new JennySolution(dimensions, tuples, withouts, testCases, testCells);
    }

    @SuppressWarnings("unchecked")
    private static InnerScoreDirector<JennySolution, HardSoftScore> openScoreDirector(
            JennySolution problem) {
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml");
        DefaultSolverFactory<JennySolution> factory =
                (DefaultSolverFactory<JennySolution>) SolverFactory.<JennySolution>create(config);
        InnerScoreDirector<JennySolution, HardSoftScore> sd =
                (InnerScoreDirector<JennySolution, HardSoftScore>)
                        factory.getScoreDirectorFactory().buildScoreDirector();
        sd.setWorkingSolution(problem);
        return sd;
    }
}
