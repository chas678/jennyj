package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory;
import ai.timefold.solver.core.preview.api.move.Move;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.CoverageUtil;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EvictRowMoveIteratorFactory}. The scenarios are
 * hand-built so eviction requires a genuine single-cell rehome flip (not a
 * trivial duplicate removal), proving the three spike guarantees:
 * <ol>
 *   <li>a move fires only when every orphan tuple is rehomable,</li>
 *   <li>applying a move preserves coverage and violates no without,</li>
 *   <li>it reduces the active count by exactly one.</li>
 * </ol>
 *
 * <p>Dimensions are three binary dimensions (features a, b); the tuple size is
 * 2 (pairwise). Rows are given as feature-index triples.
 */
class EvictRowMoveIteratorFactoryTest {

    private static final int A = 0;
    private static final int B = 1;

    /**
     * Rehomable scenario. Victim V=(a,a,a) uniquely covers pair (d0=a,d1=a).
     * Its two other pairs are each covered by another row, so V has exactly
     * one orphan. That orphan can be rehomed by flipping P=(a,b,b) at d1 to
     * a — giving (a,a,b) — because the two pairs P then loses (d0=a,d1=b) and
     * (d1=b,d2=b) are still covered by S and T respectively. Deactivating V is
     * then safe.
     */
    private static int[][] rehomableRows() {
        return new int[][]{
                {A, A, A}, // V  victim
                {A, B, B}, // P  flip target (d1 -> a)
                {B, A, A}, // Q  covers (d1=a,d2=a)
                {A, B, A}, // S  covers (d0=a,d2=a) and (d0=a,d1=b)
                {B, B, B}, // T  covers (d1=b,d2=b)
        };
    }

    /**
     * Tight scenario: every covered pair is covered exactly once (except one
     * pair covered twice), so no orphan can be rehomed without dropping some
     * other pair to zero coverage. No row is evictable.
     */
    private static int[][] tightRows() {
        return new int[][]{
                {A, A, A},
                {B, B, B},
                {A, B, B},
        };
    }

