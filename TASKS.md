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
- [ ] **T13** `RandomizeRowMoveIteratorFactory` — re-roll every dim of one
      `TestCase` in a single move.
- [ ] **T14** Per-constraint `ConstraintVerifier` tests (JUnit 6.0.2).

## Phase 3 — CLI + I/O
- [x] **T15** `WithoutParser` — `-w` grammar; 7 JUnit tests green.
- [x] **T16** `JennyCli` picocli `@Command`. Supports glued-value flags
      `-n3`, `-s3`, `-ofoo.txt` via picocli's built-in attached-value parsing.
- [x] **T17** `OutputFormatter` — byte-for-byte jenny output.
- [ ] **T18** `-o<file>` / `-o-` ingestion; pinned `TestCase` entities.
      **Deferred — CLI accepts `-o` but doesn't use it yet.**
- [x] **T19** "Could not cover tuple …" reporting (matches jenny.c:1553–1554);
      printed from `JennyCli` when a tuple remains uncovered.

## Phase 4 — bench mode + regression
- [x] **T20** `BenchRunner` forks the C jenny binary, times both solvers,
      prints a two-row comparison. Wired to `JennyCli --bench`.
- [x] **T21** `BenchRunnerTest` with Mockito 3.x injecting a `ProcessForker`.
      4 tests green.
- [ ] **T22** `CliRegressionTest` covering verification scenarios 1, 3, 4, 5
      from `docs/DESIGN.md`.
- [~] **T23** Ad-hoc bench run on jenny.c:50's `-n2` variant: see
      "Measured baseline" below.

## Phase 5 — polish
- [ ] **T24** `README.md` with build + run instructions, `--bench` demo.
- [ ] **T25** `-h` output reformatted to match jenny's help text layout.
- [ ] **T26** Profile constraint-stream hot paths; revisit nullable
      `f0..f63` design if > 10% of CPU is in empty-slot handling (see the
      "Open question" in `docs/DESIGN.md`).

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

## Resume notes (checkpoint — 2026-04-22)

What works right now: scaffolding (T01–T03), the non-planning domain
(`Dimension`, `Feature`, `Without`, `AllowedTuple`), and the
`TupleEnumerator` with 4 green JUnit tests. `mvn test` passes.

What's in the tree but doesn't compile yet: `TestCase`, `TestCell`,
`JennySolution`, `FeatureShadowListener`, `JennyConstraintProvider`.
`solverConfig.xml` is written. These were drafted against the Timefold 1.x
API shape from memory; Timefold 2.0 moved/renamed several pieces.
**Concrete findings from `javap` on
`ai.timefold.solver:timefold-solver-core:2.0.0`:**

- `PlanningId` is at `ai.timefold.solver.core.api.domain.common.PlanningId`
  (not `.domain.lookup` as drafted).
- Score classes have been flattened: use
  `ai.timefold.solver.core.api.score.HardSoftScore` (no more
  `buildin.hardsoftlong.HardSoftLongScore` — there is no separate long
  variant in 2.0's public API). Penalties use the `long`-accepting
  `penalizeLong(HardSoftScore, ToLongFunction)` overloads.
- `@ShadowVariable` in 2.0 has a **different shape**. It now reads:
  `@ShadowVariable(supplierName = "computeX")` and the shadow value is
  produced by a method annotated `@ShadowSources({"path.to.source"})`.
  The old `variableListenerClass` / `sourceVariableName` /
  `sourceEntityClass` attributes are gone; Timefold 2.0 discovers
  dependencies from the method annotation instead.
- There is no public `VariableListener` interface any more — the
  `impl` package still has one but `api` does not. The declarative
  supplier-method pattern replaces it for most cases.

**Next step (T08-redo)**: rewrite `TestCase.featuresByDim` as a
`@ShadowVariable(supplierName = "recomputeFeaturesByDim")` paired with a
`@ShadowSources({"testCells[*].feature"})` supplier method on `TestCase`
that pulls from an `@InverseRelationShadowVariable List<TestCell> testCells`
collection. Delete `FeatureShadowListener.java`. Then re-compile; the
constraint provider should work once `HardSoftScore` imports are fixed.

Maven daemon is installed (`mvnd`); plain `mvn` is not. Java is Corretto
25.0.2. The C jenny reference lives at `~/src/jenny/jenny.c` (read-only
behavioral spec; not part of this repo).
