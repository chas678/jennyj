# Phase 6 — Multi-Phase Solver to Beat jenny.c

**Status:** Draft — awaiting user review
**Branch:** `phase6-approach2-multi-phase-solver`
**Goal:** Match or beat jenny.c's 116 tests on the self-test benchmark
**Constraint priority:** Test count first; runtime budget flexible up to 120s

## Context

The current Timefold port produces 131 tests on the jenny self-test benchmark
(`-n3 4 4 3 3 3 3 3 3 4 3 3 4` with 13 `-w` constraints) — 13% more than
jenny.c's 116. Profiling shows Greedy init runs in ~16% of total time; the
remaining 84% is the local search, which produces *no improvement*.

Root cause: `JennyCli` (and `SolverProfilingTest`) calls `setPinned(true)` on
every TestCase produced by `GreedyInitializer`, plus `setPinned(true)` on each
of its TestCells. Pinned entities cannot be modified by Timefold, so the
solver can only operate on the empty slots appended after the greedy tests —
it has no way to deactivate redundant greedy tests or improve their feature
choices.

The fix is conceptually small (one flag), but unlocking optimization exposes a
secondary problem: standard `ChangeMove`s rarely deactivate a whole test in
one step, so the soft-score landscape is plateau-heavy. We need
problem-specific moves that operate at test-suite granularity.

## Approach

Multi-phase local search over an **unpinned** greedy warm-start, using two
new custom move iterator factories that target test-count reduction directly.

### Pipeline (one warm-start + two solver phases)

1. **Warm-start: Greedy init (existing, unchanged algorithmically).**
   Produces ~131 valid tests via `GreedyInitializer.buildInitialTests`. Tests
   are now created *unpinned* so Timefold can modify them.
2. **Solver Phase 1 — Consolidation (30–60s).** Aggressive search using
   `MergeTestsMoveIteratorFactory`, `DeactivateRedundantMoveIteratorFactory`,
   plus existing `RandomizeRowMoveIteratorFactory` and standard ChangeMoves.
   Tabu Search acceptor (entityTabuSize=7) with `acceptedCountLimit=10`.
   Terminates after 30s with no improvement, hard cap 60s.
3. **Solver Phase 2 — Refinement (30–60s).** Fine-tunes the consolidated
   solution with only standard ChangeMoves. Late Acceptance (size=400),
   `acceptedCountLimit=1`. Terminates after 30s with no improvement, hard
   cap 60s.

A global solver-level termination of `spentLimit=PT2M` (120s) acts as a safety
net.

### Custom move iterator factories

#### `DeactivateRedundantMoveIteratorFactory`

For each active, unpinned TestCase, emit one move that flips
`active = false`. The move is always *doable*; Timefold evaluates it via
incremental score calculation. The acceptor then rejects it when any allowed
tuple becomes uncovered (hard score worsens by N for N newly-uncovered
tuples) and accepts it when the deactivation is safe (soft score improves by
1, hard unchanged).

Mirrors the structure of the existing `RandomizeRowMoveIteratorFactory` —
roughly 30 lines of code, no `Moves.compose` needed (single-variable change).

**Why it matters:** Greedy initialization creates tests sequentially, often
producing later tests whose tuple coverage is fully redundant given earlier
ones. Without this move, Timefold needs a long sequence of feature changes
to reach a state where a test can be deactivated — usually impossible inside
late acceptance because every intermediate step has a worse score.

#### `MergeTestsMoveIteratorFactory`

Picks two active, unpinned TestCases A and B. Emits a composite move (via
`Moves.compose`) that:

1. For each dimension where A and B differ, randomly chooses to keep A's
   feature or replace it with B's.
2. Sets `B.active = false`.

If the merged A still covers all tuples that A∪B covered, the soft score
improves by 1 (one fewer active test) and the acceptor accepts. If A loses
coverage, hard worsens and the acceptor rejects.

Roughly 60 lines using `Moves.compose`. Mirrors
`RandomizeRowMoveIteratorFactory`'s `createRandomMoveIterator` pattern.

**Why it matters:** Two greedy-generated tests sometimes have feature
combinations whose union covers more than either alone, but neither is
redundant on its own. Reaching the merged state via single-feature
ChangeMoves requires N intermediate steps, each rejected because they
temporarily uncover tuples. A composite Merge move arrives at the goal in
one step.

#### `RandomizeRowMoveIteratorFactory` (existing, unchanged)

Re-rolls every cell of one TestCase. Useful as a diversification mechanism
once the easy consolidations are done.

### Solver configuration

`src/main/resources/solverConfig.xml` becomes (key sections):

