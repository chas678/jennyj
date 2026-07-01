# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The `jenny` CLI surface (flags, output format) is the public API for SemVer
purposes; solver internals and heuristic tuning are not.

## [Unreleased]

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

[Unreleased]: https://github.com/chas678/jennyj/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/chas678/jennyj/releases/tag/v0.1.0
