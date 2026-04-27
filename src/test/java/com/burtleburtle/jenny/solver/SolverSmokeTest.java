package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolverSmokeTest {

    /**
     * Solve a tiny problem (2 binary dimensions, pairs) and assert the
     * solver produces a solution that covers every required tuple with
     * every active TestCase obeying the withouts.
     */
    @Test
    void solves_three_binary_dimensions_pairs() {
        List<Dimension> dims = List.of(
                new Dimension(0, 2),
                new Dimension(1, 2),
                new Dimension(2, 2));
        List<Without> withouts = List.of();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, withouts);
        assertTrue(tuples.size() > 0);

        int slotCount = Math.max(4, tuples.size());
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dims.size());
        long cellId = 0;
        for (int i = 0; i < slotCount; i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            List<TestCell> owned = new ArrayList<>(dims.size());
            for (Dimension d : dims) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(0));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }

        JennySolution problem = new JennySolution(
                dims, tuples, withouts, testCases, testCells);

        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(4))
                        .withBestScoreFeasible(true));
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();

        JennySolution solved = solver.solve(problem);

        assertNotNull(solved.getScore(), "solver must produce a score");
        assertTrue(solved.getScore().hardScore() >= 0
                        && solved.getScore().softScore() <= 0,
                "score sanity: " + solved.getScore());

        for (AllowedTuple tuple : tuples) {
            boolean covered = solved.getTestCases().stream()
                    .anyMatch(tc -> tc.isActiveFlag() && tc.coversTuple(tuple));
            assertTrue(covered, "tuple not covered: " + tuple);
        }
    }
}
