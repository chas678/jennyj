package com.pobox.chas66;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class GreedyInitializer {
    private final Random random;

    public GreedyInitializer() {
        this.random = new Random();
    }

    public GreedyInitializer(long seed) {
        this.random = new Random(seed);
    }

    public PairwiseSolution initialize(List<Dimension> dimensions,
                                       Set<Combination> required,
                                       List<ForbiddenCombination> forbidden) {
        Set<Combination> uncovered = new HashSet<>(required);
        List<TestRun> runs = new ArrayList<>();
        int idCounter = 0;
        int successfulRows = 0;

        while (!uncovered.isEmpty()) {
            Map<Integer, Character> rowMap = new HashMap<>();

            // 1. Horizontal Growth: Pick the best feature for each dimension
            for (Dimension dim : dimensions) {
                rowMap.put(dim.getId(), pickBestValidFeature(dim, rowMap, uncovered, forbidden));
            }

            // 2. Construct the TestRun object
            TestRun run = new TestRun();
            run.setId(idCounter++);
            run.setActive(true);

            // 3. Map assignments to the TestRun
            List<FeatureAssignment> assignments = dimensions.stream().map(d -> {
                FeatureAssignment fa = new FeatureAssignment(run, run.getId() + "-" + d.getId(), d);
                fa.setValue(rowMap.get(d.getId()));
                return fa;
            }).collect(Collectors.toList());

            run.setAssignments(assignments);

            // 4. Final validation: Ensure the generated row doesn't violate any -w rules
            if (forbidden.stream().noneMatch(f -> f.isViolatedBy(run))) {
                runs.add(run);
                uncovered.removeIf(combo -> isCovered(combo, run));
                successfulRows++;
            }

            // 5. Safety breaks to prevent infinite loops in impossible constraint scenarios
            if (successfulRows > 1000 || idCounter > 2000) {
                break;
            }
        }

        return new PairwiseSolution(dimensions, new ArrayList<>(required), runs, null, forbidden);
    }

    private char pickBestValidFeature(Dimension dim, Map<Integer, Character> partialRow,
                                      Set<Combination> uncovered, List<ForbiddenCombination> forbidden) {
        char bestChar = 'a';
        long maxGain = -1;
        List<Character> equalBestOptions = new ArrayList<>();

        for (int i = 0; i < dim.getSize(); i++) {
            char candidate = getCharName(i);

            // Check if this candidate violates any -w rules
            if (isCandidateForbidden(dim.getId(), candidate, partialRow, forbidden)) {
                continue;
            }

            long gain = countPotentialNewCoverage(dim.getId(), candidate, partialRow, uncovered);

            if (gain > maxGain) {
                maxGain = gain;
                bestChar = candidate;
                equalBestOptions.clear();
                equalBestOptions.add(candidate);
            } else if (gain == maxGain && gain >= 0) {
                equalBestOptions.add(candidate);
            }
        }

        // If multiple features provide the same (or zero) gain, pick one randomly
        // to avoid defaulting everything to 'a'
        if (!equalBestOptions.isEmpty()) {
            return equalBestOptions.get(random.nextInt(equalBestOptions.size()));
        }

        return bestChar;
    }

    private boolean isCandidateForbidden(int dimId, char feature, Map<Integer, Character> partialRow,
                                         List<ForbiddenCombination> forbidden) {
        // Create a temporary map to represent the row state with the candidate [cite: 17, 20]
        Map<Integer, Character> tempRow = new HashMap<>(partialRow);
        tempRow.put(dimId, feature);

        for (ForbiddenCombination f : forbidden) {
            // A rule is violated only if ALL dimensions in the restriction exist in our tempRow
            // AND all values match the forbidden characters [cite: 19-21]
            boolean allRestrictionsMatch = f.getRestrictions().entrySet().stream().allMatch(entry -> {
                Character currentVal = tempRow.get(entry.getKey());
                return currentVal != null && entry.getValue().contains(currentVal);
            });

            if (allRestrictionsMatch) return true;
        }
        return false;
    }

    private long countPotentialNewCoverage(int dimId, char feature, Map<Integer, Character> partialRow, Set<Combination> uncovered) {
        return uncovered.stream().filter(combo -> {
            Character req = combo.getAssignments().get(dimId);
            if (req != null && req != feature) return false;

            return combo.getAssignments().entrySet().stream()
                    .filter(e -> e.getKey() != dimId)
                    .allMatch(e -> !partialRow.containsKey(e.getKey()) || partialRow.get(e.getKey()).equals(e.getValue()));
        }).count();
    }

    private boolean isCovered(Combination c, TestRun r) {
        if (!r.getActive()) return false; // Ensure sync with solver logic
        return c.getAssignments().entrySet().stream()
                .allMatch(e -> {
                    FeatureAssignment fa = r.getAssignmentForDimension(e.getKey());
                    return fa != null && fa.getValue().equals(e.getValue());
                });
    }

    private char getCharName(int index) {
        return (index < 26) ? (char) ('a' + index) : (char) ('A' + (index - 26));
    }
}