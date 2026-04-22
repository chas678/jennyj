# Timefold port of Jenny — design plan

## Context

`jenny.c` (Bob Jenkins, 2003; ~/src/jenny/jenny.c) is a single-file C combinatorial
test-case generator. Given *m* feature dimensions and optional forbidden-combination
rules ("withouts"), it greedily builds the smallest set of test cases that covers
every allowed *n*-tuple of features. Its algorithm is pure hand-rolled greedy +
hillclimbing with `-s`-seeded pseudorandom tiebreaking.

The goal of this project is to re-implement jenny in Java 25 + Maven using
**Timefold Solver 2.0**, keeping the CLI surface (flags `-n`, `-s`, `-w`, `-o`, `-h`;
dimension sizes as positional args) and the output format bit-for-bit identical,
but replacing the greedy core with Timefold's constraint-streams + metaheuristics
pipeline. Target: better solution quality (fewer test cases) at equal or better
runtime on non-trivial inputs, without sacrificing compatibility on trivial ones.

Working directory `/Users/chas/src/timefoldJenny/` is currently empty — greenfield.

## Approach

**Modeling decision**: fixed overcapacity with a boolean `active` flag per test
case (option A of the three candidates evaluated). The other two options — outer
iterative resolve, and `@PlanningListVariable` on a `TestSuite` — were rejected
because the list-variable uniqueness rule forbids letting the solver pick feature
values freely (it can only pick from a pre-enumerated pool), and the outer-loop
approach pays construction-heuristic cost each iteration without beating A's
single solve for the typical 5–15 dim, n∈{2,3} workload. Rationale detail lives
in the conversation that produced this plan.

### Domain (all under `com.burtleburtle.jenny.domain`)

- `Dimension(int index, int size)` — problem fact.
- `Feature(Dimension dim, int featureIndex)` — problem fact; equality by `(dim.index, featureIndex)`.
  Feature name is derived: `"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(featureIndex)`.
- `AllowedTuple(List<Feature> features)` — problem fact. Pre-enumerated in a
  builder pass (see Bootstrapping below). Only tuples not matched by any
  `Without` are emitted.
- `Without(List<List<Feature>> perDimAllowedFeatures)` — problem fact; matches a
  test iff every dimension the without names has the test's feature in the
  without's list for that dimension.
- `@PlanningEntity TestCase` — one per slot. Fields:
  - `@PlanningId long id`
  - `@PlanningVariable Boolean active` — value range `[true, false]`.
  - `@PlanningVariable Feature[] features` — one genuine variable *per dimension*,
    each with its own value-range provider. (In code: `f0`, `f1`, … generated or
    held as a map; see "open question" below.)
  - `@PlanningPin boolean pinned` — used for `-o` imported tests.

- `@PlanningSolution JennySolution` — holds `List<Dimension>`, `List<Feature>` per
  dim (value-range providers), `List<AllowedTuple>`, `List<Without>`,
  `List<TestCase>`, and `@PlanningScore HardSoftLongScore`.

### Constraints (under `com.burtleburtle.jenny.solver.JennyConstraintProvider`)

Hard (must reach zero):
1. **Cover every allowed tuple.** For each `AllowedTuple`, `ifNotExists` any
   active `TestCase` whose features match the tuple on all its dimensions.
   Penalty 1 per uncovered tuple.
2. **Respect every without on every active test.** `forEach(Without).join(active
   TestCase, withoutMatches(...))` penalize 1 each.

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
`TestCase` at once — single-variable `ChangeMove`s struggle to escape plateaus
once most tuples are covered, which was Jenny's own motivation for multi-feature
repair in `obey_withouts` / `maximize_coverage`.

### CLI layer (`com.burtleburtle.jenny.cli.JennyCli` — `main`)

Built on **picocli** (`info.picocli:picocli:4.7.x`). Exact flag semantics mirror
`jenny.c` (from ~/src/jenny/jenny.c:12–51 — the C is the spec). Because jenny's
flags are glued-value style (`-n3`, `-w1a2bc3b`, `-ofoo.txt`) rather than POSIX
`--n=3`, we use picocli's `arity="1"` with no-space parsing by declaring each
flag with its prefix and using a custom `IParameterConsumer` for `-w` (which is
repeatable and has a non-trivial internal grammar).

