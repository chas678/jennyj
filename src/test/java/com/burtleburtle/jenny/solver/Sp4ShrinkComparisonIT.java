package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.bootstrap.GreedyInitializer;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import com.burtleburtle.jenny.cli.WithoutParser;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP4 A/B measurement: runs the jenny self-test through the LS-only control
 * pipeline and the SP4 (LS + custom shrink/recover) pipeline with the <em>same</em>
 * per-phase budgets, then prints and compares the active-row counts.
 *
 * <p>Both configs come from {@link JennySolverFactory} so the local-search
 * phases get identical budgets; the only difference is the interleaved
 * deterministic shrink/recover custom phases. Because those phases are
 * coverage-safe and without-safe, "with-phases" can only match or beat the
 * control at hard==0.
 */
class Sp4ShrinkComparisonIT {

    private static final long BUDGET_SECONDS = 60L;

    @Test
    void shrinkPhasesLowerActiveAtHardZero() {
        Problem p = buildSelfTest();

        Result control = solve(p, JennySolverFactory.createConfig(BUDGET_SECONDS, false));
        Result withPhases = solve(p, JennySolverFactory.createConfig(BUDGET_SECONDS, true));

        System.out.printf("SP4-COMPARE control(LS-only): active=%d uncovered=%d hard=%d%n",
                control.active, control.uncovered, control.hard);
        System.out.printf("SP4-COMPARE with-phases     : active=%d uncovered=%d hard=%d%n",
                withPhases.active, withPhases.uncovered, withPhases.hard);
        System.out.printf("SP4-COMPARE delta(active)   : %d (negative = fewer rows with phases)%n",
                withPhases.active - control.active);

        assertEquals(0, withPhases.uncovered, "with-phases must cover every allowed tuple");
        assertEquals(0, withPhases.hard, "with-phases must be feasible (hard == 0)");
        assertTrue(withPhases.active <= control.active,
                "with-phases active (" + withPhases.active + ") must be <= control ("
                        + control.active + ")");
    }

    private record Result(long active, long uncovered, long hard) {
    }

    private static Result solve(Problem p, SolverConfig baseConfig) {
        // Fresh entities per run so the two solves are independent.
        JennySolution problem = p.build();
        SolverConfig config = baseConfig
                .withRandomSeed(0L)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(BUDGET_SECONDS + 15)));
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);

        long active = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        long uncovered = solved.getAllowedTuples().stream()
                .filter(t -> solved.getTestCases().stream()
                        .noneMatch(tc -> tc.isActiveFlag() && tc.coversTuple(t)))
                .count();
        return new Result(active, uncovered, solved.getScore().hardScore());
    }

    // ---- self-test problem (n=3, 12 dims, 13 withouts) --------------------

    private record Problem(List<Dimension> dims, List<AllowedTuple> tuples,
                           List<Without> withouts, List<Map<Dimension, Feature>> greedy,
                           int slotCount) {
        JennySolution build() {
            List<TestCase> testCases = new ArrayList<>(slotCount);
            List<TestCell> testCells = new ArrayList<>(slotCount * dims.size());
            long cellId = 0;
            for (int i = 0; i < greedy.size(); i++) {
                TestCase tc = new TestCase(i);
                tc.setActive(Boolean.TRUE);
                List<TestCell> owned = new ArrayList<>(dims.size());
                for (Dimension d : dims) {
                    TestCell cell = new TestCell(cellId++, tc, d);
                    cell.setFeature(greedy.get(i).get(d));
                    owned.add(cell);
                    testCells.add(cell);
                }
                tc.setCells(owned);
                testCases.add(tc);
            }
            for (int i = greedy.size(); i < slotCount; i++) {
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
            return new JennySolution(dims, tuples, withouts, testCases, testCells);
        }
    }

    private static Problem buildSelfTest() {
        List<Dimension> dims = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4));
        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"};
        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dims));
        }
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 3, withouts);
        List<Map<Dimension, Feature>> greedy =
                GreedyInitializer.buildInitialTests(dims, tuples, withouts, new Random(0));
        int slotCount = Math.max(greedy.size() + 20, 200);
        return new Problem(dims, tuples, withouts, greedy, slotCount);
    }
}
