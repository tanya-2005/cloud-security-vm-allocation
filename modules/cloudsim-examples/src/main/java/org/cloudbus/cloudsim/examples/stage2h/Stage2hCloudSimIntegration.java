package org.cloudbus.cloudsim.examples.stage2h;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
import org.cloudbus.cloudsim.examples.stage2d.CoResidencyDurationTracker;
import org.cloudbus.cloudsim.examples.stage2d.MigrationEvent;
import org.cloudbus.cloudsim.examples.stage2d.VmMigrationService;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveEvaluator;
import org.cloudbus.cloudsim.examples.stage2g.NssaoConfig;
import org.cloudbus.cloudsim.examples.stage2g.NssaoOptimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2H — CloudSim Integration Orchestrator.
 *
 * <p>Connects all components from Stages 2B–2G into a complete end-to-end
 * CloudSim simulation workflow:
 * <pre>
 * threat scores (CSV)
 *   → CloudSim VMs with threat metadata
 *   → deterministic initial placement
 *   → NSSAO optimization (hypothetical, read-only)
 *   → final Pareto front (multiple solutions)
 *   → deterministic single-solution selection
 *   → actual CloudSim VM migration
 *   → post-hoc co-residency duration measurement
 *   → final metrics report
 * </pre>
 *
 * <p>CRITICAL INVARIANT: NSSAO evaluation remains hypothetical and read-only.
 * Actual VM migration happens ONLY ONCE after NSSAO finishes and one final
 * candidate is selected.
 *
 * <p>Per Stage 2H design:
 * <ul>
 *   <li>Initial placement: First-Fit Decreasing by CPU demand (deterministic)</li>
 *   <li>Pareto selection: Lexicographic on (f2, f3, f1, f4) + index tie-break (resolves I3)</li>
 *   <li>Metrics: 14 categories including NSSAO stats and repair rate</li>
 *   <li>Validation: 14 tests verifying end-to-end correctness</li>
 * </ul>
 */
public final class Stage2hCloudSimIntegration {

    private final List<Host> hosts;
    private final List<Vm> allVms;
    private final Map<Integer, Integer> currentMapping;
    private final Map<Integer, VmThreatState> threatMap;
    private final ObjectiveEvaluator evaluator;
    private final ServerRiskModel riskModel;
    private final NssaoConfig nssaoConfig;
    private final double perHostCost;
    private final double perMigrationCost;
    private final VmMigrationService migrationService;
    private final CoResidencyDurationTracker durationTracker;
    private final double highThreatThreshold;

    private NssaoOptimizer.RunResult nssaoResult;
    private ParetoSolutionSelector.SelectionResult selectionResult;
    private SimulationMetrics metrics;
    private Map<Integer, Integer> initialMapping;