    @Test
    void sizeIsActiveUnpinnedCount() {
        JennySolution problem = buildProblem(rehomableRows(), List.of());
        problem.getTestCases().get(0).setPinned(true);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);
        try {
            EvictRowMoveIteratorFactory factory = new EvictRowMoveIteratorFactory();
            assertEquals(4L, factory.getSize(sd), "5 active - 1 pinned = 4 candidates");
        } finally {
            sd.close();
        }
    }

    @Test
    void evictFiresPreservesCoverageAndDropsExactlyOneActive() {
        JennySolution problem = buildProblem(rehomableRows(), List.of());
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);
        try {
            sd.calculateScore();
            long activeBefore = activeCount(problem);
            Set<AllowedTuple> coveredBefore = coveredTuples(problem);
            assertEquals(0, withoutViolations(problem), "scenario has no withouts");

            EvictRowMoveIteratorFactory factory = new EvictRowMoveIteratorFactory();
            Iterator<Move<JennySolution>> it = factory.createOriginalMoveIterator(sd);
            assertTrue(it.hasNext(), "a rehomable victim must yield an evict move");
            Move<JennySolution> move = it.next();

            sd.executeMove(move);
            sd.calculateScore(); // flush shadow-variable updates

            long activeAfter = activeCount(problem);
            Set<AllowedTuple> coveredAfter = coveredTuples(problem);

            assertEquals(activeBefore - 1, activeAfter,
                    "an evict move must deactivate exactly one row");
            assertTrue(coveredAfter.containsAll(coveredBefore),
                    "no previously covered tuple may be lost");
            assertEquals(0, withoutViolations(problem),
                    "eviction must not create a without violation");
        } finally {
            sd.close();
        }
    }

    @Test
    void noMoveFiresWhenNoOrphanIsRehomable() {
        JennySolution problem = buildProblem(tightRows(), List.of());
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);
        try {
            EvictRowMoveIteratorFactory factory = new EvictRowMoveIteratorFactory();
            assertFalse(factory.createOriginalMoveIterator(sd).hasNext(),
                    "no row is removable when every orphan flip would zero another tuple");
        } finally {
            sd.close();
        }
    }

    @Test
    void everyEmittedMoveRespectsWithouts() {
        // The single rehome flip that would evict V in rehomableRows() turns
        // P into (a,a,b). Forbid exactly that combination; the flip must then
        // be rejected and no emitted move may leave any without violated.
        JennySolution withoutProblem = buildProblem(rehomableRows(), List.of(forbid(A, A, B)));
        assertEquals(0, withoutViolations(withoutProblem),
                "sanity: the initial solution obeys the without");

        int moveCount = countMoves(rehomableRows(), List.of(forbid(A, A, B)));

        for (int k = 0; k < moveCount; k++) {
            JennySolution problem = buildProblem(rehomableRows(), List.of(forbid(A, A, B)));
            InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);
            try {
                Set<AllowedTuple> coveredBefore = coveredTuples(problem);
                Move<JennySolution> move = kthMove(
                        new EvictRowMoveIteratorFactory().createOriginalMoveIterator(sd), k);
                sd.executeMove(move);
                sd.calculateScore();
                assertEquals(0, withoutViolations(problem),
                        "emitted move #" + k + " must not violate any without");
                assertTrue(coveredTuples(problem).containsAll(coveredBefore),
                        "emitted move #" + k + " must preserve coverage");
            } finally {
                sd.close();
            }
        }
    }

    // ---- helpers ----

    private static int countMoves(int[][] rows, List<Without> withouts) {
        JennySolution fresh = buildProblem(rows, withouts);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(fresh);
        try {
            int count = 0;
            Iterator<Move<JennySolution>> it =
                    new EvictRowMoveIteratorFactory().createOriginalMoveIterator(sd);
            while (it.hasNext()) {
                it.next();
                count++;
            }
            return count;
        } finally {
            sd.close();
        }
    }

    private static Move<JennySolution> kthMove(Iterator<Move<JennySolution>> it, int k) {
        Move<JennySolution> move = null;
        for (int i = 0; i <= k; i++) {
            move = it.next();
        }
        return move;
    }

    private static long activeCount(JennySolution sol) {
        return sol.getTestCases().stream().filter(TestCase::isActiveFlag).count();
    }

    private static Set<AllowedTuple> coveredTuples(JennySolution sol) {
        Set<AllowedTuple> covered = new HashSet<>();
        for (AllowedTuple t : sol.getAllowedTuples()) {
            for (TestCase tc : sol.getTestCases()) {
                if (CoverageUtil.covers(tc, t)) {
                    covered.add(t);
                    break;
                }
            }
        }
        return covered;
    }

    private static int withoutViolations(JennySolution sol) {
        int count = 0;
        for (TestCase tc : sol.getTestCases()) {
            if (!tc.isActiveFlag()) {
                continue;
            }
            for (Without w : sol.getWithouts()) {
                if (w.matches(tc.getFeaturesByDim())) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Three binary dimensions used by every scenario. */
    private static List<Dimension> dimensions() {
        return List.of(new Dimension(0, 2), new Dimension(1, 2), new Dimension(2, 2));
    }

    /** All allowed pairs over the three binary dimensions (none excluded — the
     *  3-dimension without never matches a 2-tuple). */
    private static List<AllowedTuple> allPairs(List<Dimension> dims) {
        List<AllowedTuple> tuples = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            for (int j = i + 1; j < dims.size(); j++) {
                for (int fi = 0; fi < 2; fi++) {
                    for (int fj = 0; fj < 2; fj++) {
                        tuples.add(new AllowedTuple(List.of(
                                dims.get(i).feature(fi), dims.get(j).feature(fj))));
                    }
                }
            }
        }
        return tuples;
    }

    private static Without forbid(int f0, int f1, int f2) {
        List<Dimension> dims = dimensions();
        Map<Dimension, Set<Feature>> map = new LinkedHashMap<>();
        map.put(dims.get(0), Set.of(dims.get(0).feature(f0)));
        map.put(dims.get(1), Set.of(dims.get(1).feature(f1)));
        map.put(dims.get(2), Set.of(dims.get(2).feature(f2)));
        return new Without(map);
    }

    private static JennySolution buildProblem(int[][] rows, List<Without> withouts) {
        List<Dimension> dims = dimensions();
        List<AllowedTuple> tuples = allPairs(dims);
        List<TestCase> testCases = new ArrayList<>(rows.length);
        List<TestCell> testCells = new ArrayList<>(rows.length * dims.size());
        long cellId = 0;
        for (int r = 0; r < rows.length; r++) {
            TestCase tc = new TestCase(r);
            tc.setActive(Boolean.TRUE);
            List<TestCell> owned = new ArrayList<>(dims.size());
            for (Dimension d : dims) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(d.feature(rows[r][d.index()]));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
        return new JennySolution(dims, tuples, new ArrayList<>(withouts), testCases, testCells);
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
