# jenny-timefold — Design

## Context

`jenny.c` (Bob Jenkins, 2003; `~/src/jenny/jenny.c`) is a single-file C
combinatorial test-case generator. Given *m* feature dimensions and optional
forbidden-combination rules ("withouts"), it greedily builds the smallest set of
test cases that covers every allowed *n*-tuple of features. Its algorithm is
pure hand-rolled greedy + hillclimbing with `-s`-seeded pseudorandom
tiebreaking.

This project re-implements jenny in Java 25 + Maven using **Timefold Solver
2.0**, keeping the CLI surface (flags `-n`, `-s`, `-w`, `-o`, `-h`; dimension
sizes as positional args) and the output format identical, but replacing the
greedy core with Timefold's constraint-streams + metaheuristics pipeline.
Target: better solution quality (fewer test cases) at equal or better runtime
on non-trivial inputs, without sacrificing compatibility on trivial ones.

Ongoing tasks: `../TASKS.md`.

## Approach

**Modeling decision**: fixed overcapacity with a boolean `active` flag per test
case. Two alternatives were evaluated and rejected:

- *Outer iterative resolve* (re-solve with growing entity count): pays
  construction-heuristic cost each iteration without beating the single-solve
  approach for the typical 5–15 dim, n ∈ {2,3} workload.
- *`@PlanningListVariable` on a TestSuite*: the list-variable uniqueness rule
  forbids the solver from picking feature values freely — it can only pick from
  a pre-enumerated value pool, which defeats the point.

### Domain (under `com.burtleburtle.jenny.domain`)

- `Dimension(int index, int size)` — problem fact.
- `Feature(Dimension dim, int featureIndex)` — problem fact; equality by
  `(dim.index, featureIndex)`. Feature name is derived:
  `"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(featureIndex)`.
- `AllowedTuple(List<Feature> features)` — problem fact. Pre-enumerated in a
  builder pass. Only tuples not matched by any `Without` are emitted.
- `Without(List<List<Feature>> perDimAllowedFeatures)` — problem fact; matches
  a test iff every dimension the without names has the test's feature in the
  without's list for that dimension.
- `@PlanningEntity TestCase` — one per slot. Fields:
  - `@PlanningId long id`
  - `@PlanningVariable Boolean active` — value range `[true, false]`.
  - `@PlanningVariable` feature fields per dimension (`f0`, `f1`, …), each with
    its own per-dimension value range. See "Open question" below.
  - `@PlanningPin boolean pinned` — used for `-o` imported tests.
- `@PlanningSolution JennySolution` — holds `List<Dimension>`, `List<Feature>`
  per dim (value-range providers), `List<AllowedTuple>`, `List<Without>`,
  `List<TestCase>`, and `@PlanningScore HardSoftLongScore`.

### Constraints (`com.burtleburtle.jenny.solver.JennyConstraintProvider`)

Hard (must reach zero):
1. **Cover every allowed tuple.** For each `AllowedTuple`, `ifNotExists` any
   active `TestCase` whose features match the tuple on all its dimensions.
   Penalty 1 per uncovered tuple.
2. **Respect every without on every active test.** `forEach(Without).join(
   active TestCase, withoutMatches(...))` penalize 1 each.

Soft:
3. **Minimize active test count.** `forEach(TestCase).filter(isActive).penalize(1)`.

### Solver config (`src/main/resources/solverConfig.xml`)

```xml
<solver>
  <solutionClass>…JennySolution</solutionClass>
  <entityClass>…TestCase</entityClass>
  <scoreDirectorFactory>
    <constraintProviderClass>…JennyConstraintProvider</constraintProviderClass>
  </scoreDirectorFactory>
  <constructionHeuristic>
    <constructionHeuristicType>FIRST_FIT_DECREASING</constructionHeuristicType>
  </constructionHeuristic>
  <localSearch>
    <unionMoveSelector>
      <changeMoveSelector/>
      <swapMoveSelector/>
      <moveIteratorFactory>
        <moveIteratorFactoryClass>…RandomizeRowMoveIteratorFactory</moveIteratorFactoryClass>
      </moveIteratorFactory>
    </unionMoveSelector>
    <acceptor><lateAcceptanceSize>400</lateAcceptanceSize></acceptor>
    <forager><acceptedCountLimit>4</acceptedCountLimit></forager>
  </localSearch>
  <termination>
    <bestScoreFeasible>true</bestScoreFeasible>
    <unimprovedSecondsSpentLimit>5</unimprovedSecondsSpentLimit>
  </termination>
</solver>
```

The custom `RandomizeRowMoveIteratorFactory` re-rolls all dimensions of one
`TestCase` at once. Single-variable `ChangeMove`s struggle to escape plateaus
once most tuples are covered — this was Jenny's own motivation for multi-feature
repair in `obey_withouts` / `maximize_coverage` (jenny.c:1246–1322).

### CLI layer (`com.burtleburtle.jenny.cli.JennyCli`)

Built on **picocli** 4.7.x. Exact flag semantics mirror `jenny.c`
(jenny.c:12–51 is the spec). Jenny's flags are glued-value style (`-n3`,
`-w1a2bc3b`, `-ofoo.txt`) rather than POSIX `--n=3`, so picocli `arity="1"`
with a custom `IParameterConsumer` handles `-w`'s repeatable, non-trivial
internal grammar.

- positional integer 2..52 → append a `Dimension` of that size.
- `-n<int>`: tuple size, 1..32, default 2.
- `-s<int>`: seed; passed to Timefold via `<randomSeed>` in solverConfig.
- `-w<number><features><number><features>…`: repeatable. `WithoutParser`
  handles multi-feature blocks per dim (`-w1a2cd4ac` → 4 restrictions).
