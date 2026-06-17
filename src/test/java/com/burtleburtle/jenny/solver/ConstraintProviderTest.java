package com.burtleburtle.jenny.solver;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.burtleburtle.jenny.domain.AllowedTuple;
import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Feature;
import com.burtleburtle.jenny.domain.JennySolution;
import com.burtleburtle.jenny.domain.TestCase;
import com.burtleburtle.jenny.domain.TestCell;
import com.burtleburtle.jenny.domain.Without;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for individual constraints using ConstraintVerifier.
 * Per T14: verify each constraint penalizes correctly in isolation.
 */
class ConstraintProviderTest {

    private ConstraintVerifier<JennyConstraintProvider, JennySolution> constraintVerifier;

    @BeforeEach
    void setup() {
        constraintVerifier = ConstraintVerifier.build(
                new JennyConstraintProvider(),
                JennySolution.class,
                TestCase.class,
                TestCell.class);
    }

    // ================================================================================
    // coverAllTuples constraint tests
    // ================================================================================

    @Test
    void coverAllTuples_whenCovered_noPenalty() {
        Dimension d0 = new Dimension(0, 2);
        Dimension d1 = new Dimension(1, 2);

        AllowedTuple tuple = new AllowedTuple(List.of(d0.feature(0), d1.feature(0)));

        TestCase tc = createTestCase(1L, true, List.of(d0, d1));
        setFeatures(tc, d0.feature(0), d1.feature(0));

        constraintVerifier.verifyThat(JennyConstraintProvider::coverAllTuples)
                .given(tuple, tc, tc.getCells().toArray())
                .penalizesBy(0);
    }

    @Test
    void coverAllTuples_whenUncovered_penalizes() {
        Dimension d0 = new Dimension(0, 2);
        Dimension d1 = new Dimension(1, 2);

        AllowedTuple uncovered = new AllowedTuple(List.of(d0.feature(0), d1.feature(1)));

        TestCase tc = createTestCase(1L, true, List.of(d0, d1));
        setFeatures(tc, d0.feature(0), d1.feature(0)); // Does NOT cover the tuple

        constraintVerifier.verifyThat(JennyConstraintProvider::coverAllTuples)
                .given(uncovered, tc, tc.getCells().toArray())
                .penalizesBy(1);
    }

    @Test
    void coverAllTuples_whenInactive_doesNotCover() {
        Dimension d0 = new Dimension(0, 2);
        Dimension d1 = new Dimension(1, 2);

        AllowedTuple tuple = new AllowedTuple(List.of(d0.feature(0), d1.feature(0)));

        TestCase tc = createTestCase(1L, false, List.of(d0, d1)); // INACTIVE
        setFeatures(tc, d0.feature(0), d1.feature(0));

        constraintVerifier.verifyThat(JennyConstraintProvider::coverAllTuples)
                .given(tuple, tc, tc.getCells().toArray())
                .penalizesBy(1); // Inactive doesn't cover
    }

    // ================================================================================
    // respectWithouts constraint tests
    // ================================================================================

    @Test
    void respectWithouts_whenNoViolation_noPenalty() {
        Dimension d0 = new Dimension(0, 2);
        Dimension d1 = new Dimension(1, 2);

        Without without = new Without(Map.of(
                d0, Set.of(d0.feature(0)),
                d1, Set.of(d1.feature(1))));

        TestCase tc = createTestCase(1L, true, List.of(d0, d1));
        setFeatures(tc, d0.feature(0), d1.feature(0)); // Does NOT violate (1a,2a)

        constraintVerifier.verifyThat(JennyConstraintProvider::respectWithouts)
                .given(without, tc, tc.getCells().toArray())
                .penalizesBy(0);
    }

    @Test
    void respectWithouts_whenViolation_penalizes() {
        Dimension d0 = new Dimension(0, 2);
        Dimension d1 = new Dimension(1, 2);

        Without without = new Without(Map.of(
                d0, Set.of(d0.feature(0)),
                d1, Set.of(d1.feature(1))));

        TestCase tc = createTestCase(1L, true, List.of(d0, d1));
        setFeatures(tc, d0.feature(0), d1.feature(1)); // VIOLATES (1a,2b)

        constraintVerifier.verifyThat(JennyConstraintProvider::respectWithouts)
                .given(without, tc, tc.getCells().toArray())
                .penalizesBy(1);
    }

    @Test
    void respectWithouts_whenInactive_noPenalty() {
        Dimension d0 = new Dimension(0, 2);
        Dimension d1 = new Dimension(1, 2);

        Without without = new Without(Map.of(
                d0, Set.of(d0.feature(0)),
                d1, Set.of(d1.feature(1))));

        TestCase tc = createTestCase(1L, false, List.of(d0, d1)); // INACTIVE
        setFeatures(tc, d0.feature(0), d1.feature(1));

        constraintVerifier.verifyThat(JennyConstraintProvider::respectWithouts)
                .given(without, tc, tc.getCells().toArray())
                .penalizesBy(0); // Inactive test not checked
    }

    // ================================================================================
    // minimizeActiveTests constraint tests
    // ================================================================================

    @Test
    void minimizeActiveTests_countsOnlyActive() {
        Dimension d0 = new Dimension(0, 2);

        TestCase active1 = createTestCase(1L, true, List.of(d0));
        TestCase active2 = createTestCase(2L, true, List.of(d0));
        TestCase inactive = createTestCase(3L, false, List.of(d0));

        List<Object> allEntities = new ArrayList<>();
        allEntities.add(active1);
        allEntities.add(active2);
        allEntities.add(inactive);
        allEntities.addAll(active1.getCells());
        allEntities.addAll(active2.getCells());
        allEntities.addAll(inactive.getCells());

        constraintVerifier.verifyThat(JennyConstraintProvider::minimizeActiveTests)
                .given(allEntities.toArray())
                .penalizesBy(2); // Only 2 active tests
    }

    // ================================================================================
    // Helper methods
    // ================================================================================

    private TestCase createTestCase(long id, boolean active, List<Dimension> dimensions) {
        TestCase tc = new TestCase(id);
        tc.setActive(active);
        List<TestCell> cells = new ArrayList<>(dimensions.size());
        long cellId = id * 1000;
        for (Dimension dim : dimensions) {
            TestCell cell = new TestCell(cellId++, tc, dim);
            cell.setFeature(dim.feature(0));
            cells.add(cell);
        }
        tc.setCells(cells);
        // Manually set shadow variable (ConstraintVerifier doesn't update them)
        tc.setFeaturesByDim(tc.recomputeFeaturesByDim());
        return tc;
    }

    private void setFeatures(TestCase tc, Feature... features) {
        List<TestCell> cells = tc.getCells();
        for (int i = 0; i < features.length && i < cells.size(); i++) {
            cells.get(i).setFeature(features[i]);
        }
        // Manually update shadow variable
        tc.setFeaturesByDim(tc.recomputeFeaturesByDim());
    }
}
