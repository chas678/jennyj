# jenny-timefold

A high-performance pairwise (and N-wise) test suite generator. Drop-in CLI
compatible with Bob Jenkins' classic [`jenny`][jenny], but built on the
[Timefold Solver][timefold] constraint-optimisation engine. By driving the
search with a **multi-phase solver** (Tabu Search consolidation, a
deterministic shrink pass, Hill Climbing refinement, then a final shrink +
recovery pass) over a set of problem-specific moves, it produces **smaller,
feasible test suites** than the original C version on the same inputs.

```
$ ./jenny/jenny -n3 4 4 3 3 3 3 3 3 4 3 3 4 -w1abc2d -w1d2abc -w6ab7bc -w6b8c \
          -w6a8bc -w6a9abc -w6a10ab -w11a12abc -w11bc12d -w4c5ab \
          -w1a3a -w1a9a -w3a9c | wc -l
116

$ java -jar target/jenny.jar -n3 -s0 4 4 3 3 3 3 3 3 4 3 3 4 \
        -w1abc2d -w1d2abc -w6ab7bc -w6b8c -w6a8bc -w6a9abc \
        -w6a10ab -w11a12abc -w11bc12d -w4c5ab -w1a3a -w1a9a -w3a9c \
        | grep -c '^ '
99
```

Both tools print one line per generated test (and a `Could not cover tuple`
line — with **no** leading space — for any uncoverable tuple, of which there
are none here). `jenny` (this port) writes its solver-phase progress to
**stderr**, so stdout carries only the test suite — `grep -c '^ '` and `wc -l`
agree here (the `grep` form additionally skips any `Could not cover` line). Use
`--bench` for an automatic side-by-side count + wall-time comparison.

