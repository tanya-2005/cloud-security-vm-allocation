package org.cloudbus.cloudsim.examples.stage2g;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Converts real-valued NSSAO positions into hypothetical
 * {@link CandidatePlacement}s, with deterministic repair-first feasibility
 * handling (Stage 2G.1 design §5/§6).
 *
 * <h3>Continuous → discrete mapping (approved rule, Stage 2G.1 §5 Option A)</h3>
 * <pre>
 * hostIndex = clamp( round(x_k), 0, H−1 )
 * </pre>
 * where round is {@link Math#rint} (banker's rounding: deterministic,
 * no positive bias) and clamp enforces the bounds. Multiple VMs MAY map to
 * the same host — that is legitimate co-residency.
 *
 * <h3>Structural guarantees</h3>
 * The decoder emits exactly one host per movable VM (positions come from a
 * per-VM vector) and starts from the current placement for pinned VMs, so
 * missing assignments, duplicate assignments, and out-of-range hosts are
 * impossible by construction. Any residual infeasibility can only be
 * CPU/RAM capacity, handled by repair below.
 *
 * <h3>Repair-first feasibility (approved strategy, Stage 2G.1 §6)</h3>
 * After decode, hosts whose candidate CPU or RAM demand exceeds capacity are
 * repaired deterministically: VMs are moved (in the fixed migration-list
 * order) to the feasible host minimizing the resulting CPU utilization; ties
 * broken by lower host id. Repairs preserve as much of the proposed
 * assignment as possible — only overloaded hosts are touched. If no feasible
 * assignment exists for some VM, the candidate is REJECTED (reported as
 * infeasible), never silently accepted, and no objective penalties are used.
 *
 * <p>This class never mutates live CloudSim state: it only reads capacities
 * and constructs new {@link CandidatePlacement} objects.
 */
public final class PlacementDecoder {

    /**
     * Decode outcome: either a (possibly repaired) candidate or a rejection
     * with the blocking VM.
     */
    public static final class DecodeResult {
        private final CandidatePlacement candidate;
        private final int blockedVmId;
        private final boolean repaired;

        private DecodeResult(CandidatePlacement candidate, int blockedVmId, boolean repaired) {
            this.candidate = candidate;
            this.blockedVmId = blockedVmId;
            this.repaired = repaired;
        }

        static DecodeResult ok(CandidatePlacement candidate, boolean repaired) {
            return new DecodeResult(candidate, -1, repaired);
        }

        static DecodeResult rejected(int blockedVmId) {
            return new DecodeResult(null, blockedVmId, false);
        }

        /** True if the decode (with repair) produced a candidate. */
        public boolean isSuccess() {
            return candidate != null;
        }

        /** The decoded candidate; only meaningful when {@link #isSuccess()}. */
        public CandidatePlacement getCandidate() {
            return candidate;
        }

        /** True if repair had to change at least one assignment. */
        public boolean wasRepaired() {
            return repaired;
        }

        /** VM that could not be placed feasibly (when rejected). */
        public int getBlockedVmId() {
            return blockedVmId;
        }
    }

