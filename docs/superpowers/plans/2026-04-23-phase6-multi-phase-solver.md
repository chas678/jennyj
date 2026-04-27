# Phase 6 Multi-Phase Solver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beat jenny.c's 116 tests on the self-test benchmark (`-n3 4 4 3 3 3 3 3 3 4 3 3 4` + 13 withouts) by unpinning the greedy warm-start and adding two custom move iterator factories driven by a multi-phase Timefold local-search configuration.

**Architecture:** Three-stage pipeline — greedy warm-start (now unpinned), Phase 1 Tabu Search with two new problem-specific moves (DeactivateRedundant, MergeTests), Phase 2 Late Acceptance refinement with standard ChangeMoves. Existing constraints stay untouched; the optimization wins come from move design and unpinning.

**Tech Stack:** Java 25, Timefold Solver 2.0 (preview Moves API), JUnit 5.6, Maven 3.x. Existing `RandomizeRowMoveIteratorFactory` is the structural reference for both new factories.

**Spec:** `docs/superpowers/specs/2026-04-23-phase6-multi-phase-solver-design.md`

**Branch:** `phase6-approach2-multi-phase-solver` (already created and checked out)

---

## File Structure

**New files (all under `src/main/java/com/burtleburtle/jenny/solver/` and matching test paths):**
- `DeactivateRedundantMoveIteratorFactory.java` — emits one Active=false move per active unpinned TestCase. Single-variable change move using `SelectorBasedChangeMove`.
- `MergeTestsMoveIteratorFactory.java` — picks two active unpinned TestCases (A, B), emits a composite move that overwrites A's differing cells with B's features (random per dim) and deactivates B. Uses `Moves.compose`.
- Test files for both factories under `src/test/java/com/burtleburtle/jenny/solver/`.
- `JennyBeatsBenchmarkTest.java` — the goal-line test asserting ≤116 active tests with 0 uncovered tuples on the self-test input. Tagged `benchmark` so it can be excluded from default `mvn test` runs.

**Modified files:**
- `src/main/resources/solverConfig.xml` — replaced with multi-phase configuration (Phase 1 Tabu Search + Phase 2 Late Acceptance + global 120s safety cap).
- `src/main/java/com/burtleburtle/jenny/cli/JennyCli.java` — remove `setPinned(true)` on greedy-derived TestCase and TestCell construction (lines ~166, 174). Keep pinning on `-o`-imported tests.
- `src/test/java/com/burtleburtle/jenny/solver/SolverProfilingTest.java` — same unpinning fix; also report active-test count between phases for diagnostics.
- `pom.xml` — Surefire `<groups>` configuration to exclude `benchmark`-tagged tests from default runs.
- `TASKS.md` — mark T27 complete with the new measurements.

---

## Task 1: Add JennyBeatsBenchmarkTest as the failing-goal oracle

**Why this first:** TDD. We need a single, machine-checkable target so each subsequent task can be evaluated against it. The test is expected to FAIL until Task 7 is complete; we tag it so it doesn't break `mvn test`.

**Files:**
- Create: `src/test/java/com/burtleburtle/jenny/solver/JennyBeatsBenchmarkTest.java`
- Modify: `pom.xml` (Surefire excluded-groups configuration)

- [ ] **Step 1.1: Configure Surefire to exclude benchmark tag from default runs**

In `pom.xml`, locate the `maven-surefire-plugin` block (lines ~104-110). Replace it with:

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
                <configuration>
                    <argLine>-Dnet.bytebuddy.experimental=true</argLine>
                    <excludedGroups>benchmark</excludedGroups>
                </configuration>
            </plugin>
```

- [ ] **Step 1.2: Create the goal-line benchmark test**

Create `src/test/java/com/burtleburtle/jenny/solver/JennyBeatsBenchmarkTest.java` with this exact content:

```java
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 goal-line test: solve the jenny self-test benchmark and assert
 * we match or beat jenny.c's 116-test result with 0 uncovered tuples.
 *
 * <p>Tagged {@code benchmark} so it is excluded from default {@code mvn test}.
 * Run explicitly with: {@code mvn test -Dgroups=benchmark}.
 */
@Tag("benchmark")
class JennyBeatsBenchmarkTest {

    private static final int JENNY_C_TEST_COUNT = 116;
    private static final long MAX_WALL_TIME_MS = 130_000L;

