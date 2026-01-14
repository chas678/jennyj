package com.pobox.chas66;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GreedyInitializer {

    public PairwiseSolution initialize(List<Dimension> dimensions,
                                       Set<Combination> required,
                                       List<ForbiddenCombination> forbidden) {
        Set<Combination> uncovered = new HashSet<>(required);
        List<TestRun> runs = new ArrayList<>();
        int id = 0;

        while (!uncovered.isEmpty()) {
            Map<Integer, Character> rowMap = new HashMap<>();

            // Horizontal Growth
            for (Dimension dim : dimensions) {
                rowMap.put(dim.getId(), pickBestValidFeature(dim, rowMap, uncovered, forbidden));
            }

            TestRun run = new TestRun();
            run.setId(id++);
            run.setActive(true);

            List<FeatureAssignment> assignments = dimensions.stream().map(d -> {
                FeatureAssignment fa = new FeatureAssignment(run, run.getId() + "-" + d.getId(), d);
                fa.setValue(rowMap.get(d.getId()));
                return fa;
            }).collect(Collectors.toList());

            run.setAssignments(assignments);

            // Final validation: If the horizontal growth produced a forbidden row despite
            // greedy checks, we must skip or fix it (rare in pairwise).
            if (forbidden.stream().noneMatch(f -> f.isViolatedBy(run))) {
                runs.add(run);
                uncovered.removeIf(combo -> isCovered(combo, run));
            }

            if (id > 2000) break; // Increased safety break for complex constraints
        }

        return new PairwiseSolution(dimensions, new ArrayList<>(required), runs, null, forbidden);
    }

    private char pickBestValidFeature(Dimension dim, Map<Integer, Character> partialRow,
                                      Set<Combination> uncovered, List<ForbiddenCombination> forbidden) {
        char bestChar = 'a';
        long maxGain = -1;

        for (int i = 0; i < dim.getSize(); i++) {
            char candidate = getCharName(i);

            // Check if this candidate creates a forbidden combination with the partial row
            if (isCandidateForbidden(dim.getId(), candidate, partialRow, forbidden)) {
                continue;
            }

            long gain = countPotentialNewCoverage(dim.getId(), candidate, partialRow, uncovered);

            if (gain > maxGain) {
                maxGain = gain;
                bestChar = candidate;
            }
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
        return c.getAssignments().entrySet().stream()
                .allMatch(e -> r.getAssignmentForDimension(e.getKey()).getValue().equals(e.getValue()));
    }

    private char getCharName(int index) {
        return (index < 26) ? (char) ('a' + index) : (char) ('A' + (index - 26));
    }
}