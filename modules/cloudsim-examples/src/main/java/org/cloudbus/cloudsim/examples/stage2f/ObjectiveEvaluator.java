package org.cloudbus.cloudsim.examples.stage2f;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stage 2F objective evaluator: computes the four approved objectives
 * (all minimized) for a {@link CandidatePlacement} WITHOUT mutating any
 * live CloudSim state.
 *
 * <h3>Approved objectives (all minimize)</h3>
 * <pre>
 * f1 = mean[ threat(vm) × serverRisk(candidateHost(vm)) ]
 *      serverRisk = clamp(baseHostRisk + utilizationRiskWeight × candidateOverallUtilization, 0, 1)
 *
 * f2 = sum of max(threatA, threatB) for cross-entity VM pairs
 *      sharing the same candidate host
 *
 * f3 = activeHosts × perHostCost + migrationCount × perMigrationCost
 *      migrationCount = number of VMs whose candidate host != current host
 *
 * f4 = 1 − ((avgCPUUtil + avgMemoryUtil) / 2)
 *      averages taken across ALL hosts, including empty hosts;
 *      each host utilization clamped to [0,1]
 * </pre>
 *
 * <h3>Purity contract</h3>
 * <ul>
 *   <li>This evaluator only READS from Host and Vm objects (capacities,
 *       guest lists, static descriptors). It never adds/removes guests,
 *       never calls allocation policies, and never touches CloudSim clocks.</li>
 *   <li>All utilization quantities are recomputed from the CANDIDATE
 *       mapping, not from live host state. Live host state is consulted
 *       only to determine each VM's CURRENT host for the migration count
 *       in f3.</li>
 *   <li>Evaluating the same candidate twice yields identical values
 *       (deterministic; A→B→A evaluation is stable).</li>
 * </ul>
 *
 * <h3>Invalid candidates are rejected</h3>
 * A candidate is rejected (no objectives computed) if ANY of:
 * <ul>
 *   <li>a VM has no assignment in the candidate (missing assignment)</li>
 *   <li>the VM list contains duplicate VM IDs (duplicate assignment)</li>
 *   <li>a VM is assigned to a host ID that does not exist (invalid host)</li>
 *   <li>the candidate CPU demand on any host exceeds that host's capacity</li>
 *   <li>the candidate RAM demand on any host exceeds that host's capacity</li>
 * </ul>
 *
 * <h3>Simulation-parameter disclaimer</h3>
 * perHostCost, perMigrationCost, baseHostRisk and utilizationRiskWeight are
 * configurable SIMULATION PARAMETERS, not measured cloud costs or risks.
 */
public final class ObjectiveEvaluator {

    private final ServerRiskModel serverRiskModel;
    private final double perHostCost;
    private final double perMigrationCost;

    /**
     * Create an evaluator with an explicit {@link ServerRiskModel} and cost
     * parameters.
     *
     * @param serverRiskModel  supplies baseHostRisk and utilizationRiskWeight
     *                         used by the f1 server-risk formula
     * @param perHostCost      cost per active host (simulation currency units)
     * @param perMigrationCost cost per migrated VM (simulation currency units)
     * @throws IllegalArgumentException if costs are negative or model is null
     */
    public ObjectiveEvaluator(ServerRiskModel serverRiskModel,
                              double perHostCost,
                              double perMigrationCost) {
        if (serverRiskModel == null) {
            throw new IllegalArgumentException("serverRiskModel cannot be null");
        }
        if (perHostCost < 0.0) {
            throw new IllegalArgumentException(
                    "perHostCost cannot be negative, got: " + perHostCost);
        }
        if (perMigrationCost < 0.0) {
            throw new IllegalArgumentException(
                    "perMigrationCost cannot be negative, got: " + perMigrationCost);
        }
        this.serverRiskModel = serverRiskModel;
        this.perHostCost = perHostCost;
        this.perMigrationCost = perMigrationCost;
    }

    /**
     * Create an evaluator with Stage 2C/2D default simulation parameters:
     * ServerRiskModel(0.1, 0.3), perHostCost = 1.0, perMigrationCost = 1.0.
     */
    public ObjectiveEvaluator() {
        this(new ServerRiskModel(), 1.0, 1.0);
    }

    /**
     * Evaluation outcome: either raw objective values (valid candidate) or a
     * list of violations (invalid candidate). Never both.
     */
    public static final class EvaluationResult {
        private final ObjectiveValues objectives;
        private final List<String> violations;
        private final int activeHostCount;
        private final int migrationCount;

        private EvaluationResult(ObjectiveValues objectives,
                                 List<String> violations,
                                 int activeHostCount,
                                 int migrationCount) {
            this.objectives = objectives;
            this.violations = violations;
            this.activeHostCount = activeHostCount;
            this.migrationCount = migrationCount;
        }

