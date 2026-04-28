# jenny-timefold

A high-performance pairwise (and N-wise) test suite generator. Drop-in CLI
compatible with Bob Jenkins' classic [`jenny`][jenny], but built on the
[Timefold Solver][timefold] constraint-optimisation engine. By driving the
search with **Tabu Search** (consolidation phase) and **Hill Climbing**
(refinement phase) over a set of problem-specific moves, it produces
**smaller test suites** than the original C version on the same inputs.

```
$ ./jenny -n3 4 4 3 3 3 3 3 3 4 3 3 4 -w1abc2d -w1d2abc -w6ab7bc -w6b8c \
          -w6a8bc -w6a9abc -w6a10ab -w11a12abc -w11bc12d -w4c5ab \
          -w1a3a -w1a9a -w3a9c
116 active tests

$ java -jar target/jenny.jar -n3 ... (same args)
105 active tests   # 11 fewer; 0 uncovered tuples; 0 'without' violations
```

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
./jenny -n2 4 2 5 2 5 2
# 28 tests

# jenny-timefold (multi-phase constraint optimisation)
java -jar target/jenny.jar -n2 4 2 5 2 5 2
# 25 tests (~11% fewer)
```

### Background reading

- Pairwise testing concepts: [pairwise.org](https://www.pairwise.org/)
- Original tool: [jenny.c by Bob Jenkins](https://burtleburtle.net/bob/math/jenny.html)
- Solver: [timefold.ai](https://timefold.ai/)

---

## Highlights

- **Beats jenny.c on the self-test benchmark:** 105 vs 116 active tests on
  `-n3 4 4 3 3 3 3 3 3 4 3 3 4` with 13 `-w` constraints (target ≤116, see
  `JennyBeatsBenchmarkTest`).
- **CLI-compatible with jenny:** `-n`, `-s`, `-w`, `-o`, positional dim
  sizes — same attached-value form (`-n2`, `-w1a2b`) the C tool uses.
- **Multi-phase solver:** Tabu Search consolidation with custom
  `DeactivateRedundant` and `MergeTests` moves, followed by Hill Climbing
  refinement that strictly preserves coverage.
- **Head-to-head bench mode:** `--bench` forks the C `jenny` binary on the
  same input and prints a comparison table.
- **Java 25 + Timefold 2.0 preview Moves API.**

---

## Prerequisites

- **Java 25** (tested with Amazon Corretto 25.0.2)
- **Maven 3.9+** (or `mvnd`)
- *(Optional)* the C `jenny` binary for `--bench`. A pre-built arm64 macOS
  binary plus source ships in `jenny/`; build other architectures with:
  ```bash
  cc -O2 -o jenny/jenny jenny/jenny.c
  ```

## Build

```bash
mvn package
```

Produces `target/jenny.jar` — a shaded uber-JAR with all dependencies.

## Run

```bash
java -jar target/jenny.jar -n3 -s0 4 4 3 3 3 3 3 3 4 3 3 4 \
     -w1abc2d -w1d2abc -w6ab7bc -w6b8c -w6a8bc -w6a9abc \
     -w6a10ab -w11a12abc -w11bc12d -w4c5ab -w1a3a -w1a9a -w3a9c
```

### Flags

| flag                       | meaning                                                       |
|----------------------------|---------------------------------------------------------------|
| `-n<k>`                    | tuple size, 1..32 (default 2)                                 |
| `-s<seed>`                 | random seed (default 0)                                       |
| `-w<spec>`                 | forbidden combination, e.g. `-w1a2cd4ac` (repeatable)         |
| `-o<file>`                 | seed with existing tests from FILE (or `-` for stdin)         |
| `-h`                       | help                                                          |
| *positional*               | feature counts per dimension, in order (2..52 each)           |
| `--time-limit-seconds=<s>` | solver wall-clock budget (default 5)                          |
| `--bench`                  | head-to-head against the C jenny binary                       |
| `--jenny-path=<path>`      | location of the C binary (defaults to `$JENNY_BIN` then `~/src/jenny/jenny`) |

The CLI uses jenny's attached-value style: `-n3` not `-n=3`, `-w1a2b` not
`-w 1a2b`, `-ofile.txt` not `-o file.txt`.

### Head-to-head bench

```bash
java -jar target/jenny.jar --bench --jenny-path=jenny/jenny \
     -n2 2 3 8 3 2 2 5 3 2 2 --time-limit-seconds=10
