package org.cloudbus.cloudsim.examples.stage2i;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stage 2I.2 — PROJECT-DEFINED BASELINES (not from the paper).
 *
 * <p>B1, B2, and B3 are standard heuristics defined by this project
 * (Stage 2I.1 design §3) purely as comparison points for the proposed
 * method. They are NOT claimed to be the paper's baselines. They operate
 * entirely on hypothetical {@link CandidatePlacement}s: no method here ever
 * mutates live CloudSim state (no guest-list changes, no migration-service
 * calls inside search). Physical application of a final placement is the
 * runner's job, identical for every method.
 *
 * <p>All baselines START from the SAME deterministic FFD initial placement
 * as the proposed method (fair-comparison rule, Stage 2I.1 §5):
 * <ul>
 *   <li><b>B1 — FFD static:</b> returns the initial placement unchanged;
 *       zero relocations by definition.</li>
 *   <li><b>B2 — Best-Fit + f4-oriented local search:</b> repeatedly applies
 *       the single VM relocation that decreases f4 the most, subject to
 *       Stage 2F feasibility, until no improving move exists or a
 *       documented move-attempt cap (2 x VM count) is reached. Ties on f4
 *       improvement break by (vmId asc, targetHostId asc). f4 is evaluated
 *       with the SAME Stage 2F evaluator used by NSSAO (no metric drift).</li>
 *   <li><b>B3 — Random feasible relocation:</b> seeded RNG; for a fixed
 *       2 x VM count attempts, propose (vm, target host) uniformly; accept
 *       the move only if feasible; moves are applied to the hypothetical
 *       placement immediately.</li>
 * </ul>
 *
 * <p>MIGRATION ACCOUNTING: migration count for every method (including the
 * proposed one) is |final placement - initial FFD placement|, computed
 * deterministically by the runner. B1 therefore always reports 0.
 */
public final class Stage2iBaselines {

    /** Move-attempt cap factor for B2/B3 (Stage 2I.1 §3.3/§3.4). */
    public static final int MOVE_ATTEMPT_FACTOR = 2;

    private Stage2iBaselines() {
    }

    /**
     * B1 — FFD static: the initial placement, unchanged.
     */
    public static BaselineResult runB1FfdStatic(
            CandidatePlacement initial, List<Vm> vms,
            Stage2iEngine engine) {
        // No search at all: zero relocations by definition.
        return new BaselineResult(initial, 0, 0,
                "B1: no search; placement = initial FFD");
    }

    /**
     * B2 — Best-Fit + f4-oriented local search (hypothetical only).
     *
     * <p>Note on the "best-fit" step: because ALL methods share the same FFD
     * initial placement (fair-comparison rule), B2's placement phase is the
     * shared FFD itself; the differentiating phase is the f4-oriented local
     * search, which greedily tightens packing beyond FFD. This keeps the
     * initial state identical across methods so migration accounting is
     * comparable.
     */
    public static BaselineResult runB2BestFitF4LocalSearch(
            CandidatePlacement initial, List<Vm> vms,
            Stage2iEngine engine) {

        List<Host> hosts = engine.hosts();
        Map<Integer, Integer> mapping =
                new LinkedHashMap<>(initial.viewMapping());
        int attemptCap = MOVE_ATTEMPT_FACTOR * vms.size();
        int attempts = 0;
        int appliedMoves = 0;

        while (attempts < attemptCap) {
            Move best = bestF4ImprovingMove(mapping, vms, hosts, engine);
            if (best == null) {
                break; // local optimum: no improving feasible move
            }
            mapping.put(best.vmId, best.targetHostId);
            appliedMoves++;
            attempts++;
        }

        CandidatePlacement result = new CandidatePlacement(mapping, hosts);
        return new BaselineResult(result, appliedMoves, attempts,
                "B2: greedy f4 local search; attempts=" + attempts
                        + ", moves=" + appliedMoves);
    }