    /**
     * Decode a real-valued position vector into a hypothetical candidate.
     *
     * @param positions      one continuous position per movable VM, each in
     *                       [0, H−1] (clamped here defensively)
     * @param movableVmIds   the VMs owned by the optimizer, in the fixed
     *                       deterministic migration-list order
     * @param currentMapping the current (live) VM→host mapping; provides the
     *                       assignments for all non-movable (pinned) VMs
     * @param hosts          all hosts in the datacenter (Stage 2F contract)
     * @param allVms         all VMs (movable + pinned) that the candidate
     *                       must assign — passed through to
     *                       {@link CandidatePlacement}
     * @return decode result: valid candidate (possibly repaired) or rejection
     */
    public DecodeResult decode(double[] positions,
                               List<Integer> movableVmIds,
                               Map<Integer, Integer> currentMapping,
                               List<Host> hosts,
                               List<Vm> allVms) {
        if (positions == null) {
            throw new IllegalArgumentException("positions cannot be null");
        }
        if (movableVmIds == null || movableVmIds.isEmpty()) {
            throw new IllegalArgumentException("movableVmIds cannot be null or empty");
        }
        if (positions.length != movableVmIds.size()) {
            throw new IllegalArgumentException(
                    "positions length " + positions.length
                            + " != movable VM count " + movableVmIds.size());
        }
        if (currentMapping == null || hosts == null || allVms == null) {
            throw new IllegalArgumentException(
                    "currentMapping, hosts and allVms cannot be null");
        }
        int hostCount = hosts.size();
        if (hostCount == 0) {
            throw new IllegalArgumentException("hosts cannot be empty");
        }

        boolean repaired = false;
        Map<Integer, Integer> vmToHost = new LinkedHashMap<>();
        // Pinned VMs first: preserve current assignment untouched.
        for (Map.Entry<Integer, Integer> e : currentMapping.entrySet()) {
            vmToHost.put(e.getKey(), e.getValue());
        }

        // Movable VMs: approved discretization rule.
        for (int k = 0; k < movableVmIds.size(); k++) {
            int vmId = movableVmIds.get(k);
            int hostIndex = clampToHostRange(Math.rint(positions[k]), hostCount);
            vmToHost.put(vmId, hostIndex);
        }

        CandidatePlacement candidate = new CandidatePlacement(vmToHost, hosts);

        // === Repair pass (deterministic) ===
        candidate = repair(candidate, movableVmIds, hosts, allVms);
        if (candidate == null) {
            // Could not repair: reject rather than silently accept.
            // The blocked VM is the first movable VM that has no feasible host.
            int blocked = firstUnplaceableVm(movableVmIds, hosts, allVms, vmToHost);
            return DecodeResult.rejected(blocked);
        }
        // Detect whether repair changed anything.
        repaired = !candidate.viewMapping().equals(vmToHost);
        return DecodeResult.ok(candidate, repaired);
    }

    /**
     * Deterministic repair: for every host whose candidate CPU or RAM demand
     * exceeds capacity, move VMs (smallest CPU demand first, ties by lower
     * VM id) to the feasible host minimizing resulting CPU utilization; ties
     * broken by lower host id. Only overloaded hosts are touched.
     *
     * @return repaired candidate, or null if some VM cannot be placed
     *         feasibly anywhere
     */
    private CandidatePlacement repair(CandidatePlacement candidate,
                                      List<Integer> movableVmIds,
                                      List<Host> hosts,
                                      List<Vm> allVms) {
        Map<Integer, Integer> mapping = new LinkedHashMap<>(candidate.viewMapping());

        // Demand aggregation per host (candidate state, recomputed locally).
        TreeMap<Integer, double[]> demand = new TreeMap<>();
        TreeMap<Integer, double[]> capacity = new TreeMap<>();
        for (Host host : hosts) {
            capacity.put(host.getId(), new double[]{host.getTotalMips(), host.getRam()});
            demand.put(host.getId(), new double[]{0.0, 0.0});
        }
        for (Map.Entry<Integer, Integer> e : mapping.entrySet()) {
            Vm vm = findVm(allVms, e.getKey());
            if (vm == null) {
                continue;
            }
            double[] d = demand.get(e.getValue());
            d[0] += vm.getTotalMips();
            d[1] += vm.getRam();
        }

        boolean changed = false;
        // Iterate hosts in ascending id order (deterministic).
        for (Map.Entry<Integer, double[]> hostEntry : capacity.entrySet()) {
            int hostId = hostEntry.getKey();
            double[] cap = hostEntry.getValue();
            double[] dem = demand.get(hostId);

            // Overloaded? Move VMs out (smallest CPU demand first, ties by
            // lower vm id) until under capacity.
            while (dem[0] > cap[0] + 1e-9 || dem[1] > cap[1] + 1e-9) {
                int victim = -1;
                double victimMips = Double.MAX_VALUE;
                for (Map.Entry<Integer, Integer> e : mapping.entrySet()) {
                    if (e.getValue() != hostId) {
                        continue;
                    }
                    Vm vm = findVm(allVms, e.getKey());
                    if (vm == null) {
                        continue;
                    }
                    // Movable VMs only are relocated by repair; pinned VMs
                    // keep their current host (their overload contribution
                    // can only be relieved by moving others).
                    if (!movableVmIds.contains(e.getKey())) {
                        continue;
                    }
                    if (vm.getTotalMips() < victimMips
                            || (vm.getTotalMips() == victimMips
                                    && (victim < 0 || vm.getId() < victim))) {
                        victim = vm.getId();
                        victimMips = vm.getTotalMips();
                    }
                }
                if (victim < 0) {
                    // No movable VM left to relieve this host: cannot repair
                    // this configuration here; try whole-candidate rejection.
                    return null;
                }
                int target = bestFeasibleHost(victim, hostId, demand, capacity,
                        movableVmIds, mapping, allVms);
                if (target < 0) {
                    return null;
                }
                Vm vm = findVm(allVms, victim);
                mapping.put(victim, target);
                demand.get(hostId)[0] -= vm.getTotalMips();
                demand.get(hostId)[1] -= vm.getRam();
                demand.get(target)[0] += vm.getTotalMips();
                demand.get(target)[1] += vm.getRam();
                changed = true;
            }
        }

        if (!changed) {
            return candidate;
        }
        return new CandidatePlacement(mapping, candidateHosts(hosts));
    }

