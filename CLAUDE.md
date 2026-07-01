# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`jenny-timefold` — a CLI-compatible reimplementation of Bob Jenkins' `jenny.c`
pairwise/N-wise combinatorial test generator, built on the Timefold Solver
constraint-optimisation engine. Goal: produce **smaller, feasible** test suites
than jenny.c on the same input, driven by a three-phase solver. Java 26,
Timefold 2.2.0, Maven. Main class `com.burtleburtle.jenny.cli.JennyCli`.

## Commands

```bash
mvn package                         # unit tests + shaded uber-jar → target/jenny.jar
mvn test                            # unit tests only (surefire, *Test classes), ~15s
mvn verify                          # unit tests + 3 long ITs (failsafe, *IT classes), ~2min
mvn verify -DskipITs                # same as mvn package
mvn test -Dtest=SolutionVerificationTest      # single surefire class
mvn verify -Dit.test=JennyBeatsBenchmarkIT    # single failsafe IT (the goal-line oracle)
mvn exec:java                       # JennyBenchmarkApp → HTML report in target/benchmark-results/

java -jar target/jenny.jar -n3 -s0 4 4 3 3 3 3 3 3 4 3 3 4 -w1abc2d ...   # run
java -jar target/jenny.jar --bench --jenny-path jenny/jenny -n2 ...       # head-to-head vs C binary
java -jar target/jenny.jar --version                                      # prints "jenny <pom version>"
```

## Distribution & releases

Published via Homebrew: `brew install chas678/jennyj/jenny` (tap repo
`chas678/homebrew-jennyj`, formula wraps the shaded jar + `depends_on "openjdk"`).
SemVer; the pom `<version>` is the source of truth and the git tag `vX.Y.Z` must
equal it. Pushing a `v*` tag triggers `.github/workflows/release.yml`, which
builds `jenny.jar`, publishes a GitHub release with it attached, and (when the
`TAP_GITHUB_TOKEN` secret is set) bumps the tap formula's `url`/`sha256`.
`jenny --version` reads `Implementation-Version` from the jar manifest, injected
by the shade `ManifestResourceTransformer` — keep pom version, tag, and that
manifest entry in lockstep. Release steps: `docs/RELEASING.md`; user-facing
history: `CHANGELOG.md`. GraalVM native binary is deferred (Timefold Gizmo
bytecode-gen blocks standalone native-image).

Test tiering is **by filename convention only** (no JUnit tags): `*Test` →
surefire (`mvn test`), `*IT` → failsafe (`mvn verify`). The long ITs are
`JennyBeatsBenchmarkIT`, `SolverProfilingIT`, `GreedyInitializerProfilingIT`.

## CLI conventions (must match jenny.c)

Short options use **attached-value** style: `-n3`, `-w1a2b`, `-ofile.txt` (not
`-n 3` / `-n=3`). Long options use a **space** separator: `--time-limit-seconds
30` (the `=` form is deliberately rejected). Positional args are per-dimension
feature counts. `-w<spec>` (forbidden combinations) is repeatable.

## Architecture

Pipeline: **greedy init → 3 solver phases**, wired in `solverConfig.xml`.

1. `bootstrap.GreedyInitializer` builds a near-feasible starting suite via
   randomised greedy set-cover, ordering uncovered tuples by rarity score.
2. **Phase 1 (Tabu consolidation)** — Tabu Search over a weighted union of five
   moves: two `ChangeMoveSelector`s (`TestCell.feature`, `TestCase.active`) plus
   three custom `MoveIteratorFactory` classes (`RandomizeRow`,
   `DeactivateRedundant`, `MergeTests`). Shrinks the suite.
3. **Phase 2 (Hill Climbing)** — strict-improvement acceptor; cannot worsen the
   score, so it repairs any coverage Phase 1 broke without back-sliding.
4. **Phase 3 (Tabu feasibility repair)** — drives to `0hard`; terminates on
   `bestScoreFeasible=true`.

### Constraint model (`solver.JennyConstraintProvider`)

| Constraint | Level | Meaning |
|---|---|---|
| `coverAllTuples` | −1 HARD each | every allowed tuple covered by an active row |
| `respectWithouts` | **−2 HARD each** | no active row matches a forbidden combo |
| `minimizeActiveTests` | −1 SOFT each | fewer active rows |

The **2-hard weight on `respectWithouts` is intentional and load-bearing**:
breaking a Without violation is always a strict hard-score gain over leaving one
tuple uncovered, so Phase 3's tabu acceptor commits to the repair instead of
oscillating. Don't "simplify" it to −1 without re-validating the oracle.

### Domain (`domain/`)

`AllowedTuple` = planning fact; `TestCase` (`active`) and `TestCell` (`feature`)
= planning entities. `TestCase.featuresByDim` is a Timefold **shadow variable**
giving O(1) dim→feature lookups in the coverage check. `CoverageUtil.coversTuple`
is the single shared coverage check (constraint provider, greedy init, recounts).
`AllowedTuple` caches its hashcode (used as a constraint-stream `HashMap` key
millions of times).

## Gotchas

- Phase termination is **wall-clock-based**, so active-test count varies ±1
  run-to-run. `JennyBeatsBenchmarkIT` is the authoritative correctness/quality
  oracle (asserts ≤116 active, 0 uncovered, 0hard); its wall-clock assertion is
  a loose sanity ceiling, not the budget.
- Solver constants (`entityTabuSize`, `acceptedCountLimit`, move weights) are
  tuned against the self-test benchmark — changing them requires re-running the
  oracle IT. Move weights carry annotated A/B history in `solverConfig.xml`.
- Current stack is Java 26 + Timefold 2.2.0 (`pom.xml` is source of truth).
  The 2.1.0→2.2.0 bump needed no source changes (bug-fix/internal release, no
  API/config/breaking changes). Historical TASKS.md/PLAN.md phase entries still
  name older versions by design — don't rewrite that history.

## Reference docs

`README.md` (full usage + benchmarks), `docs/DESIGN.md` (design-of-record),
`TASKS.md` (resumable checklist), `jenny/` (original C source + binary + PDF docs).