    /**
     * Create an orchestrator for a specific scenario.
     *
     * @param hosts              all CloudSim hosts (immutable descriptors)
     * @param allVms             all VMs (immutable descriptors)
     * @param threatMap          VM ID → threat state (Stage 2C)
     * @param riskModel          server risk model (Stage 2C)
     * @param perHostCost        cost coefficient for active hosts
     * @param perMigrationCost   cost coefficient per migration
     * @param nssaoConfig        NSSAO parameters
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public Stage2hCloudSimIntegration(
            List<Host> hosts,
            List<Vm> allVms,
            Map<Integer, VmThreatState> threatMap,
            ServerRiskModel riskModel,
            double perHostCost,
            double perMigrationCost,
            NssaoConfig nssaoConfig) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("hosts cannot be null or empty");
        }
        if (allVms == null || allVms.isEmpty()) {
            throw new IllegalArgumentException("allVms cannot be null or empty");
        }
        if (threatMap == null) {
            throw new IllegalArgumentException("threatMap cannot be null");
        }
        if (riskModel == null) {
            throw new IllegalArgumentException("riskModel cannot be null");
        }
        if (perHostCost < 0 || perMigrationCost < 0) {
            throw new IllegalArgumentException("cost coefficients must be non-negative");
        }
        if (nssaoConfig == null) {
            throw new IllegalArgumentException("nssaoConfig cannot be null");
        }

        this.hosts = hosts;
        this.allVms = allVms;
        this.threatMap = threatMap;
        this.riskModel = riskModel;
        this.perHostCost = perHostCost;
        this.perMigrationCost = perMigrationCost;
        this.nssaoConfig = nssaoConfig;

        // Capture current live placement
        this.currentMapping = captureCurrentPlacement();

        // Initialize components
        this.evaluator = new ObjectiveEvaluator(riskModel, perHostCost, perMigrationCost);
        this.migrationService = new VmMigrationService(threatMap);
        this.durationTracker = new CoResidencyDurationTracker(
                threatMap, nssaoConfig.getHighThreatThreshold());
        this.highThreatThreshold = nssaoConfig.getHighThreatThreshold();

        // NOTE: no co-residency snapshot is taken here. At construction time
        // the VMs are on no host at all, so a snapshot now would be empty.
        // The first snapshot is recorded in run() immediately after the
        // initial placement is applied to CloudSim (the initial-placement
        // event), and the second after the actual migration event.
    }

    /**
     * Execute the complete end-to-end integration workflow.
     *
     * @return final metrics
     * @throws Exception if any stage fails
     */
    public SimulationMetrics run() throws Exception {
        Log.println("\n========================================");
        Log.println("STAGE 2H END-TO-END INTEGRATION");
        Log.println("========================================");

        // === Stage 1: Initial placement ===
        Log.println("\n[Stage 1] Deterministic initial placement...");
        InitialPlacementStrategy initialPlacement = new InitialPlacementStrategy(hosts, allVms);
        Map<Integer, Integer> initialMap = initialPlacement.computePlacement();
        Log.println("  Initial placement computed: " + initialMap.size() + " VMs");
        verifyPlacementFeasibility(initialMap, "initial");

        // Apply initial placement to live CloudSim state. VMs start on no
        // host at all, so this is a direct placement, NOT a migration: it
        // produces no MigrationEvent and does NOT count toward the migration
        // count or migration cost.
        applyInitialPlacement(initialMap);
        currentMapping.clear();
        currentMapping.putAll(initialMap);
        initialMapping = new LinkedHashMap<>(initialMap);
        Log.println("  Initial placement applied to CloudSim");

        // Record the co-residency snapshot at the initial-placement event
        // (simulation time 0)
        durationTracker.recordSnapshot(hosts, 0.0);
        Log.println("  Co-residency snapshot recorded at t=0");

        // === Stage 2: NSSAO optimization ===
        Log.println("\n[Stage 2] NSSAO optimization...");
        List<Integer> movableVmIds = extractMovableVmIds();
        NssaoOptimizer optimizer = new NssaoOptimizer(
                hosts, allVms, movableVmIds, currentMapping, threatMap, evaluator, nssaoConfig);
        nssaoResult = optimizer.run();
        Log.println(String.format("  NSSAO completed: %d iterations, "
                        + "front-1 size=%d, rejected=%d, stall=%s",
                nssaoResult.getIterationsExecuted(),
                nssaoResult.getFirstFront().size(),
                nssaoResult.getRejectedDecodeCount(),
                nssaoResult.isStoppedByStall()));

        // Verify CloudSim purity
        verifyCloudSimPurity("after NSSAO");

        // === Stage 3: Pareto front analysis and solution selection ===
        Log.println("\n[Stage 3] Pareto front analysis and solution selection...");
        ParetoSolutionSelector selector = new ParetoSolutionSelector();
        selectionResult = selector.select(nssaoResult);
        Log.println(String.format("  Front-1: %d members, %d unique placements, "
                        + "%d duplicates removed",
                selectionResult.getFront1RawSize(),
                selectionResult.getUniquePlacements(),
                selectionResult.getDuplicatesRemoved()));

        CandidatePlacement selectedCandidate = selectionResult.getSelectedPlacement();
        verifyPlacementFeasibility(selectedCandidate.viewMapping(), "selected");
        Log.println("  Selected candidate feasible: " + selectedCandidate.viewMapping().size() + " VMs");

        // === Stage 4: Apply selected candidate ===
        Log.println("\n[Stage 4] Applying selected candidate to CloudSim...");
        double migrationTime = 1.0; // Simulation time of the migration event
        List<MigrationEvent> migrationEvents =
                applySelectedCandidate(selectedCandidate, migrationTime);
        Log.println(String.format("  Migrations executed: %d", migrationEvents.size()));

        // Record co-residency snapshot after migration
        durationTracker.recordSnapshot(hosts, migrationTime);
        Log.println("  Co-residency duration tracking updated");

        // === Stage 5: Metrics collection ===
        Log.println("\n[Stage 5] Collecting final metrics...");
        metrics = collectMetrics(migrationEvents);
        Log.println("  Final metrics collected");

        Log.println("\n" + metrics.formatReport());

        return metrics;
    }

