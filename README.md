# jenny-timefold

A high-performance pairwise (and N-wise) test suite generator. Drop-in CLI
compatible with Bob Jenkins' classic [`jenny`][jenny], but built on the
[Timefold Solver][timefold] constraint-optimisation engine. By driving the
search with a **three-phase solver** (Tabu Search consolidation, Hill Climbing
refinement, Tabu Search feasibility repair) over a set of problem-specific
moves, it produces **smaller, feasible test suites** than the original C
version on the same inputs.

```
$ ./jenny -n3 4 4 3 3 3 3 3 3 4 3 3 4 -w1abc2d -w1d2abc -w6ab7bc -w6b8c \
          -w6a8bc -w6a9abc -w6a10ab -w11a12abc -w11bc12d -w4c5ab \
          -w1a3a -w1a9a -w3a9c | wc -l
116

$ jenny -n3 -s0 4 4 3 3 3 3 3 3 4 3 3 4 \
        -w1abc2d -w1d2abc -w6ab7bc -w6b8c -w6a8bc -w6a9abc \
        -w6a10ab -w11a12abc -w11bc12d -w4c5ab -w1a3a -w1a9a -w3a9c | wc -l
106
```

Both tools print one line per generated test (and a `Could not cover tuple`
line for any uncoverable tuple, of which there are none here); piping
through `wc -l` gives the test count. Use `--bench` for an automatic
side-by-side count + wall-time comparison.

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

## What is pairwise testing?

Combinatorial coverage testing reduces test suite size dramatically while
keeping high defect-detection rates. Most software defects are triggered by
the interaction of just two parameters — exhaustive testing is wasteful for
most scenarios.

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
./jenny -n2 4 2 5 2 5 2 | wc -l
# 28

