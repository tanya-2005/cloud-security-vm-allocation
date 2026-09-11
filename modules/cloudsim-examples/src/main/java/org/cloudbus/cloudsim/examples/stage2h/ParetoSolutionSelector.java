package org.cloudbus.cloudsim.examples.stage2h;

import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;
import org.cloudbus.cloudsim.examples.stage2g.NssaoOptimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects exactly one solution from the final Pareto front using a deterministic,
 * defensible lexicographic preference rule.
 *
 * <h3>Selection Rule (resolving Stage 2G.3 audit I3)</h3>
 * Among all front-1 members, choose the solution that is lexicographically
 * minimal on {@code (f2, f3, f1, f4)}, with index as final tie-break.
 *
 * <h3>Justification</h3>
 * <ol>
 *   <li><b>f2 (co-residency risk) FIRST:</b> Security threat is the paper's PRIMARY motivation.
 *       Minimizing f2 is the most security-aligned choice.</li>
 *   <li><b>f3 (cost) SECOND:</b> Among solutions with equal f2, prefer lower operational cost.
 *       Cost is directly observable and operationally relevant.</li>
 *   <li><b>f1 (threat×server-risk) THIRD:</b> Secondary security dimension after direct co-residency.</li>
 *   <li><b>f4 (unused capacity) LAST:</b> Resource efficiency as final tie-break.
 *       Less critical than security and cost in the paper's framing.</li>
 *   <li><b>Index (if all else equal):</b> Ultimate deterministic tie-break.</li>
 * </ol>
 *
 * <h3>Properties</h3>
 * <ul>
 *   <li>Deterministic: same front → same selection, always</li>
 *   <li>Defensible: priority order aligns with paper's security focus</li>
 *   <li>Transparent: no hidden weights, no invented parameters</li>
 *   <li>Auditable: lexicographic order is trivial to verify manually</li>
 *   <li>NOT a claim: this is "optimal" or "best" in any absolute sense</li>
 * </ul>
 *
 * <h3>Duplicate Handling</h3>
 * Multiple positions may decode to the same placement (due to repair or rounding),
 * producing identical objective vectors. Front-1 is deduplicated by unique placement
 * BEFORE applying the selection rule.
 */
public final class ParetoSolutionSelector {

    /**
     * Result of the selection process.
     */
    public static final class SelectionResult {
        private final NssaoOptimizer.Individual selected;
        private final List<NssaoOptimizer.Individual> front1Raw;
        private final List<NssaoOptimizer.Individual> front1Deduplicated;
        private final int duplicatesRemoved;

        SelectionResult(NssaoOptimizer.Individual selected,
                        List<NssaoOptimizer.Individual> front1Raw,
                        List<NssaoOptimizer.Individual> front1Deduplicated,
                        int duplicatesRemoved) {
            this.selected = selected;
            this.front1Raw = front1Raw;
            this.front1Deduplicated = front1Deduplicated;
            this.duplicatesRemoved = duplicatesRemoved;
        }

        public NssaoOptimizer.Individual getSelected() {
            return selected;
        }

        public List<NssaoOptimizer.Individual> getFront1Raw() {
            return front1Raw;
        }

        public List<NssaoOptimizer.Individual> getFront1Deduplicated() {
            return front1Deduplicated;
        }

        public int getDuplicatesRemoved() {
            return duplicatesRemoved;
        }

        public int getFront1RawSize() {
            return front1Raw.size();
        }

        public int getUniquePlacements() {
            return front1Deduplicated.size();
        }

        public ObjectiveValues getSelectedObjectives() {
            return selected.getObjectives();
        }

        public CandidatePlacement getSelectedPlacement() {
            return selected.getPlacement();
        }
    }

    /**
     * Select exactly one solution from the final Pareto front.
     *
     * @param nssaoResult NSSAO optimizer run result
     * @return selection result with chosen individual and statistics
     * @throws IllegalArgumentException if front-1 is empty or contains invalid individuals
     */
    public SelectionResult select(NssaoOptimizer.RunResult nssaoResult) {
        if (nssaoResult == null) {
            throw new IllegalArgumentException("nssaoResult cannot be null");
        }

        List<NssaoOptimizer.Individual> front1Raw = nssaoResult.getFirstFront();
        if (front1Raw.isEmpty()) {
            throw new IllegalArgumentException("Front-1 is empty — no solutions to select");
        }

        // Validate all front-1 members
        for (int i = 0; i < front1Raw.size(); i++) {
            NssaoOptimizer.Individual ind = front1Raw.get(i);
            if (ind.getPlacement() == null) {
                throw new IllegalArgumentException(
                        "Front-1 member " + i + " has null placement (rejected decode?)");
            }
            if (ind.getObjectives() == null) {
                throw new IllegalArgumentException(
                        "Front-1 member " + i + " has null objectives");
            }
        }

        // Deduplicate by unique placement
        List<NssaoOptimizer.Individual> deduplicated = deduplicateByPlacement(front1Raw);
        int duplicatesRemoved = front1Raw.size() - deduplicated.size();

        // Apply lexicographic selection on (f2, f3, f1, f4), then index
        NssaoOptimizer.Individual selected = deduplicated.stream()
                .min(Comparator
                        .comparingDouble((NssaoOptimizer.Individual ind) -> 
                                ind.getObjectives().getF2CoResidencyRisk())
                        .thenComparingDouble(ind -> 
                                ind.getObjectives().getF3Cost())
                        .thenComparingDouble(ind -> 
                                ind.getObjectives().getF1ThreatServerRisk())
                        .thenComparingDouble(ind -> 
                                ind.getObjectives().getF4UnusedCapacity())
                        .thenComparingInt(front1Raw::indexOf))
                .orElseThrow(() -> new IllegalStateException("Selection failed unexpectedly"));

        return new SelectionResult(selected, front1Raw, deduplicated, duplicatesRemoved);
    }

    /**
     * Deduplicate front-1 by unique placement (VM→host mapping).
     * Keeps the first representative of each unique placement.
     */
    private List<NssaoOptimizer.Individual> deduplicateByPlacement(
            List<NssaoOptimizer.Individual> front1) {
        Map<String, NssaoOptimizer.Individual> uniquePlacements = new LinkedHashMap<>();
        for (NssaoOptimizer.Individual ind : front1) {
            String placementKey = placementKey(ind.getPlacement());
            if (!uniquePlacements.containsKey(placementKey)) {
                uniquePlacements.put(placementKey, ind);
            }
        }
        return new ArrayList<>(uniquePlacements.values());
    }

    /**
     * Generate a unique string key for a placement.
     * Format: "vmId1:hostId1,vmId2:hostId2,..." (sorted by VM ID)
     */
    private String placementKey(CandidatePlacement placement) {
        return placement.viewMapping().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