- `-o<file>` (or `-o-` for stdin): read existing tests, terminate on `.`.
  Parsed tests become `pinned=true, active=true` TestCase entities.
- `-h`: help text formatted to match jenny's layout.
- `--bench` (new, not in jenny): head-to-head mode. Runs the C jenny binary
  (path via `--jenny-path` or `$JENNY_BIN`, default `~/src/jenny/jenny`) and
  this solver on the same inputs, reporting wall-clock time and test count for
  each.
- Output per test: one leading space, `<dimIdx1-based><feature-letter>`
  space-separated, trailing space, newline. Print via `OutputFormatter` to
  keep the byte-for-byte promise.

### Test-count sizing

Initial `MAX_TESTS` = `2 × product-of-n-largest-dimension-sizes`, capped at
jenny's 65534. Enough overcapacity that the solver has room while keeping the
search space tractable. If the solver terminates infeasible, grow by 25% and
restart warm (re-use previous assignments as initial solution).

### Bench mode (head-to-head vs jenny.c)

`jenny --bench -n3 2 3 8 3 2 2 5 3 2 2 -s3` runs both solvers on the same
input and prints:

```
         tests    wall_ms
jenny-c     45      12
timefold    43    5007
```

`BenchRunner`:
1. Forks the C jenny binary via `ProcessBuilder`, times it with
   `System.nanoTime()`, counts non-empty output lines starting with a space.
2. Runs the Timefold solver in-process with the same args.
3. Prints the two-row table; exit 0 iff both succeeded.

The C binary path defaults to `~/src/jenny/jenny`; missing → clear error
message pointing the user to build it.

### Tuple enumeration

`TupleEnumerator` uses Guava `Sets.combinations(Set<Dimension>, n)` to pick
which *n* dimensions participate, then Cartesian-expands each combination via
`Lists.cartesianProduct`. Each product element is filtered against the
`Without` list before being emitted as an `AllowedTuple`. Replaces jenny.c's
`next_builder` loop (jenny.c:1122–1148).

### `-o` compatibility and "uncoverable tuple" handling

Jenny promotes a tuple to an auto-without when it proves uncoverable mid-run.
We replicate this: after each solve, any still-uncovered `AllowedTuple` is
reported with `Could not cover tuple …` on stdout (matches jenny.c:1553–1554)
before the final test list.

## Project layout

```
pom.xml                                      Java 25, Timefold 2.0 BOM,
                                             picocli 4.7.x, Guava 33.6.0-jre,
                                             JUnit 6.0.2, Mockito 3.x
src/main/java/com/burtleburtle/jenny/
  cli/JennyCli.java                          picocli @Command, main
  cli/WithoutParser.java                     -w grammar
  cli/OutputFormatter.java                   exact output reproduction
  bench/BenchRunner.java                     --bench head-to-head
  domain/Dimension.java
  domain/Feature.java
  domain/Without.java
  domain/AllowedTuple.java
  domain/TestCase.java                       @PlanningEntity
  domain/JennySolution.java                  @PlanningSolution
  bootstrap/TupleEnumerator.java             Guava Sets.combinations + cartesianProduct
  solver/JennyConstraintProvider.java
  solver/RandomizeRowMoveIteratorFactory.java
src/main/resources/solverConfig.xml
src/test/java/com/burtleburtle/jenny/
  CliRegressionTest.java
  ConstraintProviderTest.java
  bench/BenchRunnerTest.java
  cli/WithoutParserTest.java
```

## Key dependencies

| Dep                 | GAV                                                   |
| ------------------- | ----------------------------------------------------- |
| Timefold Solver BOM | `ai.timefold.solver:timefold-solver-bom:2.0.0`        |
| picocli             | `info.picocli:picocli:4.7.7`                          |
| Guava               | `com.google.guava:guava:33.6.0-jre`                   |
| JUnit Jupiter       | `org.junit.jupiter:junit-jupiter:6.0.2`               |
| Mockito             | `org.mockito:mockito-core:3.12.4`                     |

## Open question deferred to implementation

Timefold 2.0 wants each `@PlanningVariable` on a concretely named field.
Variable dimension counts per problem mean we either (a) generate `TestCase`
subclasses at solution-build time via bytecode (overkill), or (b) keep a fixed
upper bound on dimensions and have `f0..fMAX` fields, nullable, with extras
forced null via a `[null]` `ValueRange` per unused slot. We pick (b) with
`MAX = 64` initially (jenny's practical limit is ≤ 52 features but ≤ 32
dimensions in any realistic input, given `-n > 4` is "highly discouraged").
If profiling shows null-slot overhead matters, revisit.

## Verification

End-to-end parity tests drive the regression suite:

1. **Golden-output parity** (where tractable). For small seeded invocations,
   assert the Java port produces a valid covering set — every allowed tuple
   present in at least one output test, no output test violating any `-w`,
   test count ≤ 1.1× jenny.c's count.
2. **ConstraintVerifier** per-constraint unit tests.
3. **Command-line regression.**
   `./mvnw -q exec:java -Dexec.args="-n3 2 3 8 3 2 2 5 3 2 2 -w1a2bc3b -w1b3a -s3"`
   (jenny.c:50's worked example).
4. **`-o` round-trip.** Output → `-o -` re-ingest → zero additional tests.
5. **Uncoverable-tuple report.** Over-restricted input → "Could not cover
   tuple …" line on stdout matching jenny's wording.
6. **Bench-mode head-to-head.** `jenny --bench -n2 3 3 3 3 3 -s1` prints a
   two-row table. `BenchRunnerTest` mocks the forked process so unit tests
   don't require the C binary.

Manual sanity: `./mvnw package && java -jar target/jenny.jar -h`.
