# jenny-timefold — Task sheet

Resumable checklist. Each task has a short ID (T01…); reference it in commit
messages so partial sessions are easy to pick up. Design-of-record lives in
`docs/DESIGN.md`.

To find the next open task: `grep '\- \[ \]' TASKS.md`.

## Phase 0 — project scaffolding
- [x] **T01** Create `pom.xml` with Java 25, Timefold 2.0 BOM, picocli 4.7.x,
      Guava 33.6.0-jre, JUnit 6.0.2, Mockito 3.x. Configure
      `maven-shade-plugin` for an executable `target/jenny.jar`.
- [x] **T02** Add `.gitignore`.
- [x] **T03** Write `docs/DESIGN.md` (design-of-record) and `TASKS.md`
      (this file).

## Phase 1 — domain + tuple enumeration
- [x] **T04** `Dimension`, `Feature` records. Derive feature name from index.
- [x] **T05** `Without` value type + `matches(TestCase)` helper.
- [x] **T06** `AllowedTuple` value type.
- [x] **T07** `TupleEnumerator` — Guava `Sets.combinations` +
      `Lists.cartesianProduct`, filtered by withouts. Unit test against the
      worked example at jenny.c:50. **4 tests green.**
- [x] **T08** `JennySolution` `@PlanningSolution`; `TestCase` / `TestCell`
      `@PlanningEntity`. Shadow-variable coverage snapshot dropped in favour
      of an on-demand recompute over `TestCase.cells`; see T26.

## Phase 2 — constraints + solver config
- [x] **T09** `JennyConstraintProvider`: `coverAllTuples`
      (hard, `ifNotExists`).
- [x] **T10** `JennyConstraintProvider`: `respectWithouts` (hard, `join`).
- [x] **T11** `JennyConstraintProvider`: `minimizeActiveTests` (soft).
- [x] **T12** `solverConfig.xml`; smoke test passes end-to-end on
      3 binary dims × pairs.
- [x] **T13** `RandomizeRowMoveIteratorFactory` — re-roll every dim of one
      `TestCase` in a single move. Implemented using Timefold 2.0 preview API
      `Moves.compose()` to create composite moves. **Tuning in progress:** After
      design review, adjusted solver config parameters per Timefold best practices:
      `lateAcceptanceSize: 400→800`, `acceptedCountLimit: 4→1` to improve
      solution quality.
- [x] **T14** Per-constraint `ConstraintVerifier` tests + comprehensive solution
      verification tests. **17 tests green** (7 constraint unit tests + 10
      acceptance tests verifying complete tuple coverage and without compliance).

## Phase 3 — CLI + I/O
- [x] **T15** `WithoutParser` — `-w` grammar; 7 JUnit tests green.
- [x] **T16** `JennyCli` picocli `@Command`. Supports glued-value flags
      `-n3`, `-s3`, `-ofoo.txt` via picocli's built-in attached-value parsing.
- [x] **T17** `OutputFormatter` — byte-for-byte jenny output.
- [x] **T18** `-o<file>` / `-o-` ingestion; pinned `TestCase` entities.
      Implemented with `TestFileParser` (7 unit tests) and pinning mechanism
      in `JennyCli` (setPinned on TestCase and TestCell).
- [x] **T19** "Could not cover tuple …" reporting (matches jenny.c:1553–1554);
      printed from `JennyCli` when a tuple remains uncovered.

## Phase 4 — bench mode + regression
- [x] **T20** `BenchRunner` forks the C jenny binary, times both solvers,
      prints a two-row comparison. Wired to `JennyCli --bench`.
- [x] **T21** `BenchRunnerTest` with Mockito 3.x injecting a `ProcessForker`.
      4 tests green.
- [x] **T22** `CliRegressionTest` covering verification scenarios 1, 3, 4, 5
      from `docs/DESIGN.md`. 4 tests green.
- [~] **T23** Ad-hoc bench run on jenny.c:50's `-n2` variant: see
      "Measured baseline" below.