    /**
     * Pick the feasible host (excluding {@code excludeHostId}) minimizing
     * resulting CPU utilization; ties broken by lower host id.
     */
    private int bestFeasibleHost(int vmId,
                                 int excludeHostId,
                                 TreeMap<Integer, double[]> demand,
                                 TreeMap<Integer, double[]> capacity,
                                 List<Integer> movableVmIds,
                                 Map<Integer, Integer> mapping,
                                 List<Vm> allVms) {
        Vm vm = findVm(allVms, vmId);
        if (vm == null) {
            return -1;
        }
        int best = -1;
        double bestUtil = Double.MAX_VALUE;
        for (Map.Entry<Integer, double[]> e : capacity.entrySet()) {
            int hostId = e.getKey();
            if (hostId == excludeHostId) {
                continue;
            }
            double[] cap = e.getValue();
            double[] dem = demand.get(hostId);
            double newCpu = dem[0] + vm.getTotalMips();
            double newRam = dem[1] + vm.getRam();
            if (newCpu > cap[0] + 1e-9 || newRam > cap[1] + 1e-9) {
                continue;
            }
            double util = newCpu / cap[0];
            if (util < bestUtil - 1e-12) {
                bestUtil = util;
                best = hostId;
            }
            // exact ties keep the lower host id (ascending iteration order)
        }
        return best;
    }

    /**
     * First movable VM that has no feasible host anywhere (used only to
     * report a rejection reason).
     */
    private int firstUnplaceableVm(List<Integer> movableVmIds,
                                   List<Host> hosts,
                                   List<Vm> allVms,
                                   Map<Integer, Integer> vmToHost) {
        for (int vmId : movableVmIds) {
            Vm vm = findVm(allVms, vmId);
            if (vm == null) {
                return vmId;
            }
            boolean fitsSomewhere = false;
            for (Host host : hosts) {
                if (vm.getTotalMips() <= host.getTotalMips()
                        && vm.getRam() <= host.getRam()) {
                    fitsSomewhere = true;
                    break;
                }
            }
            if (!fitsSomewhere) {
                return vmId;
            }
        }
        return movableVmIds.get(0);
    }

    private List<Host> candidateHosts(List<Host> hosts) {
        return hosts;
    }

    private static Vm findVm(List<Vm> allVms, int vmId) {
        for (Vm vm : allVms) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        return null;
    }

    /**
     * The approved clamp: hostIndex = clamp(round(x), 0, H−1).
     * {@code rounded} is already {@link Math#rint}-applied.
     */
    static int clampToHostRange(double rounded, int hostCount) {
        int idx = (int) rounded;
        if (idx < 0) {
            return 0;
        }
        if (idx > hostCount - 1) {
            return hostCount - 1;
        }
        return idx;
    }
}
