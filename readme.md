# **Jenny-TF: Timefold Optimized Pairwise Generator**

**Jenny-TF** is a high-performance pairwise (and N-wise) test suite generator. It mirrors the CLI syntax and naming conventions of the classic `jenny.c` tool but replaces the simple greedy heuristic with the **Timefold** constraint satisfaction engine. By utilizing metaheuristics like **Tabu Search** and **Late Acceptance**, it produces mathematically smaller test suites than the original C version.

---

## **🧩 What is Pairwise Testing?**

**Pairwise testing** (also called **all-pairs testing**) is a combinatorial testing technique that dramatically reduces test suite size while maintaining high defect detection rates. Research shows that most software defects are triggered by interactions between just **two parameters** — making exhaustive testing wasteful for most scenarios.

### **Real-World Example**

Consider testing a new web page feature across multiple configurations:

| Dimension       | Features                                    | Count |
|-----------------|---------------------------------------------|-------|
| Operating System| Mac, Windows, iOS, Android                  | 4     |
| User State      | Logged in, Logged out                       | 2     |
| Browser         | Chrome, Edge, Brave, Firefox, Opera         | 5     |
| JavaScript      | Enabled, Disabled                           | 2     |
| Domain          | DE, SE, FI, DK, NO                          | 5     |
| Dark Mode       | On, Off                                     | 2     |

**Exhaustive testing:** 4 × 2 × 5 × 2 × 5 × 2 = **800 test cases**

**Pairwise testing:** Only **~25 test cases** needed to cover all pairs of features.

### **Comparison: Jenny vs Jenny-TF**

Using dimensions `4 2 5 2 5 2` (800 exhaustive tests):

```bash
# Original jenny.c (greedy hill-climbing)
./jenny -n2 4 2 5 2 5 2
# Result: 28 tests

# Jenny-TF (Timefold constraint optimization)
java -jar target/jennyj2-1.0-SNAPSHOT.jar -n2 4 2 5 2 5 2
# Result: 25 tests (~10% fewer)
```

**Jenny-TF achieves a smaller test suite through advanced optimization** while maintaining complete coverage of all pairwise combinations.

### **Learn More**

