package com.burtleburtle.jenny.domain;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.burtleburtle.jenny.bootstrap.TupleEnumerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guard tests protecting against Timefold 2.1.0's duplicate-entity fail-fast.
 *
 * <p>Timefold 2.1.0 hard-fails on duplicate inserts:
 * {@code AbstractForEachUniNode.insert()} throws
 * {@link IllegalStateException}("The fact (...) was already inserted...").
 * The check uses a {@code HashMap} keyed by equals/hashCode, so value-equal
 * instances (not just reference-identical ones) trigger it.
 *
 * <p>{@link AllowedTuple} has value-based equals/hashCode. These tests confirm
 * that {@link TupleEnumerator} never produces equal tuples and that
 * {@link TestCase}/{@link TestCell} IDs are unique, so a solve does not abort
 * with the duplicate-insert error.
 */
class DuplicateEntityGuardTest {

    // -------------------------------------------------------------------------
    // (a) AllowedTuple uniqueness — the actual fail-fast trigger
    // -------------------------------------------------------------------------

    /**
     * 3×3×3 dims, n=2: confirms no two AllowedTuples are .equals().
     */
    @Test
    void enumeratedTuples_3x3x3_n2_containsNoDuplicates() {
        List<Dimension> dims = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 3));
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, List.of());

        Set<AllowedTuple> distinct = new HashSet<>(tuples);
        assertEquals(distinct.size(), tuples.size(),
                "AllowedTuples must all be distinct (value-equal pair would trigger "
                + "Timefold 2.1.0 duplicate-insert fail-fast). "
                + "Duplicate count: " + (tuples.size() - distinct.size()));
    }

    /**
     * 3×3×3 dims, n=2, with a -w (dim0=feat0 & dim1=feat0 forbidden):
     * confirms the without-filtered list still has no duplicates.
     */
    @Test
    void enumeratedTuples_3x3x3_n2_withWithout_containsNoDuplicates() {
        Dimension d0 = new Dimension(0, 3);
        Dimension d1 = new Dimension(1, 3);
        Dimension d2 = new Dimension(2, 3);
        List<Dimension> dims = List.of(d0, d1, d2);

        Without w = new Without(Map.of(
                d0, Set.of(d0.feature(0)),
                d1, Set.of(d1.feature(0))));
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, List.of(w));

        Set<AllowedTuple> distinct = new HashSet<>(tuples);
        assertEquals(distinct.size(), tuples.size(),
                "Filtered AllowedTuples must all be distinct. "
                + "Duplicate count: " + (tuples.size() - distinct.size()));
    }

    // -------------------------------------------------------------------------
    // (b) PlanningId uniqueness for TestCase and TestCell
    // -------------------------------------------------------------------------

    /**
     * Verifies that a built JennySolution has unique @PlanningId Long values
     * for all TestCase entities and separately for all TestCell entities.
     * Duplicate PlanningIds would cause silent Timefold state corruption.
     */
    @Test
    void builtSolution_planningIds_areUniqueWithinEachEntityType() {
        List<Dimension> dims = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 3));
        List<Without> withouts = List.of();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, withouts);

        JennySolution solution = buildSolution(dims, tuples, withouts);

        // TestCase IDs must all be unique
        List<Long> caseIds = solution.getTestCases().stream()
                .map(TestCase::getId)
                .collect(Collectors.toList());
        Set<Long> distinctCaseIds = new HashSet<>(caseIds);
        assertEquals(distinctCaseIds.size(), caseIds.size(),
                "TestCase @PlanningId values must be unique");

        // TestCell IDs must all be unique
        List<Long> cellIds = solution.getTestCells().stream()
                .map(TestCell::getId)
                .collect(Collectors.toList());
        Set<Long> distinctCellIds = new HashSet<>(cellIds);
        assertEquals(distinctCellIds.size(), cellIds.size(),
                "TestCell @PlanningId values must be unique");
    }

    // -------------------------------------------------------------------------
    // (c) End-to-end: solve does NOT throw Timefold's duplicate-insert error
    // -------------------------------------------------------------------------

    /**
     * Builds a small problem and runs the solver for up to 3 seconds.
     * A regression introducing duplicate AllowedTuples (or duplicate PlanningIds)
     * would cause Timefold 2.1.0 to throw:
     * <pre>IllegalStateException: The fact (...) was already inserted...</pre>
     * This test catches that failure mode explicitly.
     */
    @Test
    void solve_smallProblem_doesNotThrowDuplicateInsertException() {
        List<Dimension> dims = List.of(
                new Dimension(0, 3),
                new Dimension(1, 3),
                new Dimension(2, 3));
        List<Without> withouts = List.of();
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dims, 2, withouts);
        JennySolution problem = buildSolution(dims, tuples, withouts);

        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(3)));
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();

        try {
            JennySolution solved = solver.solve(problem);
            assertNotNull(solved.getScore(), "solver must produce a score");
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("already inserted")) {
                fail("Timefold 2.1.0 duplicate-insert fail-fast triggered — "
                        + "duplicate AllowedTuple or duplicate PlanningId detected by solver: "
                        + e.getMessage());
            }
            throw e; // unrelated ISE — rethrow
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Builds a JennySolution using the same pattern as SolverSmokeTest,
     * allocating enough slot capacity to hold all tuples.
     */
    private JennySolution buildSolution(
            List<Dimension> dims,
            List<AllowedTuple> tuples,
            List<Without> withouts) {

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

        return new JennySolution(dims, tuples, withouts, testCases, testCells);
    }
}