The `99` above is one real, captured run of that exact command — re-running
it, even with the same `-s0` seed, does not reproduce it exactly: the local
search phases run for a bounded time budget and select moves via a seeded
RNG, so how far the search gets before its budget expires (and thus the
final count) is itself time-sensitive. Same-day samples of this exact
command (with and without an explicit `-j1`) ranged 99–104, mostly landing
on 100; see [Highlights](#highlights) for how `-j<K>` bounds — not
eliminates — that variance. (The example runs the source-build jar so the
count reproduces today; once installed via Homebrew the command is simply
`jenny …`.)

---

## Install

On **macOS or Linux** with [Homebrew](https://brew.sh/) (install Homebrew
first if you don't have it):

```bash
brew install chas678/jennyj/jenny
```

This puts a `jenny` command on your `PATH`. A JDK is pulled in automatically as
a dependency — there's no jar or JVM to manage. Verify it and generate your
first pairwise suite:

```bash
jenny --version
jenny -n2 4 2 5 2 5 2      # 6 dimensions -> a compact pairwise test set
```

Prefer to build it yourself? See [Build from source](#build-from-source).

---

## Why pairwise testing?

Software behaviour usually depends on many independent factors — browser,
OS, payment method, locale, feature flags — and the number of *full*
combinations is their product, which explodes fast: six factors with a
handful of values each already means hundreds of cases, and it only gets
worse. Testing every combination is rarely affordable.

Pairwise (and more generally *n*-way) testing rests on an empirical
observation: most defects are triggered by a single factor, or by the
interaction of just **two** factors — not by rare higher-order
combinations. So instead of every combination, you generate a much smaller
set of tests that still contains **every pair of values from every pair of
factors at least once**. That set — a *covering array* — routinely shrinks
the suite by an order of magnitude while keeping the bulk of its
fault-finding power. Raise `-n` to 3 to cover every triple, and so on.

### Real-world example

Suppose you're testing a feature across these configurations:

| Dimension        | Features                                   | Count |
|------------------|--------------------------------------------|------:|
| Operating System | Mac, Windows, iOS, Android                 |     4 |
| User State       | Logged in, Logged out                      |     2 |
| Browser          | Chrome, Edge, Brave, Firefox, Opera        |     5 |
| JavaScript       | Enabled, Disabled                          |     2 |
| Domain           | DE, SE, FI, DK, NO                         |     5 |
| Dark Mode        | On, Off                                    |     2 |

Exhaustive: 4 × 2 × 5 × 2 × 5 × 2 = **800 test cases**.
Pairwise: roughly **25 test cases** cover every two-dimension interaction.

```bash
# Original jenny.c (greedy hill-climbing)
./jenny/jenny -n2 4 2 5 2 5 2 | wc -l
# 28

# jenny-timefold (multi-phase constraint optimisation)
jenny -n2 4 2 5 2 5 2 | grep -c '^ '
# 25  (~11% fewer)
```

### Background reading

- Pairwise testing concepts: [pairwise.org](https://www.pairwise.org/)
- Original tool: [jenny.c by Bob Jenkins](https://burtleburtle.net/bob/math/jenny.html)
- Solver: [timefold.ai](https://timefold.ai/)

---

## Highlights

- **Beats jenny.c on the self-test benchmark:** ~100 active tests vs 116
  on `-n3 4 4 3 3 3 3 3 3 4 3 3 4` with 13 `-w` constraints, **0hard
  feasible**. The Tabu/Hill Climbing phases run for a bounded time budget
  and pick moves via a seeded RNG, so the exact count varies run-to-run —
  even with an identical seed, since how far the search gets before its
  budget expires is itself time-sensitive. Nine same-day samples of the
  intro command above (`-s0`, with and without an explicit `-j1`, which
  takes the identical code path) ranged 99–104 and mostly landed on 100.
  `-j8` (best of 8 replicas, see [Flags](#flags)) produced 100 and 101
  across two same-day runs (~37s each) — it lowers the odds of a high
  outlier but does not guarantee the minimum. `-j<K>` bounds — not
  eliminates — this variance.
- **CLI-compatible with jenny:** `-n`, `-s`, `-w`, `-o`, positional dim
  sizes — same attached-value form (`-n2`, `-w1a2b`) the C tool uses.
- **Multi-phase solver pipeline** (one config in `JennySolverFactory`, used by
  the CLI *and* every test): greedy initialisation; Phase 1 Tabu Search
  consolidation over six moves (including row-eviction); a deterministic shrink
  pass; Phase 2 Hill Climbing refinement that preserves coverage; a Phase 3 Tabu
  feasibility-repair pass; and a final shrink + recovery pass — driving the
  solution to **0hard** (no Without violations) and making "Could not cover
  tuple" output honest. See [Solver pipeline](#solver-pipeline).
- **Head-to-head bench mode:** `--bench` forks the C `jenny` binary on the
  same input and prints a comparison table.
- **Java 26 + Timefold 2.2.0** (Preview Moves API: `Moves.compose` + 4
  `MoveIteratorFactory` classes).

---

## Run

```bash
jenny -n3 -s0 4 4 3 3 3 3 3 3 4 3 3 4 \
      -w1abc2d -w1d2abc -w6ab7bc -w6b8c -w6a8bc -w6a9abc \
      -w6a10ab -w11a12abc -w11bc12d -w4c5ab -w1a3a -w1a9a -w3a9c
```

(From a source build the command is `java -jar target/jenny.jar` with the same
arguments.)

### Flags

| flag                      | meaning                                                       |
|---------------------------|---------------------------------------------------------------|
| `-n<k>`                   | tuple size, 1..32 (default 2)                                 |
| `-s<seed>`                | random seed (default 0)                                       |
| `-j<k>`                   | run K replicas in parallel, keep the smallest feasible suite (default 1, e.g. `-j8`); parallelism capped at available processors |
| `-w<spec>`                | forbidden combination, e.g. `-w1a2cd4ac` (repeatable)         |
| `-o<file>`                | seed with existing tests from FILE (or `-` for stdin)         |
| `-h`                      | help                                                          |
| `--version`               | print version and exit                                        |
| *positional*              | feature counts per dimension, in order (2..52 each)           |
| `--time-limit-seconds <s>`| solver wall-clock budget (default 60)                         |
| `--bench`                 | head-to-head against the C jenny binary                       |
| `--jenny-path <path>`     | location of the C binary (defaults to `$JENNY_BIN` then `~/src/jenny/jenny`) |

Short options use jenny's attached-value style: `-n3` not `-n=3` or
`-n 3`; `-w1a2b` not `-w 1a2b`; `-ofile.txt` not `-o file.txt`. Long
options use a **space** separator: `--time-limit-seconds 30` (the `=`
form is rejected to keep parser behaviour consistent with jenny.c).

### Head-to-head bench

```bash
jenny --bench --jenny-path jenny/jenny \
      -n2 2 3 8 3 2 2 5 3 2 2 --time-limit-seconds 10
```

Forks both solvers on the same input and prints a two-row comparison of
test count and wall time.

---

## A worked example

Suppose you're testing a web checkout across five independent factors, and
one launch-time business rule: gift cards are EU-only.

| # | Factor    | Features (`a`, `b`, `c`, …)        | Count |
|---|-----------|-------------------------------------|------:|
| 1 | Browser   | Chrome, Firefox, Safari, Edge        |     4 |
| 2 | Region    | US, EU, APAC                         |     3 |
| 3 | Payment   | Card, PayPal, Gift card              |     3 |
| 4 | Shipping  | Domestic, International              |     2 |
| 5 | Account   | Guest, Member                        |     2 |

Exhaustive coverage is 4 × 3 × 3 × 2 × 2 = **144** runs. The gift-card
restriction (`-w2a3c`: Region=US **and** Payment=Gift card forbidden;
`-w2c3c`: Region=APAC **and** Payment=Gift card forbidden) removes 2 of the
9 (Region, Payment) value-pairs, leaving **75** distinct allowed value-pairs
across all ten factor-pairs. Here's the actual run, straight from a source
build:

```bash
$ java -jar target/jenny.jar -n2 4 3 3 2 2 -w2a3c -w2c3c -s1
 1a 2b 3c 4a 5b
 1b 2b 3c 4b 5a
 1c 2b 3c 4b 5b
 1d 2b 3c 4a 5a
 1a 2c 3a 4b 5a
 1b 2b 3a 4a 5b
 1b 2c 3b 4a 5b
 1c 2c 3b 4a 5a
 1c 2a 3a 4a 5a
 1d 2b 3b 4b 5b
 1d 2c 3a 4b 5a
 1a 2a 3b 4b 5b
 1b 2a 3a 4a 5b
 1d 2a 3b 4b 5b
```

(Solver-phase INFO logs go to **stderr**, so stdout is just the test block;
`grep -c '^ '` gives an exact count: **14** active tests, 0 "Could not cover
tuple" lines, `0hard` score.)

Reading the tokens back through the model (dimension 1 = Browser, feature
`a` = Chrome, `b` = Firefox, …; dimension 3 `c` = Gift card; dimension 2
`b` = EU), the first three rows are:

| # | Browser | Region | Payment   | Shipping      | Account |
|---|---------|--------|-----------|---------------|---------|
| 1 | Chrome  | EU     | Gift card | Domestic      | Member  |
| 2 | Firefox | EU     | Gift card | International | Guest   |
| 3 | Safari  | EU     | Gift card | International | Member  |

Those 14 tests cover all 75 allowed value-pairs — 144 combinations' worth
of two-way interaction coverage in 14 runs — and, per the without,
**every row that exercises Gift card pairs it with Region=EU**; grepping
the full 14-row output for the forbidden tokens confirms it:

```bash
$ java -jar target/jenny.jar -n2 4 3 3 2 2 -w2a3c -w2c3c -s1 | grep -E '2a.*3c|2c.*3c'
# (no output — US/Gift card and APAC/Gift card never co-occur)
```

---

## Build from source

Prerequisites:

- **Java 26** (tested with Amazon Corretto 26.0.1)
- **Maven 3.9+** (or `mvnd`)
- *(Optional)* the C `jenny` binary for `--bench`. A pre-built arm64 macOS
  binary plus source ships in `jenny/`; build other architectures with:
  ```bash
  cc -O2 -o jenny/jenny jenny/jenny.c
  ```

```bash
mvn package
```

Produces `target/jenny.jar` — a shaded uber-JAR with all dependencies. Run it
with `java -jar target/jenny.jar <args>`. Releases are cut by tagging `vX.Y.Z`
(see [docs/RELEASING.md](docs/RELEASING.md)).

---

## Architecture

### Solver pipeline

`solver.JennySolverFactory.createConfig()` is the **single source of truth** for
the solver pipeline — the CLI, the `PlannerBenchmark` app, and every test build
the solver from it (there is no separate `solverConfig.xml`). Its per-phase
local-search budgets scale from `--time-limit-seconds`. The pipeline is five
stages plus a greedy warm-start:

1. **Greedy initialisation** (`bootstrap.GreedyInitializer`) — an AETG-style
   greedy port of the Go reference generator (`jennygo`'s `jenny.go`). Per
   emitted row: deterministically pick the most-constrained still-uncovered
   tuple as a seed (the one whose coordinates participate in the fewest
   remaining uncovered tuples), build several candidate rows that pin the
   seed's cells, repair any without violation by hill-climbing, then
   greedily maximize coverage of the free cells; keep the best candidate.
   Coverage counting is postings-indexed (`(dimension, feature) -> tuple
   ids`), so counting a row's new coverage touches only tuples sharing one
   of its cells rather than scanning the full tuple list. A cheap
   coverage-preserving `removeRedundant` pass runs at the end.
2. **Phase 1: Tabu consolidation** — Tabu Search over a weighted union of
   six move types:
   - `ChangeMoveSelector` over `TestCell.feature` (single-cell value flip)
   - `ChangeMoveSelector` over `TestCase.active` (whole-row toggle)
   - `RandomizeRowMoveIteratorFactory` (re-roll all cells of one row)
   - `DeactivateRedundantMoveIteratorFactory` (flip one row's `active=false`)
   - `MergeTestsMoveIteratorFactory` (composite move: overwrite A's cells
     with B's where they differ, then deactivate B)
   - `EvictRowMoveIteratorFactory` (composite move: rehome every tuple a
     candidate row uniquely covers onto another active row via a legal
     single-cell flip, then deactivate the row — only emitted when every
     uniquely-covered tuple can be rehomed)

   Tuned for the self-test benchmark: `entityTabuSize=7`,
   `acceptedCountLimit=10` (constants validated by `JennyBeatsBenchmarkIT`).
3. **Custom phase: deterministic shrink** (`solver.ShrinkPhaseCommand`) — a
   coverage- and without-safe port of the Go reference's `removeRedundant` +
   `localSearchReduce` (`optimize.go`). Deactivates any unpinned row whose
   tuples are all covered elsewhere, then, for rows the first pass can't
   drop, rehomes each uniquely-covered tuple onto another row via a
   single-cell flip (full rollback on failure) before deactivating it. Runs
   to completion (no time budget); because it can only shrink or hold the
   hard/coverage score, it turns each evicted row into a guaranteed
   soft-score improvement that Tabu Search alone might not assemble.
4. **Phase 2: Hill Climbing refinement** — strict-improvement acceptor
   over single-variable moves. Phase 2 cannot worsen the score, so any
   coverage Phase 1 broke gets repaired without back-sliding.
5. **Phase 3: Feasibility repair** — a short Tabu Search over single-variable
   change moves (`TestCell` + `TestCase`). Terminates the moment the best
   solution is feasible (`bestScoreFeasible=true`) or after a short unimproved
   budget. Paired with the 2-hard `respectWithouts` weight (see Constraint
   model), breaking a Without violation is a strict hard-score improvement, so
   the repair holds and the solver re-covers from there. Drives solutions to
   **0hard** reliably. As a backstop the CLI also gates its output on
   `hardScore==0`, so a suite is never printed with a residual violation even if
   the time budget is exhausted (it exits non-zero with a diagnostic instead).
6. **Custom phase: final shrink + recovery** — `ShrinkPhaseCommand` runs
   again (the repair phases may have opened up new deletable rows), then
   `solver.RecoverPhaseCommand` — a port of the Go reference's `recover` —
   recomputes true coverage from the active rows and, for every allowed
   tuple still uncovered, spends a large randomized budget
   (`trySatisfy`/`obeyWithouts`) trying to build a complete, without-obeying
   test that covers it, filling a spare row and activating it on success.
   Only tuples that survive that budget (or find no spare row left) are
   reported as `Could not cover tuple` — this is what makes that output
   honest rather than an artifact of the search giving up early.

### Constraint model

The same three constraints are implemented **twice**. The score director
`JennySolverFactory` wires is `solver.JennyIncrementalScoreCalculator` — a
hand-rolled
`IncrementalScoreCalculator` with flat coverage-array bookkeeping ported
from the Go reference, chosen for speed over the constraint-stream form.
`solver.JennyConstraintProvider` implements the identical constraints
declaratively and is kept only as a parity/testing oracle: it's
cross-checked against the calculator under `FULL_ASSERT` in
`IncrementalScoreParityIT` and is never wired into a live solve.

| Constraint            | Score level       | Meaning                                            |
|-----------------------|-------------------|----------------------------------------------------|
| `coverAllTuples`      | `-1` HARD per     | Every allowed tuple must be covered by some active row |
| `respectWithouts`     | **`-2` HARD per** | No active row may match any forbidden combination    |
| `minimizeActiveTests` | `-1` SOFT per     | Minimise the number of active rows                  |

The 2-hard weight on `respectWithouts` is intentional: breaking a Without
violation is always a strict hard-score improvement over leaving one tuple
uncovered, so Phase 3's tabu acceptor commits to the repair rather than
oscillating on the stuck row.

`AllowedTuple` is the planning fact, `TestCase` (`active`) and `TestCell`
(`feature`) are the planning entities. `TestCase.featuresByDim` is a
shadow variable Timefold maintains automatically — it gives O(1)
dimension → feature lookups in the coverage check, mirroring the manual
`assignmentMap` pattern from sibling implementations. `JennyConstraintProvider`
joins `TestCase`/`AllowedTuple`/`Without` with `Joiners.filtering` rather
than an indexed `Joiners.equal` — tuple coverage is a multi-dimensional
subset-match with no single (Dimension, Feature) key extractable from both
sides, and Timefold has no indexed "superset" joiner for that shape.

### Performance optimisations

- Hashcode caching on `AllowedTuple` (called millions of times as a
  constraint-stream `HashMap` key)
- `CoverageUtil` consolidates the `coversTuple` check used by the
  constraint provider, the greedy initialiser, and any in-test recount
- `GreedyInitializer`'s postings index (`(dimension, feature) -> tuple ids`)
  plus a compacted array of still-uncovered tuple ids means each candidate
  row only re-scans tuples that actually share one of its cells, instead of
  the whole allowed-tuple list
- `TestCase.featuresByDim` shadow variable → O(1) constraint-time lookups
  with no manual setter upkeep
- `JennyIncrementalScoreCalculator`'s flat coverage arrays are the live
  score director (see Constraint model) and avoid the constraint-stream
  engine's per-move overhead entirely on the hot path

---

## Benchmarking

Two benchmark mechanisms ship with the project.

### 1. Goal-line oracle (JUnit-driven)

`JennyBeatsBenchmarkIT` runs the jenny self-test problem and asserts the
result is `<= 116` active tests with `0` uncovered tuples, reaching feasibility
(`0hard`) typically in ~80s. The run is bounded by the solver's internal 110s
spent-limit; the wall-clock assertion (150s) is a loose, environment-sensitive
sanity ceiling, not the authoritative budget. It builds the solver from the same
`JennySolverFactory.createConfig()` the CLI uses (`createConfig(110)`), so it
exercises exactly the shipped pipeline — see [Solver pipeline](#solver-pipeline).
Named with the `*IT` suffix so failsafe runs it under `mvn verify` only —
`mvn test` and `mvn package` skip it. Run explicitly:

```bash
mvn verify -Dit.test=JennyBeatsBenchmarkIT
```

Sample output (from a real `mvn -o verify` run on 2026-07-08):

```
benchmark: active=99, uncovered=0, withoutViolations=0, elapsed=79804ms, hardScore=0, score=0hard/-99soft
```

(Active count varies run-to-run, even with the same seed — the Tabu/Hill
Climbing phases run for a bounded time budget and pick moves via a seeded
RNG — see Highlights for the `-j<K>` best-of-K flag that bounds this; score
is 0hard/feasible either way.)

### 2. PlannerBenchmark HTML report

`JennyBenchmarkApp` runs three problem sizes (small, medium, self-test) at
30s, 60s, and 120s budgets through Timefold's
[built-in benchmarker][benchmarker], producing an HTML report under
`target/benchmark-results/`:

```bash
mvn exec:java
```

The report includes per-config best-score summaries, score-over-time
charts, and move-evaluation-speed histograms.

---

## Testing

| Command                       | What runs                                    | Approx duration |
|-------------------------------|------------------------------------------------|-----------------|
| `mvn test`                    | unit tests (surefire) — **74 tests**            | ~15 s           |
| `mvn package`                 | unit tests + build the uber jar                 | ~15 s           |
| `mvn verify`                  | unit tests + 6 IT classes, 9 tests (failsafe)   | ~5 min          |
| `mvn verify -DskipITs`        | same as `mvn package`                           | ~15 s           |

Tiering is purely by filename convention — no JUnit tags, no
`excludedGroups` config. Classes named `*Test` run under surefire (`mvn
test`); classes named `*IT` run under failsafe (`mvn verify`). The six IT
classes (confirmed via a full `mvn -o verify` run: BUILD SUCCESS, 74
surefire + 9 failsafe tests, total time 05:03 min) are `GreedyParityIT`
and `GreedyInitializerProfilingIT` (bootstrap greedy-init parity/profiling,
~0.4 s each), `JennyBeatsBenchmarkIT` (goal-line oracle, ~80 s),
`IncrementalScoreParityIT` (3 FULL_ASSERT parity checks against
`JennyConstraintProvider`, ~113 s), `SolverProfilingIT` (score-trajectory +
speed, ~20 s), and `Sp4ShrinkComparisonIT` (shrink-phase A/B comparison,
~73 s).

Run a specific surefire class:

```bash
mvn test -Dtest=SolutionVerificationTest
```

Run a specific failsafe IT:

```bash
mvn verify -Dit.test=JennyBeatsBenchmarkIT
```

---

## Project layout

```
src/main/java/com/burtleburtle/jenny/
  bootstrap/   GreedyInitializer, TupleEnumerator, FeasibilityChecker,
               MinimalCoreCollapser, TupleEnumerationTooLargeException
  cli/         JennyCli (picocli), WithoutParser, OutputFormatter,
               TestFileParser, ManifestVersionProvider
  domain/      JennySolution, TestCase, TestCell, AllowedTuple, Without,
               Dimension, Feature, CoverageUtil
  solver/      JennyConstraintProvider (parity oracle only, see Constraint
               model), JennyIncrementalScoreCalculator (the live score
               director), JennySolverFactory, PhaseSupport,
               ShrinkPhaseCommand, RecoverPhaseCommand (custom phases),
               DeactivateRedundantMoveIteratorFactory,
               EvictRowMoveIteratorFactory, MergeTestsMoveIteratorFactory,
               RandomizeRowMoveIteratorFactory
  bench/       BenchRunner (forks the C jenny binary)

src/main/resources/
  logback.xml           logging config (solver progress -> stderr)

src/test/java/com/burtleburtle/jenny/
  bootstrap/
    GreedyParityIT                (failsafe) greedy-init vs. Go reference parity
    GreedyInitializerProfilingIT  (failsafe) initializer profiling
    TupleEnumeratorTest, FeasibilityCheckerTest, MinimalCoreCollapserTest
  solver/
    JennyBeatsBenchmarkIT         (failsafe) goal-line oracle
    IncrementalScoreParityIT      (failsafe) FULL_ASSERT vs. JennyConstraintProvider
    SolverProfilingIT             (failsafe) score-trajectory + speed
    Sp4ShrinkComparisonIT         (failsafe) shrink-phase A/B comparison
    JennyBenchmarkApp             PlannerBenchmark HTML harness
    JennyBenchmarkAppTest         smoke test for the benchmark harness
    SolutionVerificationTest      coverage + without invariants
    SolutionOracle                (test support) ground-truth coverage/without recompute
    ConstraintProviderTest        per-constraint ConstraintVerifier tests
    DeactivateRedundantMoveIteratorFactoryTest, EvictRowMoveIteratorFactoryTest,
    MergeTestsMoveIteratorFactoryTest, PhaseCommandTest, SolverSmokeTest
  cli/
    CliRegressionTest, TestFileParserTest, WithoutParserTest
  domain/
    DuplicateEntityGuardTest
  bench/
    BenchRunnerTest               head-to-head harness tests

jenny/                       original C jenny source, executable, PDF docs
docs/DESIGN.md               design-of-record
docs/superpowers/            Phase 6 spec & implementation plan
TASKS.md                     resumable work checklist
```

[jenny]: https://burtleburtle.net/bob/math/jenny.html
[timefold]: https://timefold.ai/
[benchmarker]: https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/benchmarking-and-tweaking