```

Forks both solvers on the same input and prints a two-row comparison of
test count and wall time.

---

## Architecture

### Three-stage pipeline

1. **Greedy initialisation** (`bootstrap.GreedyInitializer`) — builds a
   feasible-or-near-feasible starting suite using a randomised greedy set
   cover. Uncovered tuples are ordered by **rarity score** (sum of feature
   frequencies) so the hardest-to-cover combinations get satisfied first.
2. **Phase 1: Tabu consolidation** (`solver.JennySolverFactory` Phase 1) —
   Tabu Search over a weighted union of five move types:
   - `ChangeMoveSelector` over `TestCell.feature` (single-cell value flip)
   - `ChangeMoveSelector` over `TestCase.active` (whole-row toggle)
   - `RandomizeRowMoveIteratorFactory` (re-roll all cells of one row)
   - `DeactivateRedundantMoveIteratorFactory` (flip one row's `active=false`)
   - `MergeTestsMoveIteratorFactory` (composite move: overwrite A's cells
     with B's where they differ, then deactivate B)

   `entityTabuSize` and forager breadth scale with the tuple count.
3. **Phase 2: Hill Climbing refinement** — strict-improvement acceptor
   over single-variable moves. Phase 2 cannot worsen the score, so any
   coverage Phase 1 broke gets repaired without back-sliding.

### Constraint model

Three constraints in `solver.JennyConstraintProvider`:

| Constraint            | Score level       | Meaning                                            |
|-----------------------|-------------------|----------------------------------------------------|
| `coverAllTuples`      | `-1` HARD per     | Every allowed tuple must be covered by some active row |
| `respectWithouts`     | `-1` HARD per     | No active row may match any forbidden combination    |
| `minimizeActiveTests` | `-1` SOFT per     | Minimise the number of active rows                  |

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

`JennyBeatsBenchmarkTest` runs the jenny self-test problem and asserts the
result is `<= 116` active tests with `0` uncovered tuples in `<= 130s`.
Tagged `benchmark` so it is excluded from default `mvn test`. Run
explicitly:

```bash
mvn test -Dtest=JennyBeatsBenchmarkTest -Dsurefire.excludedGroups=
```

Sample output:

```
benchmark: active=105, uncovered=0, elapsed=90752ms, score=-1hard/-105soft
```

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

```bash
mvn test
```

37 tests across constraint, move-factory, CLI, regression, and profiling
suites. The benchmark test (`JennyBeatsBenchmarkTest`) is `@Tag("benchmark")`
and excluded by default; pass `-Dsurefire.excludedGroups=` to include it.

Run a specific class:

```bash
mvn test -Dtest=SolutionVerificationTest
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
  solverConfig.xml      multi-phase Tabu + Hill Climbing config
  logback.xml           logging config

src/test/java/com/burtleburtle/jenny/solver/
  JennyBeatsBenchmarkTest    goal-line oracle (tagged benchmark)
  JennyBenchmarkApp          PlannerBenchmark HTML harness
  SolutionVerificationTest   coverage + without invariants
  SolverProfilingTest        score-trajectory + speed profiling
  ...

jenny/                       original C jenny source, executable, PDF docs
docs/DESIGN.md               design-of-record
docs/superpowers/            Phase 6 spec & implementation plan
TASKS.md                     resumable work checklist
```

[jenny]: https://burtleburtle.net/bob/math/jenny.html
[timefold]: https://timefold.ai/
[benchmarker]: https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/benchmarking-and-tweaking