# jenny-timefold (multi-phase constraint optimisation)
jenny -n2 4 2 5 2 5 2 | wc -l
# 25  (~11% fewer)
```

### Background reading

- Pairwise testing concepts: [pairwise.org](https://www.pairwise.org/)
- Original tool: [jenny.c by Bob Jenkins](https://burtleburtle.net/bob/math/jenny.html)
- Solver: [timefold.ai](https://timefold.ai/)

---

## Highlights

- **Beats jenny.c on the self-test benchmark:** ~106–108 active tests vs 116
  on `-n3 4 4 3 3 3 3 3 3 4 3 3 4` with 13 `-w` constraints, **0hard
  feasible**, ~80s (see `JennyBeatsBenchmarkIT`). Active count varies ±1
  run-to-run because phase termination is wall-clock-based.
- **CLI-compatible with jenny:** `-n`, `-s`, `-w`, `-o`, positional dim
  sizes — same attached-value form (`-n2`, `-w1a2b`) the C tool uses.
- **Three-phase solver:** Phase 1 Tabu Search consolidation with custom
  `DeactivateRedundant` and `MergeTests` moves; Phase 2 Hill Climbing
  refinement that strictly preserves coverage; Phase 3 Tabu Search
  feasibility repair that drives the solution to **0hard** (no Without
  violations).
- **Head-to-head bench mode:** `--bench` forks the C `jenny` binary on the
  same input and prints a comparison table.
- **Java 26 + Timefold 2.2.0** (Preview Moves API: `Moves.compose` + 3
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

### Three-stage pipeline

1. **Greedy initialisation** (`bootstrap.GreedyInitializer`) — builds a
   feasible-or-near-feasible starting suite using a randomised greedy set
   cover. Uncovered tuples are ordered by **rarity score** (sum of feature
   frequencies) so the hardest-to-cover combinations get satisfied first.
2. **Phase 1: Tabu consolidation** — Tabu Search over a weighted union of
   five move types:
   - `ChangeMoveSelector` over `TestCell.feature` (single-cell value flip)
   - `ChangeMoveSelector` over `TestCase.active` (whole-row toggle)
   - `RandomizeRowMoveIteratorFactory` (re-roll all cells of one row)
   - `DeactivateRedundantMoveIteratorFactory` (flip one row's `active=false`)
   - `MergeTestsMoveIteratorFactory` (composite move: overwrite A's cells
     with B's where they differ, then deactivate B)

   Tuned for the self-test benchmark: `entityTabuSize=7`,
   `acceptedCountLimit=10` (constants validated by `JennyBeatsBenchmarkIT`).
3. **Phase 2: Hill Climbing refinement** — strict-improvement acceptor
   over single-variable moves. Phase 2 cannot worsen the score, so any
   coverage Phase 1 broke gets repaired without back-sliding.
4. **Phase 3: Feasibility repair** — short Tabu Search over single-variable
   change moves. Terminates the moment the best solution is feasible
   (`bestScoreFeasible=true`) or after a short unimproved budget. Paired
   with the 2-hard `respectWithouts` weight (see Constraint model), breaking
   a Without violation is a strict hard-score improvement, so the repair
   holds and the solver re-covers from there. Drives solutions to **0hard**
   reproducibly.

### Constraint model

Three constraints in `solver.JennyConstraintProvider`:

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
`assignmentMap` pattern from sibling implementations.

### Performance optimisations

- Hashcode caching on `AllowedTuple` (called millions of times as a
  constraint-stream `HashMap` key)
- `CoverageUtil` consolidates the `coversTuple` check used by the
  constraint provider, the greedy initialiser, and any in-test recount
- Rarity-sorted `LinkedHashSet` of uncovered tuples → improved cache
  locality during greedy construction (~5× faster greedy on the self-test)
- `TestCase.featuresByDim` shadow variable → O(1) constraint-time lookups
  with no manual setter upkeep

---

## Benchmarking

Two benchmark mechanisms ship with the project.

### 1. Goal-line oracle (JUnit-driven)

`JennyBeatsBenchmarkIT` runs the jenny self-test problem and asserts the
result is `<= 116` active tests with `0` uncovered tuples, reaching feasibility
(`0hard`) typically in ~80s. The run is bounded by the solver's internal 110s
spent-limit; the wall-clock assertion (150s) is a loose, environment-sensitive
sanity ceiling, not the authoritative budget.
Named with the `*IT` suffix so failsafe runs it under `mvn verify` only —
`mvn test` and `mvn package` skip it. Run explicitly:

```bash
mvn verify -Dit.test=JennyBeatsBenchmarkIT
```

Sample output:

```
benchmark: active=107, uncovered=0, elapsed=79843ms, score=0hard/-107soft
```

(Active count varies ±1 run-to-run; score is 0hard/feasible.)

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

| Command                       | What runs                                  | Approx duration |
|-------------------------------|--------------------------------------------|-----------------|
| `mvn test`                    | unit tests (surefire) — **56 tests**       | ~15 s           |
| `mvn package`                 | unit tests + build the uber jar            | ~15 s           |
| `mvn verify`                  | unit tests + 3 long-running ITs (failsafe) | ~2 min          |
| `mvn verify -DskipITs`        | same as `mvn package`                      | ~15 s           |

Tiering is purely by filename convention — no JUnit tags, no
`excludedGroups` config. Classes named `*Test` run under surefire (`mvn
test`); classes named `*IT` run under failsafe (`mvn verify`). The three
long-running ITs are `JennyBeatsBenchmarkIT`, `SolverProfilingIT`, and
`GreedyInitializerProfilingIT`.

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
  bootstrap/   GreedyInitializer, TupleEnumerator
  cli/         JennyCli (picocli), WithoutParser, OutputFormatter, TestFileParser
  domain/      JennySolution, TestCase, TestCell, AllowedTuple, Without,
               Dimension, Feature, CoverageUtil
  solver/      JennyConstraintProvider, JennySolverFactory,
               DeactivateRedundantMoveIteratorFactory,
               MergeTestsMoveIteratorFactory, RandomizeRowMoveIteratorFactory
  bench/       BenchRunner (forks the C jenny binary)

src/main/resources/
  solverConfig.xml      three-phase solver config (Tabu + Hill Climbing + Tabu repair)
  logback.xml           logging config

src/test/java/com/burtleburtle/jenny/
  bootstrap/
    GreedyInitializerProfilingIT  (failsafe) initializer profiling
    TupleEnumeratorTest           tuple enumeration unit tests
  solver/
    JennyBeatsBenchmarkIT         (failsafe) goal-line oracle
    SolverProfilingIT             (failsafe) score-trajectory + speed
    JennyBenchmarkApp             PlannerBenchmark HTML harness
    SolutionVerificationTest      coverage + without invariants
    ConstraintProviderTest        per-constraint ConstraintVerifier tests
    ...
  cli/
    CliRegressionTest, TestFileParserTest, WithoutParserTest
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
