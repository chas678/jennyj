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
- [ ] **T26** Profile constraint-stream hot paths using Timefold's built-in
      score calculation profiling (see `EnvironmentMode.FULL_ASSERT` and
      constraint match analysis). Revisit nullable `f0..f63` design if > 10%
      of CPU is in empty-slot handling (see "Open question" in `docs/DESIGN.md`).
      **Note:** Profile before optimizing; design review confirmed current
      approach is sound.

## Phase 6 — performance optimization
- [~] **T27** Beat jenny self-test: `-n3 4 4 3 3 3 3 3 3 4 3 3 4` with 13
      `-w` constraints. Target: ≤116 tests, 0 uncovered tuples, score 0hard.
      **Achieved:** 131 tests, 0 uncovered (5s). C jenny: 116 tests, 0
      uncovered (<1s). **Valid solution achieved** via greedy initialization
      (GreedyInitializer mimics C jenny's set cover approach). Test count 13%
      higher than optimal; further optimization needed to match C jenny's 116.

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

- **Logging**: Added SLF4J 2.0.17 + Logback 1.5.32 with runtime and test
  configurations. Eliminates "SLF4J(W): No SLF4J providers" warnings.
- **Java 25 compatibility**: Upgraded Mockito 3.12.4 → 5.14.2 and enabled
  ByteBuddy experimental mode to support Java 25 class files (version 69).
  Resolves BenchRunnerTest failures.

## Current state (checkpoint — 2026-04-23, post-T13)

**What works:** The solver is fully functional and produces valid solutions.
All phases 0–4 complete. Phase 5 (polish) has T24 done.

**Test status:** 26 tests total
- ✅ 26 tests passing (100% green)
- All core functionality tests verified
- BenchRunnerTest Mockito/Java 25 issue resolved

**Core functionality:**
- Domain model with shadow variables (Timefold 2.0 API)
- Constraint provider with three constraints (hard: coverage + withouts, soft: minimize)
- CLI with picocli (supports `-n`, `-s`, `-w`, positional dims, `--bench`)
- Output formatter (byte-compatible with jenny.c)
- Tuple enumerator (Guava-based)
- Comprehensive test suite verifying complete tuple coverage and without compliance

**Remaining work:**
- **T13 tuning** (complete): Applied solver config adjustments (lateAcceptanceSize
  400→800, acceptedCountLimit 4→1); all tests passing
- **T25** (polish): Help text formatting
- **T26** (optimization): Profiling (use built-in Timefold profiling) and
  nullable field optimization if needed
- **Construction heuristic** (future): Consider alternatives to FIRST_FIT_DECREASING:
  FIRST_FIT (simpler), WEAKEST_FIT (for coverage problems),
  ALLOCATE_ENTITY_FROM_QUEUE (custom ordering)

**Next recommended task:** T18 `-o` file ingestion for seeding solver with
existing tests, or T26 profiling to identify bottlenecks and tune T13.

**Environment:** Java 25 (Corretto 25.0.2), mvnd installed, C jenny reference
at `~/src/jenny/jenny.c` for behavioral comparison.