```xml
<solver>
    <solutionClass>...JennySolution</solutionClass>
    <entityClass>...TestCase</entityClass>
    <entityClass>...TestCell</entityClass>
    <scoreDirectorFactory>
        <constraintProviderClass>...JennyConstraintProvider</constraintProviderClass>
    </scoreDirectorFactory>

    <!-- Phase 1: Consolidation -->
    <localSearch>
        <termination>
            <unimprovedSecondsSpentLimit>30</unimprovedSecondsSpentLimit>
            <secondsSpentLimit>60</secondsSpentLimit>
        </termination>
        <unionMoveSelector>
            <moveIteratorFactory>
                <moveIteratorFactoryClass>...DeactivateRedundantMoveIteratorFactory</moveIteratorFactoryClass>
                <fixedProbabilityWeight>3.0</fixedProbabilityWeight>
            </moveIteratorFactory>
            <moveIteratorFactory>
                <moveIteratorFactoryClass>...MergeTestsMoveIteratorFactory</moveIteratorFactoryClass>
                <fixedProbabilityWeight>3.0</fixedProbabilityWeight>
            </moveIteratorFactory>
            <changeMoveSelector>
                <entitySelector><entityClass>...TestCell</entityClass></entitySelector>
                <fixedProbabilityWeight>2.5</fixedProbabilityWeight>
            </changeMoveSelector>
            <moveIteratorFactory>
                <moveIteratorFactoryClass>...RandomizeRowMoveIteratorFactory</moveIteratorFactoryClass>
                <fixedProbabilityWeight>1.5</fixedProbabilityWeight>
            </moveIteratorFactory>
        </unionMoveSelector>
        <acceptor><entityTabuSize>7</entityTabuSize></acceptor>
        <forager><acceptedCountLimit>10</acceptedCountLimit></forager>
    </localSearch>

    <!-- Phase 2: Refinement -->
    <localSearch>
        <termination>
            <unimprovedSecondsSpentLimit>30</unimprovedSecondsSpentLimit>
            <secondsSpentLimit>60</secondsSpentLimit>
        </termination>
        <changeMoveSelector><entitySelector><entityClass>...TestCell</entityClass></entitySelector></changeMoveSelector>
        <changeMoveSelector><entitySelector><entityClass>...TestCase</entityClass></entitySelector></changeMoveSelector>
        <acceptor><lateAcceptanceSize>400</lateAcceptanceSize></acceptor>
        <forager><acceptedCountLimit>1</acceptedCountLimit></forager>
    </localSearch>

    <termination><spentLimit>PT2M</spentLimit></termination>
</solver>
```

### The unpinning fix

In `JennyCli.java`, where greedy tests are constructed for the planning
problem:

```java
// Remove these lines:
tc.setPinned(true);
cell.setPinned(true);
```

Greedy tests retain their assigned features as a warm-start, but Timefold is
free to modify or deactivate them. `SolverProfilingTest` needs the same
edit.

`@PlanningPin` on TestCase remains in the domain model — it's still used by
`-o` imported tests, which legitimately must not be modified.

## Constraints (no changes)

The existing constraint set is correct:

- `coverAllTuples` (hard, `ifNotExists`) — penalty 1 per uncovered tuple
- `respectWithouts` (hard, `join`) — penalty 1 per without-violation
- `minimizeActiveTests` (soft, `forEach + filter`) — penalty 1 per active test

DeactivateRedundantMove naturally improves the soft score when safe;
MergeTestsMove naturally improves the soft score by 1 per successful merge.
No constraint weighting or shadow-variable tricks needed.

## Project layout

New files:
- `src/main/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactory.java`
- `src/main/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactory.java`
- `src/test/java/com/burtleburtle/jenny/solver/DeactivateRedundantMoveIteratorFactoryTest.java`
- `src/test/java/com/burtleburtle/jenny/solver/MergeTestsMoveIteratorFactoryTest.java`
- `src/test/java/com/burtleburtle/jenny/solver/JennyBeatsBenchmarkTest.java` (the goal-line test)

Modified files:
- `src/main/resources/solverConfig.xml` (multi-phase configuration above)
- `src/main/java/com/burtleburtle/jenny/cli/JennyCli.java` (remove `setPinned(true)` on greedy outputs)
- `src/test/java/com/burtleburtle/jenny/solver/SolverProfilingTest.java` (same)

## Verification

1. **All 26 existing tests still pass.**
2. **Per-move unit tests.** `DeactivateRedundantMoveIteratorFactoryTest` and
   `MergeTestsMoveIteratorFactoryTest` verify:
   - Move size matches active TestCase count
   - Generated moves are doable on a fresh problem
   - Undo restores original state (Timefold's standard contract)
3. **`JennyBeatsBenchmarkTest`** (the new goal-line test):
   - Runs the self-test input with the production solver config
   - Asserts: 0 uncovered tuples, ≤ 116 active tests, total wall-time ≤ 130s
   - Marked `@Tag("benchmark")` so it can be excluded from default `mvn test`
4. **Updated `SolverProfilingTest`** reports test count after each phase, so
   we can see Phase 1 vs Phase 2 contributions.
5. **Manual head-to-head** via `--bench` mode against jenny.c.

## Risks and fallback

- **Risk:** Custom moves may be expensive to evaluate due to coverage
  recomputation in the constraint streams. **Mitigation:** Profile after
  Phase 1 lands; if `MergeTests` is too slow, narrow its sample size with a
  `selectionFilter` that only considers test pairs with overlapping coverage.
- **Risk:** Tabu Search may cycle or get stuck. **Mitigation:** Phase 2's
  Late Acceptance polish covers cases where Phase 1 plateaus.
- **Fallback:** If after exhausting tuning options the test count stays
  above 116, switch to Approach 1 (simpler tuning) or Approach 3 (constraint
  weighting). The branch `phase6-approach2-multi-phase-solver` keeps this
  work isolated; `main` remains at the current production state for an easy
  pivot.

## Out of scope

- Replacing `GreedyInitializer` with a Timefold construction heuristic
  (FIRST_FIT_DECREASING) — this was rejected as Approach 2's goal is to
  improve on the greedy warm-start, not replace it.
- Optimizing greedy initialization speed — already acceptable at ~16% of
  runtime.
- T25 (help-text formatting) — independent cosmetic task, unrelated.