    /**
     * Capture current live VM→host placement from CloudSim guest lists.
     */
    private Map<Integer, Integer> captureCurrentPlacement() {
        Map<Integer, Integer> placement = new LinkedHashMap<>();
        for (Host host : hosts) {
            for (GuestEntity guest : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    placement.put(vm.getId(), host.getId());
                }
            }
        }
        return placement;
    }

    /**
     * Apply the initial placement to live CloudSim state.
     *
     * <p>Before this call the VMs are on no host at all, so this is a direct
     * guest-list insertion, NOT a migration: it makes no VmMigrationService
     * call, produces no MigrationEvent, and therefore does not count toward
     * the migration count or migration cost. Only the post-NSSAO moves of
     * the selected candidate are migrations.
     */
    private void applyInitialPlacement(Map<Integer, Integer> placement) throws Exception {
        for (Map.Entry<Integer, Integer> entry : placement.entrySet()) {
            int vmId = entry.getKey();
            int targetHostId = entry.getValue();
            Vm vm = findVmById(vmId);
            Host destHost = findHostById(targetHostId);
            destHost.getGuestList().add(vm);
        }
    }

    /**
     * Apply the selected candidate placement and return migration events.
     */
    private List<MigrationEvent> applySelectedCandidate(
            CandidatePlacement selected, double migrationTime) throws Exception {
        List<MigrationEvent> events = new ArrayList<>();
        Map<Integer, Integer> targetPlacement = selected.viewMapping();
        Map<Integer, Integer> current = captureCurrentPlacement();

        for (Map.Entry<Integer, Integer> entry : targetPlacement.entrySet()) {
            int vmId = entry.getKey();
            int targetHostId = entry.getValue();

            if (current.getOrDefault(vmId, -1) != targetHostId) {
                Vm vm = findVmById(vmId);
                Host sourceHost = findHostById(current.get(vmId));
                Host destHost = findHostById(targetHostId);

                MigrationEvent event =
                        migrationService.migrateVm(vm, sourceHost, destHost, migrationTime);
                events.add(event);
                current.put(vmId, targetHostId);
            }
        }

        return events;
    }

    /**
     * Extract list of movable VM IDs (all VMs in this scenario).
     */
    private List<Integer> extractMovableVmIds() {
        List<Integer> ids = new ArrayList<>();
        for (Vm vm : allVms) {
            ids.add(vm.getId());
        }
        ids.sort(Integer::compareTo);
        return ids;
    }

    /**
     * Collect final metrics from all stages.
     */
    private SimulationMetrics collectMetrics(List<MigrationEvent> migrationEvents)
            throws Exception {
        // Objective values from selected candidate
        org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues objectives =
                selectionResult.getSelectedObjectives();

        // Active host count
        int activeHosts = 0;
        for (Host host : hosts) {
            if (!host.getGuestList().isEmpty()) {
                activeHosts++;
            }
        }

        // Utilization
        double totalCpuCapacity = 0;
        double totalCpuAlloc = 0;
        double totalMemCapacity = 0;
        double totalMemAlloc = 0;

        for (Host host : hosts) {
            for (org.cloudbus.cloudsim.Pe pe : host.getPeList()) {
                totalCpuCapacity += pe.getMips();
            }

            for (GuestEntity guest : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    totalCpuAlloc += vm.getMips();
                    totalMemAlloc += vm.getRam();
                }
            }
            totalMemCapacity += host.getRam();
        }

        double cpuUtil = totalCpuCapacity > 0 ? totalCpuAlloc / totalCpuCapacity : 0;
        double memUtil = totalMemCapacity > 0 ? totalMemAlloc / totalMemCapacity : 0;
        double overallUtil = (cpuUtil + memUtil) / 2;

        // Co-residency duration, derived from tracker snapshots recorded at
        // the ACTUAL simulation events (initial placement at t=0, migration
        // at migrationTime) — see Stage 2D tracker episode reconstruction.
        List<CoResidencyDurationTracker.CoResidencyEpisode> episodes =
                durationTracker.getEpisodes();
        double totalDuration = durationTracker.getTotalCoResidencyDuration();
        double highThreatDuration = durationTracker.getTotalHighThreatCoResidencyDuration();

        // Repair rate (placeholder — would need decoder statistics integration)
        // For now, set to 0 as it requires decoder integration
        int repairCount = 0;
        double repairRate = 0;

        return new SimulationMetrics.Builder()
                .f1ThreatServerRisk(objectives.getF1ThreatServerRisk())
                .f2CoResidencyRisk(objectives.getF2CoResidencyRisk())
                .f3Cost(objectives.getF3Cost())
                .f4UnusedCapacity(objectives.getF4UnusedCapacity())
                .activeHostCount(activeHosts)
                .cpuUtilization(cpuUtil)
                .memoryUtilization(memUtil)
                .overallUtilization(overallUtil)
                .migrationCount(migrationEvents.size())
                .migrationCost(migrationEvents.size() * perMigrationCost)
                .totalCoResidencyDuration(totalDuration)
                .highThreatCoResidencyDuration(highThreatDuration)
                .coResidencyEpisodeCount(episodes.size())
                .iterationsExecuted(nssaoResult.getIterationsExecuted())
                .stoppedByStall(nssaoResult.isStoppedByStall())
                .rejectedDecodeCount(nssaoResult.getRejectedDecodeCount())
                .front1RawSize(selectionResult.getFront1RawSize())
                .uniquePlacements(selectionResult.getUniquePlacements())
                .duplicatesRemoved(selectionResult.getDuplicatesRemoved())
                .repairCount(repairCount)
                .repairRate(repairRate)
                .build();
    }

    /**
     * Verify placement feasibility (capacity checks).
     */
    private void verifyPlacementFeasibility(Map<Integer, Integer> placement, String stageName)
            throws Exception {
        Map<Integer, Double> hostCpu = new LinkedHashMap<>();
        Map<Integer, Integer> hostRam = new LinkedHashMap<>();

        for (Host host : hosts) {
            double totalCpu = 0;
            for (org.cloudbus.cloudsim.Pe pe : host.getPeList()) {
                totalCpu += pe.getMips();
            }
            hostCpu.put(host.getId(), totalCpu);
            hostRam.put(host.getId(), host.getRam());
        }

        for (Map.Entry<Integer, Integer> entry : placement.entrySet()) {
            int vmId = entry.getKey();
            int hostId = entry.getValue();
            Vm vm = findVmById(vmId);
            Host host = findHostById(hostId);

            if (hostCpu.get(hostId) < vm.getMips()) {
                throw new Exception(
                        String.format(
                                "[%s] CPU capacity violated: VM %d (%.0f MIPS) cannot fit on H%d (%.0f MIPS)",
                                stageName, vmId, vm.getMips(), hostId, hostCpu.get(hostId)));
            }
            if (hostRam.get(hostId) < vm.getRam()) {
                throw new Exception(
                        String.format(
                                "[%s] RAM capacity violated: VM %d (%d RAM) cannot fit on H%d (%d RAM)",
                                stageName, vmId, vm.getRam(), hostId, hostRam.get(hostId)));
            }

            hostCpu.put(hostId, hostCpu.get(hostId) - vm.getMips());
            hostRam.put(hostId, hostRam.get(hostId) - vm.getRam());
        }
    }

    /**
     * Verify that CloudSim state is unchanged (purity check after NSSAO).
     */
    private void verifyCloudSimPurity(String stageName) throws Exception {
        Map<Integer, Integer> current = captureCurrentPlacement();

        if (!current.equals(currentMapping)) {
            throw new Exception(
                    String.format(
                            "[%s] CloudSim state was mutated! "
                                    + "Expected: %s, Got: %s",
                            stageName, currentMapping, current));
        }
    }

    // === Helpers ===

    private Vm findVmById(int vmId) throws Exception {
        for (Vm vm : allVms) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new Exception("VM not found: " + vmId);
    }

    private Host findHostById(int hostId) throws Exception {
        for (Host host : hosts) {
            if (host.getId() == hostId) {
                return host;
            }
        }
        throw new Exception("Host not found: " + hostId);
    }

    // === Getters ===

    public SimulationMetrics getMetrics() {
        if (metrics == null) {
            throw new IllegalStateException("run() has not completed yet");
        }
        return metrics;
    }

    public NssaoOptimizer.RunResult getNssaoResult() {
        if (nssaoResult == null) {
            throw new IllegalStateException("run() has not completed NSSAO stage yet");
        }
        return nssaoResult;
    }

    public ParetoSolutionSelector.SelectionResult getSelectionResult() {
        if (selectionResult == null) {
            throw new IllegalStateException("run() has not completed selection stage yet");
        }
        return selectionResult;
    }

    /**
     * Actual migration events recorded by the migration service during the
     * single post-selection application of the selected candidate.
     */
    public List<MigrationEvent> getMigrationEvents() {
        return migrationService.getMigrationEvents();
    }

    /**
     * Initial (pre-NSSAO) VM→host mapping that was applied to CloudSim.
     */
    public Map<Integer, Integer> getInitialMapping() {
        if (initialMapping == null) {
            throw new IllegalStateException("run() has not completed the initial placement stage yet");
        }
        return new LinkedHashMap<>(initialMapping);
    }
}
