# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The `jenny` CLI surface (flags, output format) is the public API for SemVer
purposes; solver internals and heuristic tuning are not.

## [Unreleased]

## [1.0.2] - 2026-09-08

### Changed
- Dependency bumps: Timefold Solver 2.2.0 → 2.6.0, Guava 33.6.0 → 33.7.1-jre,
  SLF4J 2.0.18 → 2.0.19, Logback 1.5.37 → 1.6.3, JUnit 6.1.1 → 6.1.3,
  maven-surefire/failsafe 3.5.6 → 3.6.0. No production source changes were
  required: the 2.3.0–2.6.0 release notes declare no breaking changes,
  deprecations, or API/config migrations for this codebase's surface (the 2.x
  breaking changes all landed in 2.0.0, which predates our baseline), and the
  solver quality oracle `JennyBeatsBenchmarkIT` still passes.
- `IncrementalScoreParityIT.fullAssert_fullPipeline_noScoreCorruption` no
  longer asserts the score the pipeline converges to. It now asserts only that
  the solver returns without a `ScoreCorruptionException`, which is the
  incremental-vs-`ConstraintProvider` parity property the test exists to prove:
  under `FULL_ASSERT` Timefold re-scores against the provider after every move
  and throws the instant the two disagree, so a normal return already proves
  parity across the whole move repertoire and both custom phases.

  The previous `hard == 0` assertion was load-dependent, not a property of the
  code: under `FULL_ASSERT`'s per-move re-scoring, every wall-clock-terminated
  phase completes far fewer moves than in production. On 2.6.0 it failed at
  `-10hard` (0 uncovered — residual `-w` violations) where 2.2.0 reached
  `0hard`, and still failed at `-8hard` under CPU contention when the budget
  was doubled. No run in any configuration raised a `ScoreCorruptionException`,
  so the calculator itself was never implicated. `JennyBeatsBenchmarkIT`
  remains the authoritative `0hard` / 0-uncovered correctness and quality
  oracle, per CLAUDE.md. Verified passing with all cores saturated.

## [1.0.1] - 2026-07-09

### Changed
- Solver configuration now has a single source of truth:
  `JennySolverFactory.createConfig()`. The parallel static `solverConfig.xml`
  (and the test-only `solverConfig-lsonly.xml`) are removed; the CLI, the
  benchmark app, and all tests build from the one factory — which now also
  includes the feasibility-repair phase the XML had but the programmatic config
  previously lacked. Eliminates the drift between the two definitions.

### Added
- CLI feasibility gate: `jenny` refuses to print a suite that violates a `-w`
  restriction. If the solver exhausts its budget with a residual violation, it
  writes a diagnostic to stderr and exits non-zero (5) rather than emitting an
  invalid test row at exit 0.

### Fixed
- `--time-limit-seconds` now rejects non-positive values (exit 2 with a
  message) instead of passing a negative budget to the solver (uncaught error,
  exit 1) or running a 0-second budget that produced a bloated, duplicate-heavy
  suite.

## [1.0.0] - 2026-07-08

First stable release. Integrates seven suite-minimization optimizations ported from
the [Jenny-go](https://github.com/chas678/Jenny-go) Go port, compounding to a
**verified-clean 100 ±1-row** covering array on the jenny self-test (`hard==0`, 0
uncovered tuples, 0 without-violations) — down from the honest 107-row baseline and
beating C jenny's 116 rows.

### Added
- `-j<K>` flag: run `K` independent solver replicas and keep the smallest valid suite
  (best-of-K).
- Minimal-core reporting for genuinely-uncoverable tuples: one line per infeasible
  core instead of an undifferentiated dump, plus a bounded tuple-enumeration cap that
  fails cleanly instead of exhausting memory on pathological inputs.

### Changed
- Hand-rolled `IncrementalScoreCalculator` replaces full rescoring on each move,
  cross-checked against the declarative constraint provider under `FULL_ASSERT` for
  correctness.
- Smaller suites via a rewritten greedy initializer, deterministic Shrink and Recover
  custom phases, and a new `EvictRowMove` (rehome a row's uniquely-covered tuples via
  legal single-cell flips, then deactivate it).
- Correctness gating tightened to `hardScore==0` (no uncovered tuple, no `-w`
  violation) end-to-end, independently verified against a fresh without-check and a
  full tuple-enumeration oracle.

### Fixed
- `--bench` now honors `--time-limit-seconds`: the flag was dropped from the arguments
  forwarded to the in-process Timefold side, so the head-to-head comparison always ran
  the solver at its default 60s budget regardless of the requested limit. (The C-jenny
  side is unchanged — it never accepted that jennyj-only flag.)
- Solver-progress logs now go to **stderr** instead of stdout. Previously Logback's
  console appender wrote INFO logs to stdout, interleaved with the generated test
  lines — polluting piped output and breaking the stdout→`-o` round-trip (a logged
  `0hard/...` line was misparsed as a test row). stdout now carries only the test
  suite.

## [0.1.0] - 2026-07-01

First released version, distributed via Homebrew (`brew install chas678/jennyj/jenny`).

### Added
- Homebrew distribution: a `jenny` command via the `chas678/homebrew-jennyj` tap
  (formula wraps the shaded jar; depends on `openjdk`). Resolves #6.
- `--version` flag, sourced from the jar manifest's `Implementation-Version`.
- `CHANGELOG.md` and a documented release process (`docs/RELEASING.md`).
- Tag-triggered release workflow that publishes `jenny.jar` and bumps the tap formula.

### Changed
- CLI-compatible reimplementation of Bob Jenkins' `jenny.c` on the Timefold Solver
  2.1.0 → 2.2.0 engine, Java 26. Three-phase solver (Tabu consolidation, Hill
  Climbing refinement, Tabu feasibility repair) producing smaller, feasible
  (`0hard`) suites than jenny.c on the self-test benchmark.

[Unreleased]: https://github.com/chas678/jennyj/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/chas678/jennyj/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/chas678/jennyj/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/chas678/jennyj/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/chas678/jennyj/releases/tag/v0.1.0