- positional integer 2..52 → append a `Dimension` of that size (picocli
  `@Parameters(arity = "1..*")` with a converter).
- `-n<int>`: tuple size, 1..32, default 2.
- `-s<int>`: seed; passed to Timefold via `<randomSeed>` in solverConfig.
- `-w<number><features><number><features>…`: repeatable. Custom parser in
  `WithoutParser` handles multi-feature blocks per dim (`-w1a2cd4ac` → 4
  restrictions).
- `-o<file>` (or `-o-` for stdin): read existing test lines, terminate on `.`.
  Parsed tests become `pinned=true, active=true` TestCase entities.
- `-h`: picocli-generated help reformatted to match jenny's layout.
- `--bench` (new, not in jenny): head-to-head mode. Runs the C jenny binary
  (path discovered via `--jenny-path` or `$JENNY_BIN`, default
  `~/src/jenny/jenny`) and this solver on the same inputs, reporting wall-clock
  time and test count for each. See Bench mode below.
- Output per test: one leading space, `<dimIdx1-based><feature-letter>`
  space-separated, trailing space, newline. Print via `OutputFormatter` to keep
  the byte-for-byte promise.

### Test-count sizing

Initial `MAX_TESTS` = `2 × product-of-n-largest-dimension-sizes`, capped at
jenny's 65534. This is enough overcapacity that the solver has room while
keeping the search space tractable. If the solver terminates infeasible, grow
by 25% and restart warm (re-use previous assignments as initial solution).

### Bench mode (head-to-head vs jenny.c)

`jenny --bench -n3 2 3 8 3 2 2 5 3 2 2 -s3` runs both solvers on the same input
and prints a comparison table:

```
         tests    wall_ms
jenny-c     45      12
timefold    43    5007
```

Implementation lives in `com.burtleburtle.jenny.bench.BenchRunner`. It:
1. Forks the C jenny binary via `ProcessBuilder`, times it with
   `System.nanoTime()`, counts non-empty output lines that start with a space.
2. Runs the Timefold solver in-process with the same args.
3. Prints the table to stdout; returns exit code 0 iff both succeeded.

The C binary path defaults to `~/src/jenny/jenny`; if missing, `--bench` fails
with a clear message pointing the user to build it.

### Tuple enumeration

`TupleEnumerator` uses **Guava** (`com.google.guava:guava:33.6.0-jre`)
`Sets.combinations(Set<Dimension>, n)` to pick which *n* dimensions participate,
then Cartesian-expands each combination across each dimension's features (via
`Lists.cartesianProduct`). Each product element is filtered against the
`Without` list before being emitted as an `AllowedTuple`. This replaces the
manual `next_builder` loop at jenny.c:1122–1148.

### `-o` compatibility and "uncoverable tuple" handling

