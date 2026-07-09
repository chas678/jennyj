# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The `jenny` CLI surface (flags, output format) is the public API for SemVer
purposes; solver internals and heuristic tuning are not.

## [Unreleased]

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

[Unreleased]: https://github.com/chas678/jennyj/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/chas678/jennyj/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/chas678/jennyj/releases/tag/v0.1.0