        /** True if the candidate passed validation and objectives were computed. */
        public boolean isValid() {
            return violations.isEmpty();
        }

        /** Raw objective values; only meaningful when {@link #isValid()}. */
        public ObjectiveValues getObjectives() {
            return objectives;
        }

        /** Rejection reasons; empty when valid. */
        public List<String> getViolations() {
            return Collections.unmodifiableList(violations);
        }

        /** Active host count used for f3 (0 when invalid). */
        public int getActiveHostCount() {
            return activeHostCount;
        }

        /** Migration count used for f3 (0 when invalid). */
        public int getMigrationCount() {
            return migrationCount;
        }
    }

    /**
     * Evaluate a candidate placement.
     *
     * @param candidate      the proposed VM-to-host mapping (not applied)
     * @param vms            all VMs that must be placed
     * @param vmThreatStateMap VM ID -> threat state (Stage 2C); supplies
     *                         threat scores and entity identity
     * @return evaluation result with raw objectives or violations
     * @throws IllegalArgumentException if any argument is null or a VM is
     *                                  missing threat metadata
     */
    public EvaluationResult evaluate(CandidatePlacement candidate,
                                     List<Vm> vms,
                                     Map<Integer, VmThreatState> vmThreatStateMap) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate cannot be null");
        }
        if (vms == null) {
            throw new IllegalArgumentException("vms cannot be null");
        }
        if (vmThreatStateMap == null) {
            throw new IllegalArgumentException("vmThreatStateMap cannot be null");
        }

        List<String> violations = validate(candidate, vms);
        if (!violations.isEmpty()) {
            return new EvaluationResult(null, violations, 0, 0);
        }

        // === Group candidate VMs by host (deterministic order) ===
        Map<Integer, List<Vm>> vmsByHost = new LinkedHashMap<>();
        for (int hostId : candidate.getAllHostIds()) {
            vmsByHost.put(hostId, new ArrayList<>());
        }
        for (Vm vm : vms) {
            int hostId = candidate.getHostIdForVm(vm.getId());
            vmsByHost.get(hostId).add(vm);
        }

        // === Per-host candidate utilization + f1 accumulation ===
        double baseHostRisk = serverRiskModel.getBaseHostRisk();
        double utilizationRiskWeight = serverRiskModel.getUtilizationRiskWeight();

        double f1Sum = 0.0;
        double cpuUtilSum = 0.0;
        double memUtilSum = 0.0;
        double f2 = 0.0;
        int activeHosts = 0;

        for (Map.Entry<Integer, List<Vm>> entry : vmsByHost.entrySet()) {
            Host host = candidate.getHost(entry.getKey());
            List<Vm> placed = entry.getValue();

            double hostTotalMips = host.getTotalMips();
            double hostRam = host.getRam();

            double allocatedMips = 0.0;
            int allocatedRam = 0;
            for (Vm vm : placed) {
                allocatedMips += vm.getTotalMips();
                allocatedRam += vm.getRam();
            }

            double cpuUtil = clampUnit(hostTotalMips > 0.0
                    ? allocatedMips / hostTotalMips : 0.0);
            double memUtil = clampUnit(hostRam > 0.0
                    ? (double) allocatedRam / (double) hostRam : 0.0);
            double overallUtil = (cpuUtil + memUtil) / 2.0;

            cpuUtilSum += cpuUtil;
            memUtilSum += memUtil;

            if (!placed.isEmpty()) {
                activeHosts++;
            }

            double serverRisk = clampUnit(
                    baseHostRisk + utilizationRiskWeight * overallUtil);

            for (Vm vm : placed) {
                VmThreatState state = requireThreatState(vmThreatStateMap, vm.getId());
                f1Sum += state.getThreatScore() * serverRisk;
            }

            // === f2: cross-entity pairs on this candidate host ===
            for (int i = 0; i < placed.size(); i++) {
                for (int j = i + 1; j < placed.size(); j++) {
                    VmThreatState a = requireThreatState(vmThreatStateMap, placed.get(i).getId());
                    VmThreatState b = requireThreatState(vmThreatStateMap, placed.get(j).getId());
                    if (a.isDifferentEntityFrom(b)) {
                        f2 += Math.max(a.getThreatScore(), b.getThreatScore());
                    }
                }
            }
        }

        double f1 = vms.isEmpty() ? 0.0 : f1Sum / vms.size();

        double avgCpuUtil = cpuUtilSum / candidate.getHostCount();
        double avgMemUtil = memUtilSum / candidate.getHostCount();
        double f4 = 1.0 - ((avgCpuUtil + avgMemUtil) / 2.0);

        // === f3: active host cost + migration cost ===
        int migrationCount = countMigrations(candidate, vms);
        double f3 = activeHosts * perHostCost + migrationCount * perMigrationCost;

        ObjectiveValues values = new ObjectiveValues(f1, f2, f3, f4);
        return new EvaluationResult(values, Collections.emptyList(),
                activeHosts, migrationCount);
    }

    // === Validation ===

    /**
     * Validate a candidate against the Stage 2F rejection rules.
     *
     * @return list of violation descriptions; empty when the candidate is valid
     */
    public List<String> validate(CandidatePlacement candidate, List<Vm> vms) {
        List<String> violations = new ArrayList<>();

        // Duplicate VM assignment: duplicate VM IDs in the placement list
        Set<Integer> seenVmIds = new LinkedHashSet<>();
        for (Vm vm : vms) {
            if (vm == null) {
                violations.add("VM list contains a null VM");
                continue;
            }
            if (!seenVmIds.add(vm.getId())) {
                violations.add("Duplicate VM assignment: VM " + vm.getId()
                        + " appears more than once");
            }
        }

        for (Vm vm : vms) {
            if (vm == null) {
                continue;
            }
            int vmId = vm.getId();

            // Missing assignment
            if (!candidate.hasAssignment(vmId)) {
                violations.add("Missing assignment: VM " + vmId
                        + " has no candidate host");
                continue;
            }

            // Invalid host
            Integer hostId = candidate.getHostIdForVm(vmId);
            if (candidate.getHost(hostId) == null) {
                violations.add("Invalid host: VM " + vmId
                        + " assigned to non-existent host " + hostId);
            }
        }

        // Capacity checks per candidate host
        Map<Integer, double[]> demandByHost = new TreeMap<>();
        for (Vm vm : vms) {
            if (vm == null || !candidate.hasAssignment(vm.getId())) {
                continue; // already reported
            }
            Integer hostId = candidate.getHostIdForVm(vm.getId());
            if (candidate.getHost(hostId) == null) {
                continue; // already reported
            }
            double[] demand = demandByHost.computeIfAbsent(hostId,
                    k -> new double[2]);
            demand[0] += vm.getTotalMips();
            demand[1] += vm.getRam();
        }
        for (Map.Entry<Integer, double[]> entry : demandByHost.entrySet()) {
            Host host = candidate.getHost(entry.getKey());
            double cpuDemand = entry.getValue()[0];
            double ramDemand = entry.getValue()[1];
            if (cpuDemand > host.getTotalMips()) {
                violations.add("CPU capacity exceeded on host " + entry.getKey()
                        + ": demand " + cpuDemand + " > capacity "
                        + host.getTotalMips());
            }
            if (ramDemand > host.getRam()) {
                violations.add("RAM capacity exceeded on host " + entry.getKey()
                        + ": demand " + ramDemand + " > capacity "
                        + host.getRam());
            }
        }

        return violations;
    }

    // === Helpers ===

    /**
     * Count VMs whose candidate host differs from their current live host.
     *
     * <p>The CURRENT host is determined by reading host guest lists (read-only)
     * and falling back to {@link Vm#getHost()}. A VM that is not currently
     * placed anywhere counts as migrating to its candidate host.
     */
    private static int countMigrations(CandidatePlacement candidate, List<Vm> vms) {
        int count = 0;
        for (Vm vm : vms) {
            int candidateHostId = candidate.getHostIdForVm(vm.getId());
            int currentHostId = findCurrentHostId(candidate, vm);
            if (currentHostId < 0 || currentHostId != candidateHostId) {
                count++;
            }
        }
        return count;
    }

    /**
     * Find the host ID that currently contains this VM in live state,
     * or -1 if the VM is not placed.
     */
    private static int findCurrentHostId(CandidatePlacement candidate, Vm vm) {
        for (int hostId : candidate.getAllHostIds()) {
            Host host = candidate.getHost(hostId);
            for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
                if (guest instanceof Vm placedVm && placedVm.getId() == vm.getId()) {
                    return hostId;
                }
            }
        }
        // Fallback to the VM's live host reference
        if (vm.getHost() instanceof Host host) {
            return host.getId();
        }
        return -1;
    }

    private static VmThreatState requireThreatState(
            Map<Integer, VmThreatState> vmThreatStateMap, int vmId) {
        VmThreatState state = vmThreatStateMap.get(vmId);
        if (state == null) {
            throw new IllegalArgumentException(
                    "No VmThreatState for VM " + vmId
                            + " — threat metadata is required for evaluation");
        }
        return state;
    }

    private static double clampUnit(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    public double getPerHostCost() {
        return perHostCost;
    }

    public double getPerMigrationCost() {
        return perMigrationCost;
    }

    public ServerRiskModel getServerRiskModel() {
        return serverRiskModel;
    }
}
