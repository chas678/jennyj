# **Jenny-TF: Timefold Optimized Pairwise Generator**

**Jenny-TF** is a high-performance pairwise (and N-wise) test suite generator. It mirrors the CLI syntax and naming conventions of the classic `jenny.c` tool but replaces the simple greedy heuristic with the **Timefold** constraint satisfaction engine. By utilizing metaheuristics like **Tabu Search**, it produces mathematically smaller test suites than the original C version.

## **🚀 Features**

* **Optimal Reduction:** Employs **Local Search** and **Tabu Search** to prune redundant test cases that standard greedy algorithms miss.
* **Jenny-Compatible CLI:** Supports identical parameters including `-n` (strength), `-w` (withouts), and positional dimensions.
* **Modern Java 25 Stack:** Optimized for the latest JVM, leveraging **Timefold Solver**, **Picocli**, and **Google Guava**.
* **Advanced Constraint Engine:** Native handling of "withouts" (impossible combinations) using declarative, multi-threaded constraints.
* **High Performance:** Includes **O(1) Map-based lookups** for high-speed tuple coverage checking and parallel initialization.

## **📋 Prerequisites**

* **Java 25** or higher.
* **Maven 3.9+**.
* (Optional) The original `jenny.c` for side-by-side benchmarking.

## **🛠️ Build**

To compile the project and generate the executable **Uber-JAR** (which includes all dependencies), run:

**mvn clean package**

The resulting artifact will be located at: `target/jennyj2-1.0-SNAPSHOT.jar`

## **📖 Usage**

The syntax follows the `jenny` standard: **java \-jar target/jennyj2-1.0-SNAPSHOT.jar \[-n N\] \[-w WITHOUTS\] \[-s SEED\] \[DIMENSIONS...\]**

### **Parameters**

* **\-n**: The strength of the coverage (default is 2 for pairwise).
* **\-w**: "Withouts" to exclude. Format: `1a2b` excludes rows where Dim 1 is 'a' AND Dim 2 is 'b'.
* **\-s**: Random seed for deterministic, reproducible results.
* **DIMENSIONS**: A list of integers representing the number of features in each dimension.

### **Examples**

1. **Standard Pairwise (3 dimensions with 3, 3, and 2 features):** `java -jar target/jennyj2-1.0-SNAPSHOT.jar 3 3 2`
2. **3-way Testing with Seed:** `java -jar target/jennyj2-1.0-SNAPSHOT.jar -n3 -s42 4 4 3 2`
3. **Complex Constraints:** `java -jar target/jennyj2-1.0-SNAPSHOT.jar -w1a2b -w3c4d 3 3 3 3`

---

## **⚖️ Head-to-Head Comparison**

To compare the efficiency of Jenny-TF against the original C version:

**\# Run original jenny and count rows** `./jenny 10 10 10 | wc -l`

**\# Run Timefold Jenny and count rows** `java -jar target/jennyj2-1.0-SNAPSHOT.jar 10 10 10 | wc -l`

### **Why Timefold?**

* **Greedy trapped in Local Optima:** `Jenny.c` uses a one-pass greedy algorithm. While fast, it often gets "trapped," resulting in redundant rows.
* **The Squeeze Strategy:** `Jenny-TF` uses the greedy result as a seed, then applies a "Squeeze" move. It identifies rows with low coverage density and redistributes their combinations to other rows, allowing the row to be toggled `inactive` and deleted from the suite.

---

## **🏗️ Architecture**

1. **Tuple Generation:** Utilizes Guava's Cartesian Product to map all required N-tuples.
2. **Constraint Filtering:** "Withouts" are applied via regex parsing to remove impossible tuples before solving begins.
3. **Density-Based Greedy Seed:** A randomized, multi-candidate initializer creates a high-density starting baseline.
4. **Optimization Engine:**
   * **Hard Constraint:** 100% coverage of all required, valid N-tuples.
   * **Soft Constraint (Primary):** Heavy penalty (-10,000) per active row to minimize suite size.
   * **Soft Constraint (Secondary):** Density reward (+1) per unique tuple covered to encourage row collapse.
5. **Final Cleanup:** A post-solving subsumption check removes any logically redundant rows before final output.

## **📄 License**

This project is open-source and follows the same philosophy as the original `jenny.c` and the Timefold optimization library.