    /**
     * B3 — Random feasible relocation with an explicit seed (hypothetical).
     */
    public static BaselineResult runB3RandomRelocation(
            CandidatePlacement initial, List<Vm> vms,
            Stage2iEngine engine, long seed) {

        List<Host> hosts = engine.hosts();
        Map<Integer, Integer> mapping =
                new LinkedHashMap<>(initial.viewMapping());
        Random random = new Random(seed);

        int attemptCap = MOVE_ATTEMPT_FACTOR * vms.size();
        int attempts = 0;
        int appliedMoves = 0;

        for (int i = 0; i < attemptCap; i++) {
            attempts++;
            // Uniform proposal: VM by index, target host by index
            Vm vm = vms.get(random.nextInt(vms.size()));
            Host target = hosts.get(random.nextInt(hosts.size()));
            int currentHostId = mapping.get(vm.getId());
            if (target.getId() == currentHostId) {
                continue; // no-op proposal
            }
            Map<Integer, Integer> trial = new LinkedHashMap<>(mapping);
            trial.put(vm.getId(), target.getId());
            if (engine.validate(trial).isEmpty()) {
                mapping = trial;
                appliedMoves++;
            }
        }

        CandidatePlacement result = new CandidatePlacement(mapping, hosts);
        return new BaselineResult(result, appliedMoves, attempts,
                "B3: seeded random relocation; seed=" + seed
                        + ", attempts=" + attempts
                        + ", moves=" + appliedMoves);
    }

    // === f4-greedy move selection (B2) ===

    /**
     * Find the feasible single-VM relocation with the largest f4 decrease.
     * Deterministic iteration order (vmId asc, then targetHostId asc) with
     * strict-improvement comparison makes the selected move independent of
     * map iteration order. Returns null when no feasible move improves f4.
     */
    private static Move bestF4ImprovingMove(
            Map<Integer, Integer> mapping, List<Vm> vms, List<Host> hosts,
            Stage2iEngine engine) {

        double currentF4 = engine.evaluate(mapping)
                .getObjectives().getF4UnusedCapacity();
        Move best = null;
        double bestF4 = currentF4;

        // Deterministic iteration: VM ID asc, target host ID asc
        List<Vm> sortedVms = new ArrayList<>(vms);
        sortedVms.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        List<Integer> sortedHostIds = new ArrayList<>();
        for (Host h : hosts) {
            sortedHostIds.add(h.getId());
        }
        Collections.sort(sortedHostIds);

        for (Vm vm : sortedVms) {
            int currentHostId = mapping.get(vm.getId());
            for (int targetHostId : sortedHostIds) {
                if (targetHostId == currentHostId) {
                    continue;
                }
                Map<Integer, Integer> trial = new LinkedHashMap<>(mapping);
                trial.put(vm.getId(), targetHostId);
                CandidatePlacement trialPlacement =
                        new CandidatePlacement(trial, hosts);
                if (!engine.validate(trialPlacement).isEmpty()) {
                    continue; // infeasible: skip
                }
                double f4 = engine.evaluate(trialPlacement).getObjectives()
                        .getF4UnusedCapacity();
                if (f4 < bestF4 - 1e-12) {
                    bestF4 = f4;
                    best = new Move(vm.getId(), targetHostId);
                }
            }
        }
        return best;
    }

    /** A single relocation move. */
    private static final class Move {
        final int vmId;
        final int targetHostId;

        Move(int vmId, int targetHostId) {
            this.vmId = vmId;
            this.targetHostId = targetHostId;
        }
    }

    /** Outcome of a baseline run (hypothetical placement only). */
    public static final class BaselineResult {
        private final CandidatePlacement finalPlacement;
        private final int relocations;
        private final int searchAttempts;
        private final String notes;

        BaselineResult(CandidatePlacement finalPlacement, int relocations,
                       int searchAttempts, String notes) {
            this.finalPlacement = finalPlacement;
            this.relocations = relocations;
            this.searchAttempts = searchAttempts;
            this.notes = notes;
        }

        public CandidatePlacement getFinalPlacement() {
            return finalPlacement;
        }

        public int getRelocations() {
            return relocations;
        }

        public int getSearchAttempts() {
            return searchAttempts;
        }

        public String getNotes() {
            return notes;
        }
    }
}