    @Test
    void beatsJennyOnSelfTest() {
        List<Dimension> dimensions = List.of(
                new Dimension(0, 4), new Dimension(1, 4), new Dimension(2, 3),
                new Dimension(3, 3), new Dimension(4, 3), new Dimension(5, 3),
                new Dimension(6, 3), new Dimension(7, 3), new Dimension(8, 4),
                new Dimension(9, 3), new Dimension(10, 3), new Dimension(11, 4));
        String[] withoutStrings = {
                "1abc2d", "1d2abc", "6ab7bc", "6b8c", "6a8bc", "6a9abc",
                "6a10ab", "11a12abc", "11bc12d", "4c5ab", "1a3a", "1a9a", "3a9c"};

        List<Without> withouts = new ArrayList<>();
        for (String w : withoutStrings) {
            withouts.add(WithoutParser.parse(w, dimensions));
        }
        List<AllowedTuple> tuples = TupleEnumerator.enumerate(dimensions, 3, withouts);

        Random rnd = new Random(0);
        List<Map<Dimension, Feature>> greedyTests = GreedyInitializer.buildInitialTests(
                dimensions, tuples, withouts, rnd);

        int slotCount = Math.max(greedyTests.size() + 20, 200);
        List<TestCase> testCases = new ArrayList<>(slotCount);
        List<TestCell> testCells = new ArrayList<>(slotCount * dimensions.size());
        long cellId = 0;

        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(i);
            tc.setActive(Boolean.TRUE);
            // Greedy tests are unpinned so the solver can deactivate or merge them.
            Map<Dimension, Feature> greedyTest = greedyTests.get(i);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                cell.setFeature(greedyTest.get(d));
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
        for (int i = greedyTests.size(); i < slotCount; i++) {
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

        JennySolution problem = new JennySolution(
                dimensions, tuples, withouts, testCases, testCells);

        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withRandomSeed(0L)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofMillis(MAX_WALL_TIME_MS)));

        long start = System.currentTimeMillis();
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();
        JennySolution solved = solver.solve(problem);
        long elapsed = System.currentTimeMillis() - start;

        long activeTests = solved.getTestCases().stream().filter(TestCase::isActiveFlag).count();
        long uncovered = tuples.stream()
                .filter(t -> solved.getTestCases().stream()
                        .noneMatch(tc -> tc.isActiveFlag() && tc.coversTuple(t)))
                .count();

        System.out.printf("benchmark: active=%d, uncovered=%d, elapsed=%dms%n",
                activeTests, uncovered, elapsed);

        assertEquals(0, uncovered, "Solution must cover every allowed tuple");
        assertTrue(activeTests <= JENNY_C_TEST_COUNT,
                "Active test count " + activeTests + " must be <= jenny.c's " + JENNY_C_TEST_COUNT);
        assertTrue(elapsed <= MAX_WALL_TIME_MS,
                "Wall time " + elapsed + "ms exceeds limit " + MAX_WALL_TIME_MS + "ms");
    }
}
```

- [ ] **Step 1.3: Verify default build still passes (benchmark excluded)**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all 26 existing tests still pass, JennyBeatsBenchmarkTest is not run.

- [ ] **Step 1.4: Verify the benchmark fails on current main behavior**

Run: `mvn test -Dgroups=benchmark -Dtest=JennyBeatsBenchmarkTest`
Expected: FAIL with "Active test count 131 must be <= jenny.c's 116" (or similar number > 116). This confirms the test correctly measures the gap we need to close.

- [ ] **Step 1.5: Commit**

```bash
git add pom.xml src/test/java/com/burtleburtle/jenny/solver/JennyBeatsBenchmarkTest.java
git commit -m "$(cat <<'EOF'
T28: Add JennyBeatsBenchmarkTest goal-line oracle

Tagged 'benchmark' and excluded from default mvn test. Currently fails
with 131 > 116 — this is the target Phase 6 must close.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Unpin greedy tests in JennyCli (the critical fix)

**Why:** Without this, Timefold cannot modify greedy-derived tests; all Phase 6 work depends on it.

**Files:**
- Modify: `src/main/java/com/burtleburtle/jenny/cli/JennyCli.java` (lines 161-180)

- [ ] **Step 2.1: Read current greedy-test construction block**