Jenny promotes a tuple to an auto-without when it proves uncoverable mid-run.
We replicate this: after each solve, any still-uncovered `AllowedTuple` gets
reported with "Could not cover tuple …" (matching jenny's stdout) before the
final test list. That matches jenny's driver flow at jenny.c:1784–1799.

### Project layout

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
  CliRegressionTest.java                     JUnit 6.0.2
  ConstraintProviderTest.java                Timefold ConstraintVerifier + JUnit 6
  bench/BenchRunnerTest.java                 Mockito 3.x stubs the jenny-c process
  cli/WithoutParserTest.java                 parser grammar tests
```

### Key dependencies (`pom.xml`)

| Dep | GAV |
| --- | --- |
| Timefold Solver BOM | `ai.timefold.solver:timefold-solver-bom:2.0.x` |
| picocli | `info.picocli:picocli:4.7.x` |
| Guava | `com.google.guava:guava:33.6.0-jre` |
| JUnit Jupiter | `org.junit.jupiter:junit-jupiter:6.0.2` |
| Mockito | `org.mockito:mockito-core:3.x` (latest 3.line) |

`<maven.compiler.release>25</maven.compiler.release>`.

### Open question deferred to implementation

Timefold 2.0 wants each `@PlanningVariable` on a concretely named field.
Variable dimension counts per problem mean we either (a) generate TestCase
subclasses at solution-build time via bytecode (overkill), or (b) keep a fixed
upper bound on dimensions (say 128 — jenny's limit is 65535 but realistic inputs
are tiny) and have `f0..f127` fields, nullable, with the extras forced null via
a ValueRange of `[null]` per unused slot. We pick (b). If profiling shows the
null-slot overhead matters, revisit.

## Critical files to create

(All files are new — greenfield project.)

- `/Users/chas/src/timefoldJenny/pom.xml`
- `/Users/chas/src/timefoldJenny/src/main/java/com/burtleburtle/jenny/cli/{JennyCli,WithoutParser,OutputFormatter}.java`
- `/Users/chas/src/timefoldJenny/src/main/java/com/burtleburtle/jenny/bench/BenchRunner.java`
- `/Users/chas/src/timefoldJenny/src/main/java/com/burtleburtle/jenny/domain/{Dimension,Feature,Without,AllowedTuple,TestCase,JennySolution}.java`
- `/Users/chas/src/timefoldJenny/src/main/java/com/burtleburtle/jenny/bootstrap/TupleEnumerator.java`
- `/Users/chas/src/timefoldJenny/src/main/java/com/burtleburtle/jenny/solver/JennyConstraintProvider.java`
- `/Users/chas/src/timefoldJenny/src/main/java/com/burtleburtle/jenny/solver/RandomizeRowMoveIteratorFactory.java`
- `/Users/chas/src/timefoldJenny/src/main/resources/solverConfig.xml`

Reused libraries: picocli (CLI), Guava `Sets.combinations` +
`Lists.cartesianProduct` (tuple enumeration), Timefold `ConstraintVerifier`
(per-constraint tests), Mockito (stub the C binary in bench tests). `jenny.c`
at `~/src/jenny/jenny.c` is the behavioral reference and the bench-mode
opponent; do not link it.

## Verification

End-to-end parity tests drive the regression suite:

1. **Golden-output parity (where tractable).** For a small fixed set of seeded
   jenny invocations (e.g. `jenny -n2 2 2 2 -s1`), capture jenny.c's output once
   and assert the Java port produces a valid covering set — NOT byte-equal,
   since the algorithms differ, but with:
   - every allowed tuple present in at least one output test,
   - no output test violating any `-w`,
   - test count ≤ jenny.c's count on that seed (stretch goal; assert ≤ 1.1× to
     start).
2. **ConstraintVerifier per-constraint unit tests** using Timefold's
   `ConstraintVerifier` API for each of the three constraints.
3. **Command-line regression.** Run `./mvnw -q exec:java -Dexec.args="-n3 2 3 8 3 2 2 5 3 2 2 -w1a2bc3b -w1b3a -s3"` (the example from jenny.c:50) and
   diff the parsed output set against the C reference's parsed output set on
   coverage — asserting both produce valid covering arrays.
4. **`-o` round-trip.** Generate output, pipe back via `-o -`, assert the
   second run emits zero additional tests.
5. **Uncoverable-tuple report.** Run with an over-restricted input (restrictions
   that forbid at least one tuple entirely) and assert the "Could not cover
   tuple …" line appears on stdout, matching jenny's wording.
6. **Bench-mode head-to-head.** `jenny --bench -n2 3 3 3 3 3 -s1` prints a
   two-row table with `tests` and `wall_ms` columns for `jenny-c` and
   `timefold`. `BenchRunnerTest` mocks the forked process with Mockito so unit
   tests don't require the C binary to be present.

Manual sanity: `./mvnw package && java -jar target/jenny.jar -h` prints help
identical to jenny's `-h` output.

## Durable project artifacts

Beyond the source tree, implementation will also write two persistent
companion documents at the project root so future sessions can pick up
mid-stream:

- `/Users/chas/src/timefoldJenny/docs/DESIGN.md` — the entire **Approach**
  section of this plan, copied verbatim and then free to evolve with the code.
  This is the design-of-record the user asked for; the plan file in
  `~/.claude/plans/` is ephemeral relative to the project.
- `/Users/chas/src/timefoldJenny/TASKS.md` — the resumable task sheet below,
  in GitHub-flavored markdown checkbox syntax so any editor or `gh` can
  render progress. Each task has a short ID (T01…) referenced in commit
  messages so a partial session is easy to resume.

## Task sheet (copy to `TASKS.md` on implementation)

Groups are independent enough to parallelize after T03. Mark each box when
done; keep the file in git so resuming = `git pull && grep '\- \[ \]' TASKS.md`.

### Phase 0 — project scaffolding
- [ ] **T01** Create `pom.xml` with Java 25, Timefold 2.0 BOM, picocli 4.7.x,
      Guava 33.6.0-jre, JUnit 6.0.2, Mockito 3.x. Configure
      `maven-shade-plugin` to produce an executable `target/jenny.jar`.
- [ ] **T02** Add `.gitignore`, `.mvn/wrapper/`, `mvnw`, `mvnw.cmd`.
- [ ] **T03** Copy the **Approach** section of this plan into
      `docs/DESIGN.md`. Copy the **Task sheet** into `TASKS.md`.

### Phase 1 — domain + tuple enumeration
- [ ] **T04** `Dimension`, `Feature` records. Derive feature name from index.
- [ ] **T05** `Without` value type + `matches(TestCase)` helper.
- [ ] **T06** `AllowedTuple` value type.
- [ ] **T07** `TupleEnumerator` — Guava `Sets.combinations` +
      `Lists.cartesianProduct`, filtered by withouts. Unit test against the
      worked example at jenny.c:50.
- [ ] **T08** `JennySolution` `@PlanningSolution`, `TestCase` `@PlanningEntity`
      with fixed `f0..f127` nullable feature variables, boolean `active`,
      `@PlanningPin` field.

### Phase 2 — constraints + solver config
- [ ] **T09** `JennyConstraintProvider`: `coverAllTuples` (hard, `ifNotExists`).
- [ ] **T10** `JennyConstraintProvider`: `respectWithouts` (hard, `join`).
- [ ] **T11** `JennyConstraintProvider`: `minimizeActiveTests` (soft).
- [ ] **T12** `solverConfig.xml` per the XML skeleton in DESIGN.md.
- [ ] **T13** `RandomizeRowMoveIteratorFactory` — re-roll every dim of one
      `TestCase` in a single move.
- [ ] **T14** Per-constraint `ConstraintVerifier` tests (JUnit 6.0.2).

### Phase 3 — CLI + I/O
- [ ] **T15** `WithoutParser` — `-w` grammar; unit tests for multi-feature
      blocks (`-w1a2cd4ac` → 4 restrictions).
- [ ] **T16** `JennyCli` picocli `@Command`. Support glued-value flags `-n3`,
      `-s3`, `-ofoo.txt` via `IParameterConsumer`.
- [ ] **T17** `OutputFormatter` — byte-for-byte jenny output (leading and
      trailing single space, dim numbers 1-indexed, feature letters a–z A–Z).
- [ ] **T18** `-o<file>` / `-o-` ingestion; pinned TestCases.
- [ ] **T19** "Could not cover tuple …" reporting (matches jenny.c:1553–1554).

### Phase 4 — bench mode + regression
- [ ] **T20** `BenchRunner` forks the C jenny binary, times both solvers,
      prints the two-row comparison table.
- [ ] **T21** `BenchRunnerTest` with Mockito 3.x stubbing `ProcessBuilder`.
- [ ] **T22** `CliRegressionTest` covering the Verification scenarios 1, 3,
      4, 5 above.
- [ ] **T23** Manual bench run on jenny.c:50's example invocation; record
      results in `docs/DESIGN.md` under a "Measured baseline" section.

### Phase 5 — polish
- [ ] **T24** `README.md` with build + run instructions, `--bench` demo.
- [ ] **T25** `-h` output reformatted to match jenny's help text layout.
- [ ] **T26** Profile constraint-stream hot paths; revisit nullable `f0..f127`
      design if >10% of CPU is in empty-slot handling (the deferred open
      question in DESIGN.md).