- **Pairwise Testing Resources:** [pairwise.org](https://www.pairwise.org/)
- **Original Jenny Tool:** [jenny.c by Bob Jenkins](https://burtleburtle.net/bob/math/jenny.html)
- **Timefold Solver:** [timefold.ai](https://timefold.ai/)

---

## **🚀 Features**

* **Optimal Reduction:** Employs **Local Search**, **Tabu Search**, and **Late Acceptance** to prune redundant test cases that standard greedy algorithms miss.
* **Jenny-Compatible CLI:** Supports identical parameters including `-n` (strength), `-w` (withouts), and positional dimensions.
* **Modern Java 25 Stack:** Optimized for the latest JVM, leveraging **Timefold Solver 1.30.0**, **Picocli**, and **Google Guava**.
* **Advanced Constraint Engine:** Native handling of "withouts" (impossible combinations) using incremental score calculation.
* **High Performance:** Achieves **600K+ moves/sec** on small problems and **150K+ moves/sec** on medium problems through:
  - O(1) hashcode caching for Combination objects
  - Optimized loop-based coverage counting
  - O(1) map-based lookups in TestRun entities
  - IncrementalScoreCalculator for efficient score tracking

## **📋 Prerequisites**

* **Java 25** or higher.
* **Maven 3.9+**.
* (Optional) The original `jenny.c` for side-by-side benchmarking.

## **🛠️ Build**

To compile the project and generate the executable **Uber-JAR** (which includes all dependencies), run:

```bash
mvn clean package
```

The resulting artifact will be located at: `target/jennyj2-1.0-SNAPSHOT.jar`

## **📖 Usage**

The syntax follows the `jenny` standard:

```bash
java -jar target/jennyj2-1.0-SNAPSHOT.jar [-n N] [-w WITHOUTS] [-s SEED] [DIMENSIONS...]
```

### **Parameters**

* **-n**: The strength of the coverage (default is 2 for pairwise).
* **-w**: "Withouts" to exclude. Format: `1a2b` excludes rows where Dim 1 is 'a' AND Dim 2 is 'b'.
* **-s**: Random seed for deterministic, reproducible results.
* **DIMENSIONS**: A list of integers representing the number of features in each dimension.

### **Examples**

**Standard Pairwise (3 dimensions with 3, 3, and 2 features):**
```bash
java -jar target/jennyj2-1.0-SNAPSHOT.jar 3 3 2
```

**3-way Testing with Seed:**
```bash
java -jar target/jennyj2-1.0-SNAPSHOT.jar -n3 -s42 4 4 3 2
```

**Complex Constraints:**
```bash
java -jar target/jennyj2-1.0-SNAPSHOT.jar -w1a2b -w3c4d 3 3 3 3
```

---

## **⚖️ Head-to-Head Comparison**

To compare the efficiency of Jenny-TF against the original C version:

```bash
# Run original jenny and count rows
./jenny 10 10 10 | wc -l

# Run Timefold Jenny and count rows
java -jar target/jennyj2-1.0-SNAPSHOT.jar 10 10 10 | wc -l
```

### **Why Timefold?**

* **Greedy trapped in Local Optima:** `jenny.c` uses a one-pass greedy algorithm. While fast, it often gets "trapped," resulting in redundant rows.
* **The Squeeze Strategy:** `Jenny-TF` uses the greedy result as a seed, then applies a "Squeeze" move. It identifies rows with low coverage density and redistributes their combinations to other rows, allowing the row to be toggled `inactive` and deleted from the suite.

---

## **🏗️ Architecture**

### **Two-Phase Optimization Strategy**

1. **Greedy Initialization** (`GreedyInitializer.java`):
   - Generates a feasible starting solution using randomized, multi-candidate greedy algorithm
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

### **Score Calculation**

The project uses `IncrementalScoreCalculator` (with `EasyScoreCalculator` fallback) using a **HardMediumSoft** score:

* **Hard Score:**
  - -100000 per forbidden combination violation (from -w constraints)
  - -10000 per uncovered combination (must be 0 for valid solution)
* **Medium Score:** -1 per active TestRun (minimizes suite size)
* **Soft Score:** Currently unused

### **The "Squeeze" Strategy**

The solver identifies rows with low coverage density and redistributes their combinations to other rows, allowing the original row to be toggled inactive and removed from the suite. This happens through:
1. Swap moves that consolidate coverage into fewer rows
2. The medium score creating pressure to deactivate rows
3. The greedy initializer providing a good starting point

### **Performance Optimizations**

* **Combination Hashcode Caching:** Pre-computes hash values at construction for O(1) HashMap operations
* **Optimized Coverage Counting:** Direct iteration instead of stream-based counting in hot paths
* **O(1) Tuple Lookups:** TestRun maintains an assignmentMap for constant-time dimension access
* **Incremental Score Tracking:** Efficiently tracks score deltas instead of full recalculation

### **Post-Solve Cleanup**

After optimization, `JennyTF.printOutput()` performs subsumption checking to remove any duplicate or redundant rows before final output.

---

## **📊 Benchmarking**

Jenny-TF includes a comprehensive benchmarking framework to compare different solver configurations and measure performance improvements.

### **Running Benchmarks**

To run the full benchmark suite:

```bash
mvn exec:java -Dexec.mainClass="com.pobox.chas66.PairwiseBenchmarkApp"
```

This will:
- Test 3 different problem sizes (small, medium, large)
- Compare 4 solver configurations:
  - `Easy-30s`: EasyScoreCalculator with 30s timeout
  - `Incremental-30s`: IncrementalScoreCalculator with 30s timeout (recommended)
  - `Incremental-60s`: IncrementalScoreCalculator with 60s timeout
  - `Incremental-NoSwap`: IncrementalScoreCalculator without swap moves
- Generate an HTML report with interactive charts at `target/benchmark-results/[timestamp]/index.html`

### **Benchmark Results**

On typical hardware (Apple Silicon M-series, 8 cores):

**Small Problems (3×3×2):**
- Incremental: **612,654 moves/sec**
- Easy: 288,340 moves/sec
- **2.1x speedup**

**Medium Problems (6×6×5):**
- Incremental: **155,982 moves/sec**
- Easy: 4,733 moves/sec
- **33x speedup**

### **Understanding the Report**

The HTML report includes:
- **Best Score Summary:** Final solution quality for each configuration
- **Move Evaluation Speed:** Moves evaluated per second (higher is better)
- **Score Over Time:** Charts showing optimization progress
- **Score Distribution:** How solutions compare across different problem sizes

### **Recommended Configuration**

Based on benchmarking, **Incremental-30s** provides the best balance:
- Fast enough for interactive use (600K+ moves/sec on small problems)
- Achieves optimal solutions on small/medium problems
- Much faster than EasyScoreCalculator

---

## **🧪 Testing**

Run the test suite:

```bash
mvn test
```

Run a specific test class:

```bash
mvn test -Dtest=JennyTFTest
```

Run a specific test method:

```bash
mvn test -Dtest=JennyTFTest#testSimpleWithoutConstraint
```

---

## **📄 License**

This project is open-source and follows the same philosophy as the original `jenny.c` and the Timefold optimization library.