Read `src/main/java/com/burtleburtle/jenny/cli/JennyCli.java` lines 161-180 to confirm the location of `tc.setPinned(true)` and `cell.setPinned(true)` calls in the greedy-test loop.

- [ ] **Step 2.2: Remove pinning calls from the greedy block**

In `JennyCli.java`, replace lines 161-180 (the greedy-tests block — `// Then add greedy initial tests (fully pinned to preserve valid solution)` through the closing `testCases.add(tc);`) with:

```java
        // Then add greedy initial tests (UNPINNED so the solver can
        // deactivate or merge them — see Phase 6 design doc).
        int greedyStart = oldTests.size();
        for (int i = 0; i < greedyTests.size(); i++) {
            TestCase tc = new TestCase(greedyStart + i);
            tc.setActive(Boolean.TRUE);

            Map<Dimension, Feature> greedyTest = greedyTests.get(i);
            List<TestCell> owned = new ArrayList<>(dimensions.size());
            for (Dimension d : dimensions) {
                TestCell cell = new TestCell(cellId++, tc, d);
                Feature assignedFeature = greedyTest.get(d);
                cell.setFeature(assignedFeature);
                owned.add(cell);
                testCells.add(cell);
            }
            tc.setCells(owned);
            testCases.add(tc);
        }
```

The old-test block (lines 141-159) keeps its `setPinned(true)` calls — `-o`-imported tests must remain immutable.

- [ ] **Step 2.3: Run all existing tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS. The existing CLI regression and solution-verification tests should all still pass; valid-solution invariants are enforced by constraints, not pinning.

- [ ] **Step 2.4: Run benchmark test to see new baseline**

Run: `mvn test -Dgroups=benchmark -Dtest=JennyBeatsBenchmarkTest`
Expected: still FAILS, but the active-test count printed should be lower than 131 (likely ~120-128 — Timefold can now do *some* improvement with standard moves alone). Note the new number; it will inform later progress checks.

- [ ] **Step 2.5: Commit**

```bash
git add src/main/java/com/burtleburtle/jenny/cli/JennyCli.java
git commit -m "$(cat <<'EOF'
T28: Unpin greedy tests in JennyCli to unblock optimization

Greedy-init tests were pinned, preventing Timefold from modifying or
deactivating them. -o imported tests remain pinned (correct behavior).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Mirror the unpinning fix in SolverProfilingTest

**Why:** SolverProfilingTest's two methods construct the planning problem inline with the same pinning bug. They will give misleading profiling numbers until corrected.

**Files:**
- Modify: `src/test/java/com/burtleburtle/jenny/solver/SolverProfilingTest.java` (lines 92-110 and 206-221, both `tc.setPinned(true)` blocks)

- [ ] **Step 3.1: Remove pinning from `profileJennySelfTest`**

In `SolverProfilingTest.java`, locate the loop that begins with `// Add greedy tests (pinned)` (around line 92-110). Delete the lines `tc.setPinned(true);` and `cell.setPinned(true);` and update the comment to `// Add greedy tests (unpinned — solver may modify/deactivate them)`.

- [ ] **Step 3.2: Remove pinning from `profileNormalMode`**

Same change in the second method, around lines 206-221.

- [ ] **Step 3.3: Run profiling test to confirm changes compile**

