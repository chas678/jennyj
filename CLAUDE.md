# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Jenny-TF is a Timefold-optimized pairwise (N-wise) test suite generator that mirrors the CLI syntax of the classic `jenny.c` tool while using constraint satisfaction and metaheuristics (Tabu Search, Late Acceptance) to produce mathematically smaller test suites.

## Build and Run Commands

**Build the project:**
```bash
mvn clean package
```
This creates an uber-JAR at `target/jennyj2-1.0-SNAPSHOT.jar` with all dependencies included.

**Run the application:**
```bash
java -jar target/jennyj2-1.0-SNAPSHOT.jar [OPTIONS] DIMENSIONS...
```

**Common CLI patterns:**
- Standard pairwise: `java -jar target/jennyj2-1.0-SNAPSHOT.jar 3 3 2`
- 3-way with seed: `java -jar target/jennyj2-1.0-SNAPSHOT.jar -n3 -s42 4 4 3 2`
- With constraints: `java -jar target/jennyj2-1.0-SNAPSHOT.jar -w1a2b -w3c4d 3 3 3 3`

**Run tests:**
```bash
mvn test
```

**Run a single test class:**
```bash
mvn test -Dtest=JennyTFTest
```

**Run a specific test method:**
```bash
mvn test -Dtest=JennyTFTest#testSimpleWithoutConstraint
```

## Core Architecture

### Two-Phase Optimization Strategy

1. **Greedy Initialization** (`GreedyInitializer.java`):
   - Generates a feasible starting solution using a randomized, multi-candidate greedy algorithm
   - For each uncovered combination, builds rows by selecting features that maximize tuple coverage
   - Respects forbidden combinations (-w constraints) during initialization
   - Creates the minimum viable test suite as a baseline

2. **Timefold Optimization** (`PairwiseSolverFactory.java`):
   - Adds buffer rows (inactive) to provide "draft space" for the solver
   - Uses Local Search with three move types:
     - Value changes on `FeatureAssignment` entities
     - Active/inactive toggles on `TestRun` entities
     - Swap moves between assignments (essential for consolidation)
   - Employs Tabu Search + Late Acceptance + Step Counting Hill Climbing
   - Default termination: 15s unimproved or 45s total

### Domain Model

**Core entities:**
- `PairwiseSolution`: Planning solution containing dimensions, required combinations, test runs, and constraints
- `TestRun`: Planning entity with active/inactive state and feature assignments
  - Maintains an O(1) lookup map (`assignmentMap`) for score calculation
  - Each assignment change triggers map rebuild via `setAssignments()`
- `FeatureAssignment`: Planning entity representing a dimension value in a test run
- `Dimension`: Problem fact representing a dimension with size (number of features)
- `Combination`: Problem fact representing a required N-tuple to cover
- `ForbiddenCombination`: Problem fact representing impossible combinations from -w flags

**Score Calculation** (`PairwiseEasyScoreCalculator.java`):
- Uses `EasyScoreCalculator` to recalculate full score from scratch (avoids score corruption issues)
- **Hard score:** -100000 per forbidden combination violation (from -w constraints)
- **Hard score:** -10000 per uncovered combination (must be 0 for valid solution)
- **Medium score:** -1 per active TestRun (minimizes suite size)
- **Soft score:** Currently unused
- Note: Earlier versions used constraint streams but had score corruption due to transitive variable access

### The "Squeeze" Strategy

The solver identifies rows with low coverage density and redistributes their combinations to other rows, allowing the original row to be toggled inactive and removed from the suite. This happens through:
1. Swap moves that consolidate coverage into fewer rows
2. The medium score creating pressure to deactivate rows
3. The greedy initializer providing a good starting point

### Feature Encoding

Features are encoded as characters: `a-z` (indices 0-25), then `A-Z` (indices 26-51).

Dimensions are 1-indexed in CLI output but 0-indexed internally.

### Constraint Syntax

The `-w` flag accepts patterns like `1a2b` meaning "dimension 1 cannot be 'a' when dimension 2 is 'b'". Multiple character sets per dimension are supported: `-w1ab2cd` forbids any combination where dim 1 is in {a,b} AND dim 2 is in {c,d}.

## Key Implementation Details

**O(1) Tuple Coverage Checking:**
- `TestRun.assignmentMap` provides constant-time dimension lookups during constraint evaluation
- Map is rebuilt whenever assignments change (see `TestRun.setAssignments()`)
- The `version` field tracks mutation for debugging

**Post-Solve Cleanup:**
- After optimization, `JennyTF.printOutput()` performs subsumption checking
- Removes rows that are identical to other rows (isSubsumed check)
- Uses ID-based symmetry breaking to avoid removing both rows in a duplicate pair

**Logging:**
- Application logs to both console and timestamped files in `logs/` directory
- Configured in `src/main/resources/logback.xml`
- Timefold solver and application code log at DEBUG level
- Progress updates show: active rows, hard score (uncovered tuples), soft score (density)

## Testing

The test suite uses JUnit 5 with Hamcrest matchers. Tests verify:
- Forbidden combinations are respected in all active rows
- Hard score is zero (complete coverage)
- Optimization produces smaller suites than naive greedy
- Constraint enforcement is strict

## Technology Stack

- Java 25 (required)
- Maven 3.9+
- Timefold Solver 1.29.0 (constraint satisfaction engine)
- Picocli 4.7.7 (CLI framework, enables `-n2` style arguments)
- Google Guava 33.5.0 (Sets.cartesianProduct for tuple generation)
- Lombok 1.18.42 (reduces boilerplate)
- SLF4J + Logback (logging)

## Development Notes

**When modifying the score calculator:**
- The score is calculated in `PairwiseEasyScoreCalculator.calculateScore()`
- Hard score: Penalize uncovered combinations (must be zero for valid solution)
- Medium score: Penalize active rows (optimization objective)
- Soft score: Currently unused
- All score calculations must handle inactive rows by filtering them out

**When adding move types:**
- Register in `PairwiseSolverFactory.createConfig()` under `UnionMoveSelectorConfig`
- Ensure moves respect forbidden combinations
- Test with small problem sizes first

**Performance tuning:**
- Buffer row count is calculated to balance search space vs. speed (currently `max(15, min(25, largest × second_largest / 3))`)
- Termination: 30s unimproved or 90s total (adjust in `PairwiseSolverFactory` for faster/slower runs)
- Move evaluation speed varies with problem size:
  - Small problems (< 100 combinations): 7000+ moves/sec
  - Medium problems (100-1000 combinations): 1000-5000 moves/sec
  - Large problems (1000-10000 combinations): 30-200 moves/sec
- Swap moves are essential for optimal solutions but slow (~10x slower than change moves)
- Environment mode: FAST_ASSERT for development, NO_ASSERT for production speed

## Implementation Notes

**Score Calculation Approach:**

The project uses `EasyScoreCalculator` instead of constraint streams. Earlier versions attempted to use constraint streams but encountered score corruption issues because the constraints accessed `FeatureAssignment.value` (a planning variable) transitively through `TestRun.getAssignmentForDimension()`. This prevented Timefold's incremental score calculator from properly tracking planning variable changes.

The `EasyScoreCalculator` approach recalculates the full score from scratch for each move, which is simpler and avoids incremental calculation bugs. For this problem size (< 10k combinations), the performance is acceptable (~30-33k moves/sec on typical hardware).
