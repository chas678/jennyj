package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeactivateRedundantMoveIteratorFactoryTest {

    @Test
    void sizeMatchesActiveUnpinnedTestCaseCount() {
        JennySolution problem = buildSimpleProblem(5, 2);
        problem.getTestCases().get(0).setPinned(true);
        problem.getTestCases().get(1).setActive(Boolean.FALSE);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        DeactivateRedundantMoveIteratorFactory factory =
                new DeactivateRedundantMoveIteratorFactory();

        // Expected: 5 total - 1 pinned - 1 inactive = 3 candidates
        assertEquals(3L, factory.getSize(sd));
        sd.close();
    }

    @Test
    void originalIteratorEmitsOneMovePerCandidate() {
        JennySolution problem = buildSimpleProblem(3, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        DeactivateRedundantMoveIteratorFactory factory =
                new DeactivateRedundantMoveIteratorFactory();

        Iterator<Move<JennySolution>> it = factory.createOriginalMoveIterator(sd);
        int count = 0;
        while (it.hasNext()) {
            Move<JennySolution> move = it.next();
            assertNotNull(move);
            count++;
        }
        assertEquals(3, count);
        sd.close();
    }

    @Test
    void emittedMovesTargetActiveFlagWithFalseValue() {
        JennySolution problem = buildSimpleProblem(3, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        DeactivateRedundantMoveIteratorFactory factory =
                new DeactivateRedundantMoveIteratorFactory();

        Iterator<Move<JennySolution>> it = factory.createOriginalMoveIterator(sd);
        int seen = 0;
        while (it.hasNext()) {
            Move<JennySolution> move = it.next();
            assertTrue(move instanceof SelectorBasedChangeMove,
                    "factory must emit SelectorBasedChangeMove instances");
            SelectorBasedChangeMove<JennySolution> change =
                    (SelectorBasedChangeMove<JennySolution>) move;
            assertEquals("active", change.getVariableName(),
                    "move must target the 'active' planning variable");
            assertEquals(Boolean.FALSE, change.getToPlanningValue(),
                    "move must set active to FALSE");
            assertTrue(change.getEntity() instanceof TestCase,
                    "move entity must be a TestCase");
            seen++;
        }
        assertEquals(3, seen, "must emit one move per active unpinned TestCase");
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
        for (int i = 0; i < testCount; i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(0));
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