Run: `mvn -q test -Dtest=SolverProfilingTest`
Expected: Tests compile and pass (they don't make hard assertions, they just print).

- [ ] **Step 3.4: Commit**

```bash
git add src/test/java/com/burtleburtle/jenny/solver/SolverProfilingTest.java
git commit -m "$(cat <<'EOF'
T28: Mirror unpinning fix in SolverProfilingTest

Keep profiling output honest by matching JennyCli's now-unpinned greedy
construction.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Implement DeactivateRedundantMoveIteratorFactory (TDD)

**Why:** This is the simpler of the two new moves. Writing it first lets us validate the move-doability/undo pattern before tackling the more complex MergeTests.

**Files:**
- Create: `src/test/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratoryFactoryTest.java`
- Create: `src/main/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactory.java`

- [ ] **Step 4.1: Write the failing factory test**

Create `src/test/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactoryTest.java`:

```java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeactivateRedundantMoveIteratorFactoryTest {

    @Test
    void sizeMatchesActiveUnpinnedTestCaseCount() {
        JennySolution problem = buildSimpleProblem(5, 2);
        // Pin one test case, deactivate another.
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
    void doingThenUndoingMoveRestoresActiveFlag() {
        JennySolution problem = buildSimpleProblem(3, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        DeactivateRedundantMoveIteratorFactory factory =
                new DeactivateRedundantMoveIteratorFactory();

        Iterator<Move<JennySolution>> it = factory.createOriginalMoveIterator(sd);
        Move<JennySolution> move = it.next();

        TestCase first = problem.getTestCases().get(0);
        assertTrue(first.isActiveFlag(), "precondition: active before doMove");

        Move<JennySolution> undo = move.doMove(sd);
        sd.triggerVariableListeners();
        assertEquals(Boolean.FALSE, first.getActive(), "doMove should set active=false");

        undo.doMove(sd);
        sd.triggerVariableListeners();
        assertEquals(Boolean.TRUE, first.getActive(), "undo should restore active=true");
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
```

- [ ] **Step 4.2: Verify the test fails with "class not found"**

Run: `mvn -q test -Dtest=DeactivateRedundantMoveIteratorFactoryTest`
Expected: COMPILATION ERROR — `cannot find symbol class DeactivateRedundantMoveIteratorFactory`. This confirms TDD is set up correctly.

- [ ] **Step 4.3: Implement the factory**

Create `src/main/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactory.java`:

```java
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
            Collections.shuffle(candidates, (java.util.Random)
                    new java.util.Random(workingRandom.nextLong()));
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
```

- [ ] **Step 4.4: Run the factory test**

Run: `mvn -q test -Dtest=DeactivateRedundantMoveIteratorFactoryTest`
Expected: ALL THREE TESTS PASS.

- [ ] **Step 4.5: Run full default test suite to confirm no regression**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all 26+3=29 tests pass.

- [ ] **Step 4.6: Commit**

```bash
git add src/main/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactory.java \
        src/test/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactoryTest.java
git commit -m "$(cat <<'EOF'
T28: Add DeactivateRedundantMoveIteratorFactory

Single-variable move that flips TestCase.active=false. Acceptor decides
whether the deactivation is safe via incremental score evaluation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Implement MergeTestsMoveIteratorFactory (TDD)

**Why:** The transformative move — composes N feature changes + 1 active-flag change into a single composite move that single-variable ChangeMoves cannot reach.

**Files:**
- Create: `src/test/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactoryTest.java`
- Create: `src/main/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactory.java`

- [ ] **Step 5.1: Write the failing factory test**

Create `src/test/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactoryTest.java`:

```java
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
        JennySolution problem = buildSimpleProblem(4, 2);
        // 4 active, unpinned -> 4*3/2 = 6 unordered pairs (we report ordered pairs as the size).
        problem.getTestCases().get(0).setPinned(true);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        // 3 active unpinned, but A != B and ordered: 3 * 2 = 6
        assertEquals(6L, factory.getSize(sd));
        sd.close();
    }

    @Test
    void randomIteratorEmitsCompositeMoves() {
        JennySolution problem = buildSimpleProblem(4, 2);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        RandomGenerator rnd = RandomGeneratorFactory.getDefault().create(0L);

        Iterator<Move<JennySolution>> it = factory.createRandomMoveIterator(sd, rnd);
        // Sample first 3 moves and verify each is non-null
        for (int i = 0; i < 3; i++) {
            assertTrue(it.hasNext());
            Move<JennySolution> move = it.next();
            assertNotNull(move);
        }
        sd.close();
    }

    @Test
    void doingThenUndoingMoveRestoresState() {
        JennySolution problem = buildSimpleProblem(3, 3);
        InnerScoreDirector<JennySolution, HardSoftScore> sd = openScoreDirector(problem);

        MergeTestsMoveIteratorFactory factory = new MergeTestsMoveIteratorFactory();
        RandomGenerator rnd = RandomGeneratorFactory.getDefault().create(42L);

        Iterator<Move<JennySolution>> it = factory.createRandomMoveIterator(sd, rnd);
        Move<JennySolution> move = it.next();

        // Snapshot active flags + cell features before the move.
        List<Boolean> beforeActive = problem.getTestCases().stream()
                .map(TestCase::getActive).toList();
        List<Long> beforeFeatures = problem.getTestCells().stream()
                .map(c -> c.getFeature() == null ? -1L : c.getFeature().featureIndex())
                .toList();

        Move<JennySolution> undo = move.doMove(sd);
        sd.triggerVariableListeners();

        undo.doMove(sd);
        sd.triggerVariableListeners();

        for (int i = 0; i < beforeActive.size(); i++) {
            assertEquals(beforeActive.get(i), problem.getTestCases().get(i).getActive(),
                    "active flag not restored at index " + i);
        }
        for (int i = 0; i < beforeFeatures.size(); i++) {
            long restored = problem.getTestCells().get(i).getFeature() == null
                    ? -1L : problem.getTestCells().get(i).getFeature().featureIndex();
            assertEquals(beforeFeatures.get(i), restored,
                    "cell feature not restored at index " + i);
        }
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
```

- [ ] **Step 5.2: Verify the test fails with "class not found"**

Run: `mvn -q test -Dtest=MergeTestsMoveIteratorFactoryTest`
Expected: COMPILATION ERROR — `cannot find symbol class MergeTestsMoveIteratorFactory`.

- [ ] **Step 5.3: Implement the factory**

Create `src/main/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactory.java`:

```java
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
        // For each cell of A, decide whether to overwrite with B's matching cell.
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
```

- [ ] **Step 5.4: Run the factory test**

Run: `mvn -q test -Dtest=MergeTestsMoveIteratorFactoryTest`
Expected: ALL THREE TESTS PASS.

- [ ] **Step 5.5: Run full default test suite to confirm no regression**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all 26+3+3 = 32 tests pass.

- [ ] **Step 5.6: Commit**

```bash
git add src/main/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactory.java \
        src/test/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactoryTest.java
git commit -m "$(cat <<'EOF'
T28: Add MergeTestsMoveIteratorFactory

Composite move that overwrites A's cells with B's where they differ and
deactivates B. The acceptor rejects merges that uncover any tuple.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Replace solverConfig.xml with multi-phase configuration

**Why:** Wire the new moves into a multi-phase solver: Tabu Search consolidation followed by Late Acceptance polish. This is the configuration that closes the gap to ≤116.

**Files:**
- Modify: `src/main/resources/solverConfig.xml`

- [ ] **Step 6.1: Replace solverConfig.xml**

Overwrite `src/main/resources/solverConfig.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<solver xmlns="https://timefold.ai/xsd/solver"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="https://timefold.ai/xsd/solver https://timefold.ai/xsd/solver/solver.xsd">

    <solutionClass>com.burtleburtle.jenny.domain.JennySolution</solutionClass>
    <entityClass>com.burtleburtle.jenny.domain.TestCase</entityClass>
    <entityClass>com.burtleburtle.jenny.domain.TestCell</entityClass>

    <scoreDirectorFactory>
        <constraintProviderClass>com.burtleburtle.jenny.solver.JennyConstraintProvider</constraintProviderClass>
    </scoreDirectorFactory>

    <!-- Phase 1: Consolidation. Aggressive Tabu Search using custom moves
         that target test-suite minimization directly. Terminates early if
         no improvement for 30s. -->
    <localSearch>
        <termination>
            <unimprovedSecondsSpentLimit>30</unimprovedSecondsSpentLimit>
            <secondsSpentLimit>60</secondsSpentLimit>
        </termination>
        <unionMoveSelector>
            <moveIteratorFactory>
                <moveIteratorFactoryClass>com.burtleburtle.jenny.solver.DeactivateRedundantMoveIteratorFactory</moveIteratorFactoryClass>
                <fixedProbabilityWeight>3.0</fixedProbabilityWeight>
            </moveIteratorFactory>
            <moveIteratorFactory>
                <moveIteratorFactoryClass>com.burtleburtle.jenny.solver.MergeTestsMoveIteratorFactory</moveIteratorFactoryClass>
                <fixedProbabilityWeight>3.0</fixedProbabilityWeight>
            </moveIteratorFactory>
            <changeMoveSelector>
                <entitySelector>
                    <entityClass>com.burtleburtle.jenny.domain.TestCell</entityClass>
                </entitySelector>
                <fixedProbabilityWeight>2.5</fixedProbabilityWeight>
            </changeMoveSelector>
            <moveIteratorFactory>
                <moveIteratorFactoryClass>com.burtleburtle.jenny.solver.RandomizeRowMoveIteratorFactory</moveIteratorFactoryClass>
                <fixedProbabilityWeight>1.5</fixedProbabilityWeight>
            </moveIteratorFactory>
        </unionMoveSelector>
        <acceptor>
            <entityTabuSize>7</entityTabuSize>
        </acceptor>
        <forager>
            <acceptedCountLimit>10</acceptedCountLimit>
        </forager>
    </localSearch>

    <!-- Phase 2: Refinement. Standard Late Acceptance polish using only
         single-variable moves to fine-tune the consolidated solution. -->
    <localSearch>
        <termination>
            <unimprovedSecondsSpentLimit>30</unimprovedSecondsSpentLimit>
            <secondsSpentLimit>60</secondsSpentLimit>
        </termination>
        <changeMoveSelector>
            <entitySelector>
                <entityClass>com.burtleburtle.jenny.domain.TestCell</entityClass>
            </entitySelector>
        </changeMoveSelector>
        <changeMoveSelector>
            <entitySelector>
                <entityClass>com.burtleburtle.jenny.domain.TestCase</entityClass>
            </entitySelector>
        </changeMoveSelector>
        <acceptor>
            <lateAcceptanceSize>400</lateAcceptanceSize>
        </acceptor>
        <forager>
            <acceptedCountLimit>1</acceptedCountLimit>
        </forager>
    </localSearch>

    <!-- Global solver-level safety cap: never run longer than 2 minutes. -->
    <termination>
        <spentLimit>PT2M</spentLimit>
    </termination>
</solver>
```

- [ ] **Step 6.2: Run default test suite to confirm config parses and existing tests pass**

Run: `mvn -q test`
Expected: BUILD SUCCESS. The smaller-input solver tests (SolverSmokeTest, SolutionVerificationTest, CliRegressionTest) all complete in their existing per-test budgets — Phase 1's 60s cap is the upper bound for one test, but practically these tiny inputs converge in milliseconds.

- [ ] **Step 6.3: Run the benchmark goal-line test**

Run: `mvn test -Dgroups=benchmark -Dtest=JennyBeatsBenchmarkTest`
Expected: PASS — active test count ≤ 116, 0 uncovered, wall time ≤ 130s.

If it fails, capture the active count printed and check:
- Did Phase 1 run for the full 60s? (look at solver INFO logs)
- Are the custom moves being selected? (logs show "step #N" with move type)
- Does Phase 2 reduce further? (compare end-of-phase-1 vs end-of-phase-2 active counts via Step 7's diagnostic)

- [ ] **Step 6.4: Commit**

```bash
git add src/main/resources/solverConfig.xml
git commit -m "$(cat <<'EOF'
T28: Multi-phase solver config with Tabu + Late Acceptance

Phase 1 Tabu Search uses DeactivateRedundant + MergeTests + ChangeMove +
RandomizeRow with weighted union selection. Phase 2 polishes with Late
Acceptance over standard ChangeMoves. Global 2-minute safety cap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Add per-phase reporting in SolverProfilingTest

**Why:** When tuning, we need to see the test count at the end of each phase to understand where the wins come from. Currently SolverProfilingTest only prints the final count.

**Files:**
- Modify: `src/test/java/com/burtleburtle/jenny/solver/SolverProfilingTest.java`

- [ ] **Step 7.1: Add a SolverEventListener that captures step end events**

In `SolverProfilingTest.java`, locate `profileNormalMode()`. Replace the solver invocation block (the lines from `Solver<JennySolution> solver = SolverFactory...` through `JennySolution solved = solver.solve(problem);`) with:

```java
        Solver<JennySolution> solver = SolverFactory.<JennySolution>create(config).buildSolver();

        // Track best-solution updates so we can see the test-count trajectory.
        java.util.List<long[]> trajectory = new java.util.ArrayList<>();
        long t0 = System.currentTimeMillis();
        solver.addEventListener(event -> {
            JennySolution s = event.getNewBestSolution();
            long active = s.getTestCases().stream().filter(TestCase::isActiveFlag).count();
            trajectory.add(new long[] { System.currentTimeMillis() - t0, active });
        });

        JennySolution solved = solver.solve(problem);
        System.out.println("Trajectory (ms, activeTests):");
        for (long[] point : trajectory) {
            System.out.printf("  %5d ms -> %d tests%n", point[0], point[1]);
        }
```

- [ ] **Step 7.2: Run profiling test**

Run: `mvn -q test -Dtest=SolverProfilingTest#profileNormalMode`
Expected: PASS, with a printed trajectory like:
```
Trajectory (ms, activeTests):
   1234 ms -> 131 tests
   2456 ms -> 129 tests
   ...
```

- [ ] **Step 7.3: Commit**

```bash
git add src/test/java/com/burtleburtle/jenny/solver/SolverProfilingTest.java
git commit -m "$(cat <<'EOF'
T28: Track best-solution trajectory in SolverProfilingTest

Print (elapsed_ms, active_test_count) for each new best solution so
we can see which phase contributes which improvement.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Update TASKS.md with completion status

**Files:**
- Modify: `TASKS.md`

- [ ] **Step 8.1: Add a new T28 entry for the Phase 6 multi-phase work**

In `TASKS.md`, locate the `## Phase 6 — performance optimization` heading. Append immediately after the existing T27 block:

```markdown
- [x] **T28** Multi-phase solver to beat jenny.c's 116 tests:
      Unpinned greedy + Tabu Search Phase 1 (DeactivateRedundantMove +
      MergeTestsMove + ChangeMove + RandomizeRowMove) + Late Acceptance
      Phase 2. **Achieved:** ≤116 tests, 0 uncovered, ≤130s wall.
      See `docs/superpowers/specs/2026-04-23-phase6-multi-phase-solver-design.md`
      for design rationale.
```

- [ ] **Step 8.2: Update the Project Summary section**

In `TASKS.md`, locate the line `- **Test count:** 131 vs 116 (13% higher)` under "Performance vs C jenny" (around line 156). Replace with the actual measurement from your benchmark run, e.g.:

```markdown
- **Test count:** 116 vs 116 (matched) — see T28
```

(Use the real number from the benchmark run; if you beat 116, write `XYZ vs 116 (beaten by N tests)`.)

- [ ] **Step 8.3: Run the full default test suite one final time**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all default tests pass (32 tests).

- [ ] **Step 8.4: Run the benchmark one final time and capture output**

Run: `mvn test -Dgroups=benchmark -Dtest=JennyBeatsBenchmarkTest 2>&1 | tail -20`
Expected: PASS, with the printed `benchmark: active=N, uncovered=0, elapsed=Mms` line confirming N ≤ 116.

- [ ] **Step 8.5: Commit and push branch**

```bash
git add TASKS.md
git commit -m "$(cat <<'EOF'
T28: Mark Phase 6 multi-phase solver complete in TASKS.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push -u origin phase6-approach2-multi-phase-solver
```

---

## Verification Checklist (read before declaring done)

- [ ] All 26 pre-existing tests still pass with `mvn -q test`
- [ ] 6 new tests pass (3 for DeactivateRedundant, 3 for MergeTests)
- [ ] `JennyBeatsBenchmarkTest` passes with `mvn test -Dgroups=benchmark`
- [ ] Active test count ≤ 116, uncovered = 0, wall time ≤ 130s on the self-test
- [ ] `solverConfig.xml` has two `<localSearch>` phases plus a global `<termination>` cap
- [ ] `JennyCli.java` greedy block no longer calls `setPinned(true)`; `-o` block still does
- [ ] All commits on the branch reference T28 for traceability

## If you get stuck

- **Move tests fail with NPE on `featureDescriptor`:** confirm the variable name matches `@PlanningVariable` exactly — `"feature"` for TestCell, `"active"` for TestCase.
- **Benchmark times out at 120s but doesn't beat 116:** raise Phase 1's `secondsSpentLimit` to 90s and Phase 2's `unimprovedSecondsSpentLimit` to 45s; rerun.
- **Benchmark hard-score is non-zero:** the test failed coverage. Check that the empty-slot loop in JennyCli still seeds `cell.setFeature(d.feature(0))` so they enter the solver in a consistent state.
- **Phase 1 reports tabu cycling warnings:** lower `entityTabuSize` from 7 to 5; if still cycling, swap for `<lateAcceptanceSize>200</lateAcceptanceSize>` in Phase 1's acceptor.

## Out of scope for this plan

- Optimizing the GreedyInitializer (already adequate at ~16% of runtime).
- Replacing the constraint provider (the existing constraints are correct).
- T25 help-text formatting (independent cosmetic work).
- Adding a third "Approach 3 fallback" config — that lives on a different branch if needed.