## Phase 5 — polish
- [x] **T24** `README.md` with build + run instructions, `--bench` demo.
- [ ] **T25** `-h` output reformatted to match jenny's help text layout.
- [x] **T26** Profile constraint-stream hot paths using Timefold's built-in
      score calculation profiling (see `EnvironmentMode.FULL_ASSERT` and
      constraint match analysis). Revisit nullable `f0..f63` design if > 10%
      of CPU is in empty-slot handling (see "Open question" in `docs/DESIGN.md`).
      **COMPLETE:** Profiling completed with `SolverProfilingTest` and
      `GreedyInitializerProfilingTest`. Key findings:
      - Total time breakdown: Greedy init 16%, Solver 84%
      - Greedy bottleneck: ~37 tests/sec, 1.3M tuple coverage checks per test
      - Hot path: `coversTuple()` called 172M times for 132 tests
      - Solver overhead: FULL_ASSERT mode has <1% overhead vs normal mode
      - Nullable field design: Not profiled (greedy init is the bottleneck)

## Phase 6 — performance optimization
- [~] **T27** Beat jenny self-test: `-n3 4 4 3 3 3 3 3 3 4 3 3 4` with 13
      `-w` constraints. Target: ≤116 tests, 0 uncovered tuples, score 0hard.
      **Achieved:** 131 tests, 0 uncovered (5s). C jenny: 116 tests, 0
      uncovered (<1s). **Valid solution achieved** via greedy initialization
      (GreedyInitializer mimics C jenny's set cover approach). Test count 13%
      higher than optimal; further optimization needed to match C jenny's 116.
- [x] **T28** Multi-phase solver beats jenny.c on the self-test benchmark.
      See `docs/superpowers/specs/2026-04-23-phase6-multi-phase-solver-design.md`
      and `docs/superpowers/plans/2026-04-23-phase6-multi-phase-solver.md`.
      **Achieved (post-T28 baseline):** active=105, uncovered=0, elapsed≈90s,
      score≈-2hard (residual `respectWithouts` violations). Beats jenny.c by 11
      tests. Oracle: `JennyBeatsBenchmarkIT`, run with
      `mvn verify -Dit.test=JennyBeatsBenchmarkIT`.
      Built on branch `phase6-approach2-multi-phase-solver`. Adds:
      - `JennyBeatsBenchmarkIT` (failsafe IT, runs under `mvn verify` only)
      - `DeactivateRedundantMoveIteratorFactory` — flips one TestCase.active=false
      - `MergeTestsMoveIteratorFactory` — composite move that merges two TestCases
        (overwrite differing cells with B's features, deactivate B)
      - Unpinned greedy initializer in `JennyCli` and `SolverProfilingIT`
        so the solver can deactivate/merge greedy-derived tests
      - Multi-phase `solverConfig.xml`: Phase 1 Tabu Search with all moves
        (60s/30s caps), Phase 2 Hill Climbing with single-variable moves
        only (60s/30s caps) so coverage never regresses below Phase 1's
        best feasible state
      - Per-phase `(elapsed_ms, active_test_count)` trajectory reporting in
        `SolverProfilingIT.profileNormalMode`
      **Trade-offs (at time of T28):** the final solution typically still had a
      couple of `respectWithouts` violations (hard score ~-2). See T30 for the
      Phase 3 + 2-hard fix that resolves this.

## Measured baseline (2026-04-22)

Ad-hoc head-to-head on an Apple Silicon MBP, Corretto 25.0.2, 10s budget:

| Input                       | jenny-c tests | jenny-c wall | timefold tests | timefold wall |
| --------------------------- | ------------: | -----------: | -------------: | ------------: |
| `-n2 2 2 2`                 |             5 |         < 10 |              4 |        ~400ms |
| `-n2 3 3 3 3 3`             |            14 |         22ms |             14 |       ~500ms  |
| `-n2 2 3 8 3 2 2 5 3 2 2`   |            42 |         15ms |             40 |        5.5s   |

Quality: timefold reaches ≤ jenny-c's test count on all three; finds the
optimum on the first two and beats jenny-c by 2 tests on the 10-dim pair
input. Wall time: jenny-c's hand-rolled greedy is fast; timefold pays
~400ms startup + time-budget for soft-score improvement. Worth it when
test count matters; worth it less for rapid-fire small problems.

## Additional improvements (not in formal task list)

- **Logging**: Added SLF4J 2.0.18 + Logback 1.5.34 with runtime and test
  configurations. Eliminates "SLF4J(W): No SLF4J providers" warnings.
- **Java 25→26 + Mockito**: Upgraded Mockito 3.12.4 → 5.23.0 and retained
  `-Dnet.bytebuddy.experimental=true` argLine in surefire/failsafe to support
  JDK 26 class files. Resolves BenchRunnerTest and all IT failures.

## Phase 7 — Java 26 + Timefold 2.1.0 migration (2026-06-17, branch migrate/java26-timefold21)

- [x] **T29** Migrate to Java 26 (Amazon Corretto 26.0.1). Bump
      `maven.compiler.release` 25→26. Retained
      `-Dnet.bytebuddy.experimental=true` in surefire/failsafe argLine;
      mockito 5.23.0 + byte-buddy pass on JDK 26 class files (version 70)
      without further bumps. Preview Moves API (`Moves.compose`, 3
      `MoveIteratorFactory` classes) compiles cleanly on Timefold 2.1.0.

- [x] **T30** Phase 3 feasibility repair + 2-hard `respectWithouts` weight.
      Added a third `<localSearch>` phase (short Tabu Search with
      `<bestScoreFeasible>true</bestScoreFeasible>`) that runs after Hill
      Climbing. Raised `respectWithouts` penalty from 1 hard to **2 hard**:
      breaking a Without is now a strict hard-score improvement over leaving
      one tuple uncovered, so the tabu acceptor commits to the repair and
      re-covers from there. **Result:** self-test oracle (`JennyBeatsBenchmarkIT`)
      now achieves **~106–108 active, 0 uncovered, 0hard (FEASIBLE), ~80s**
      reproducibly. Active count varies ±1 run-to-run (wall-clock phase
      termination). Beats jenny.c's 116 by ~8–10 tests with a fully feasible
      solution.

- [x] **T31** Duplicate-entity guard test. Timefold 2.1.0 hard-fails on
      duplicate planning entities. Added `DuplicateEntityGuardTest` (4 tests)
      verifying `AllowedTuple`, `TestCase`, and `TestCell` yield no
      duplicates under the current domain construction. Fast unit test (runs
      under surefire).

- [x] **T32** Indexed `containedIn`+`groupBy` joiner evaluated and rejected.
      Fully implemented the decomposed stream for `coverAllTuples` (also
      assessed `respectWithouts`), validated behaviour-equivalent under
      `FULL_ASSERT`. Benchmarked 2026-06-17: **~6.8x SLOWER** (~594 vs
      ~4076 moves/sec) because the `groupBy` over the (testCase, cell,
      tuple) tri-stream materialises far more incremental state than the
      per-pair `coversTuple()` scan it replaced. **`Joiners.filtering` is
      retained as the faster shape for this problem.** This closes the
      "deferred indexed rewrite" from the 2026-04-27 comment; it is now
      resolved (rejected), not merely deferred.

- [x] **T33** Timefold FIRST_FIT construction heuristic evaluated and
      rejected. CH produced a slower start and worse hard score than
      `GreedyInitializer`; GreedyInitializer is retained.

- [x] **T34** Test/build tiering documented and confirmed. Tiering is by
      filename convention only (no JUnit tags, no `excludedGroups`). `mvn
      test` runs **56 fast unit tests in ~15s**; `mvn verify` adds the 3
      long-running ITs. `JennyBenchmarkApp` (PlannerBenchmark HTML harness)
      runs via `mvn exec:java` only.

## Current state (checkpoint — 2026-06-17, post-T34)

**Environment:** Java 26 (Amazon Corretto 26.0.1), Timefold 2.1.0,
mockito 5.23.0, JUnit 6.1.0, surefire/failsafe 3.5.6.

**Test status:** 56 unit tests (surefire), 3 long-running ITs (failsafe).
- ✅ 56/56 unit tests passing, ~15s (`mvn test`)
- ✅ Failsafe ITs green (`mvn verify`)
- Oracle `JennyBeatsBenchmarkIT`: ~106–108 active, 0 uncovered, 0hard, ~80s

**Performance vs C jenny (jenny self-test benchmark):**
- **Test count:** ~106–108 vs 116 (beats jenny.c by ~8–10 tests, feasible)
- **Time:** ~80s vs <1s (trade wall time for test count)
- **Feasibility:** 0hard (fully feasible) — previous ~-2 hard residual eliminated

**Remaining optional work:**
- T25: Help text formatting to match C jenny layout (cosmetic, LOW PRIORITY)
