#!/bin/bash
# Benchmark script for testing jennyj2 optimizations
# Usage: ./benchmark_optimizations.sh

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Ensure JAR is built
if [ ! -f "target/jennyj2-1.0-SNAPSHOT.jar" ]; then
    echo -e "${YELLOW}Building jennyj2...${NC}"
    mvn clean package -DskipTests
fi

# Test cases: description, arguments
declare -a TEST_CASES=(
    "Trivial 2x2x2:2 2 2"
    "Small 3x3x3:3 3 3"
    "Medium 4x4x3x2:4 4 3 2"
    "Large 5x5x5:5 5 5"
    "Constrained 3x3x3 -w1a2b:3 3 3 -w1a2b"
    "Two large 10x10:10 10"
    "3-way 2x2x2x2 -n3:2 2 2 2 -n3"
    "3-way 3x3x3 -n3:3 3 3 -n3"
    "Many dims 2x2x2x2x2x2:2 2 2 2 2 2"
)

echo "============================================"
echo "jennyj2 Optimization Benchmark Suite"
echo "============================================"
echo ""

# CSV header
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_FILE="benchmark_results_${TIMESTAMP}.csv"
echo "Test Case,Arguments,Active Rows,Total Combinations,Solving Time (ms),Score" > "$OUTPUT_FILE"

# Run each test case
for test_case in "${TEST_CASES[@]}"; do
    # Split description and args
    IFS=':' read -r description args <<< "$test_case"

    echo -e "${GREEN}Testing: $description${NC}"
    echo "  Args: $args"

    # Run jennyj2 and capture output
    OUTPUT=$(java -jar target/jennyj2-1.0-SNAPSHOT.jar $args 2>&1)

    # Extract metrics using grep/awk
    ACTIVE_ROWS=$(echo "$OUTPUT" | grep -o "^[0-9]\+" | wc -l)
    COMBINATIONS=$(echo "$OUTPUT" | grep "Total Combinations" | grep -o "[0-9]\+" || echo "N/A")
    SOLVING_TIME=$(echo "$OUTPUT" | grep "Solving ended" | grep -o "time spent ([0-9]\+)" | grep -o "[0-9]\+" || echo "N/A")
    SCORE=$(echo "$OUTPUT" | grep "best score" | tail -1 | grep -o "(.*)" | head -1 || echo "N/A")

    # Display results
    echo "  Active Rows: $ACTIVE_ROWS"
    echo "  Total Combinations: $COMBINATIONS"
    echo "  Solving Time: ${SOLVING_TIME}ms"
    echo "  Score: $SCORE"
    echo ""

    # Write to CSV
    echo "\"$description\",\"$args\",$ACTIVE_ROWS,$COMBINATIONS,$SOLVING_TIME,\"$SCORE\"" >> "$OUTPUT_FILE"
done

echo -e "${GREEN}Benchmark complete!${NC}"
echo "Results saved to: $OUTPUT_FILE"
echo ""
echo "To compare with baseline, run this script before and after optimizations:"
echo "  ./benchmark_optimizations.sh > baseline.txt  # Before"
echo "  # Make optimizations"
echo "  ./benchmark_optimizations.sh > optimized.txt # After"
echo "  diff baseline.txt optimized.txt"
