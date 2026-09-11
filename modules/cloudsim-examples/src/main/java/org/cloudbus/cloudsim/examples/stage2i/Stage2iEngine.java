package org.cloudbus.cloudsim.examples.stage2i;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
import org.cloudbus.cloudsim.examples.stage2d.CoResidencyDurationTracker;
import org.cloudbus.cloudsim.examples.stage2d.MigrationEvent;
import org.cloudbus.cloudsim.examples.stage2d.VmMigrationService;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveEvaluator;
import org.cloudbus.cloudsim.examples.stage2h.InitialPlacementStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stage 2I.2 — shared experiment engine (the COMMON EVALUATION PATH).
 *
 * <p>Every method (proposed NSSAO and baselines B1/B2/B3) runs inside this
 * engine so that fairness rules hold by construction:
 * <ol>
 *   <li>Deterministic FFD initial placement, computed identically for every
 *       method from the same scenario objects.</li>
 *   <li>The initial placement is applied to live CloudSim state ONCE (the
 *       shared starting state); co-residency snapshot at t=0.</li>
 *   <li>Method search is HYPOTHETICAL: {@code validate}/{@code evaluate}
 *       read state but never mutate it. (NSSAO uses the same evaluator
 *       instance and the same hypothetical rule as in Stage 2G/2H.)</li>
 *   <li>Objectives f1–f4 of a final placement are computed by the Stage 2F
 *       evaluator with live state = shared initial placement, so f3's
 *       migration term = |final − initial| for every method.</li>
 *   <li>The final placement is then applied physically via the Stage 2D
 *       {@link VmMigrationService} (migration events recorded), and the
 *       co-residency snapshot at t=1 is taken. Post-hoc metrics
 *       (utilization, co-residency duration) come from live state.</li>
 * </ol>
 *
 * <p>Cost parameters: perHostCost = 1.0, perMigrationCost = 1.0 (Stage 2H
 * validation convention). Server risk: ServerRiskModel(0.1, 0.3) (Stage 2C/2F
 * convention). Both fixed for all scenarios and methods (Stage 2I.1 §4.5).
 */
public final class Stage2iEngine {

    public static final double PER_HOST_COST = 1.0;
    public static final double PER_MIGRATION_COST = 1.0;

    /** Simulation time of the migration event (Stage 2H convention). */
    public static final double MIGRATION_TIME = 1.0;

    private final Stage2iScenario.ScenarioData scenario;
    private final List<Host> hosts;
    private final List<Vm> vms;
    private final Map<Integer, VmThreatState> threatMap;
    private final ObjectiveEvaluator evaluator;
    private final CoResidencyDurationTracker tracker;
    private final VmMigrationService migrationService;

    private CandidatePlacement initialPlacement;
    private boolean initialApplied = false;

    public Stage2iEngine(Stage2iScenario.ScenarioData scenario) {
        this.scenario = scenario;
        this.hosts = scenario.hosts;
        this.vms = scenario.vms;
        this.threatMap = scenario.threatMap;
        this.evaluator = new ObjectiveEvaluator(
                new ServerRiskModel(0.1, 0.3),
                PER_HOST_COST, PER_MIGRATION_COST);
        this.tracker = new CoResidencyDurationTracker(
                threatMap, scenario.adaptiveThreshold);
        this.migrationService = new VmMigrationService(threatMap);
    }

    // === Scenario access ===

    public List<Host> hosts() {
        return hosts;
    }

    public List<Vm> vms() {
        return vms;
    }

    public Map<Integer, VmThreatState> threatMap() {
        return threatMap;
    }

    public double adaptiveThreshold() {
        return scenario.adaptiveThreshold;
    }

    /**
     * The shared Stage 2F evaluator instance. Exposed so the proposed method
     * (Stage 2G optimizer) uses the SAME evaluation code path as the
     * baselines — identical f1–f4 definitions, cost and risk parameters.
     * This does not bypass any Stage 2F/2G logic; it is the composition point
     * the Stage 2G optimizer constructor requires.
     */
    public ObjectiveEvaluator evaluatorRef() {
        return evaluator;
    }

    // === Initial placement (shared by every method) ===

    /**
     * Compute the deterministic FFD initial placement (Stage 2H strategy,
     * unchanged). Reads host descriptors only; does not mutate live state.
     */
    public CandidatePlacement computeInitialFfdPlacement() {
        Map<Integer, Integer> map =
                new InitialPlacementStrategy(hosts, vms).computePlacement();
        initialPlacement = new CandidatePlacement(map, hosts);
        return initialPlacement;
    }

    /**
     * Apply the initial placement to live CloudSim state (direct guest-list
     * insertion — VMs start on no host; this is NOT a migration and produces
     * no MigrationEvent). Records the co-residency snapshot at t=0.
     */
    public void applyInitialPlacementToLiveState() {
        if (initialPlacement == null) {
            throw new IllegalStateException("computeInitialFfdPlacement() first");
        }
        if (initialApplied) {
            throw new IllegalStateException("initial placement already applied");
        }
        for (Map.Entry<Integer, Integer> e
                : initialPlacement.viewMapping().entrySet()) {
            Vm vm = findVm(e.getKey());
            findHost(e.getValue()).getGuestList().add(vm);
        }
        tracker.recordSnapshot(hosts, 0.0);
        initialApplied = true;
    }

    public CandidatePlacement initialPlacement() {
        if (initialPlacement == null) {
            throw new IllegalStateException("initial placement not computed yet");
        }
        return initialPlacement;
    }

    // === Hypothetical evaluation (used by ALL methods' search) ===

    /** Validate a candidate mapping against Stage 2F rejection rules. */
    public List<String> validate(Map<Integer, Integer> mapping) {
        return evaluator.validate(new CandidatePlacement(mapping, hosts), vms);
    }

    /** Validate a candidate placement against Stage 2F rejection rules. */
    public List<String> validate(CandidatePlacement placement) {
        return evaluator.validate(placement, vms);
    }

    /** Evaluate a candidate mapping (f1–f4, active hosts, migrations vs live). */
    public ObjectiveEvaluator.EvaluationResult evaluate(
            Map<Integer, Integer> mapping) {
        return evaluator.evaluate(new CandidatePlacement(mapping, hosts),
                vms, threatMap);
    }

    /** Evaluate a candidate placement (f1–f4, active hosts, migrations vs live). */
    public ObjectiveEvaluator.EvaluationResult evaluate(
            CandidatePlacement placement) {
        return evaluator.evaluate(placement, vms, threatMap);
    }

    // === Physical application of the FINAL placement ===

    /**
     * Apply the final placement to live state: VMs whose final host differs
     * from their current live host are moved through the Stage 2D
     * {@link VmMigrationService} (each move = one MigrationEvent). Then the
     * co-residency snapshot at t=1 is recorded.
     *
     * @return the recorded migration events (size == changed assignments)
     */
    public List<MigrationEvent> applyFinalPlacement(
            CandidatePlacement finalPlacement) throws Exception {
        if (!initialApplied) {
            throw new IllegalStateException("initial placement not applied yet");
        }
        List<MigrationEvent> events = new ArrayList<>();
        Map<Integer, Integer> current = livePlacementMap();
        for (Map.Entry<Integer, Integer> e
                : finalPlacement.viewMapping().entrySet()) {
            int vmId = e.getKey();
            int targetHostId = e.getValue();
            Integer currentHostId = current.get(vmId);
            if (currentHostId == null || currentHostId != targetHostId) {
                Vm vm = findVm(vmId);
                Host source = findHost(currentHostId);
                Host dest = findHost(targetHostId);
                events.add(migrationService.migrateVm(
                        vm, source, dest, MIGRATION_TIME));
            }
        }
        tracker.recordSnapshot(hosts, MIGRATION_TIME);
        return events;
    }

    // === Post-hoc metrics from live state ===

    /** Live VM→host map read from host guest lists. */
    public Map<Integer, Integer> livePlacementMap() {
        Map<Integer, Integer> placement = new LinkedHashMap<>();
        for (Host host : hosts) {
            for (org.cloudbus.cloudsim.core.GuestEntity guest
                    : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    placement.put(vm.getId(), host.getId());
                }
            }
        }
        return placement;
    }

    /** Number of hosts holding at least one VM in live state. */
    public int liveActiveHostCount() {
        int count = 0;
        for (Host host : hosts) {
            if (!host.getGuestList().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** Aggregate CPU utilization of the live state. */
    public double liveCpuUtilization() {
        double capacity = 0;
        double allocated = 0;
        for (Host host : hosts) {
            for (org.cloudbus.cloudsim.Pe pe : host.getPeList()) {
                capacity += pe.getMips();
            }
            for (org.cloudbus.cloudsim.core.GuestEntity guest
                    : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    allocated += vm.getMips();
                }
            }
        }
        return capacity > 0 ? allocated / capacity : 0;
    }

    /** Aggregate memory utilization of the live state. */
    public double liveMemoryUtilization() {
        double capacity = 0;
        double allocated = 0;
        for (Host host : hosts) {
            capacity += host.getRam();
            for (org.cloudbus.cloudsim.core.GuestEntity guest
                    : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    allocated += vm.getRam();
                }
            }
        }
        return capacity > 0 ? allocated / capacity : 0;
    }

    /** Overall utilization = (CPU + memory) / 2 of the live state. */
    public double liveOverallUtilization() {
        return (liveCpuUtilization() + liveMemoryUtilization()) / 2.0;
    }

    // === Co-residency duration (post-hoc, Stage 2D tracker) ===

    public double totalCoResidencyDuration() {
        return tracker.getTotalCoResidencyDuration();
    }

    public double highThreatCoResidencyDuration() {
        return tracker.getTotalHighThreatCoResidencyDuration();
    }

    public int coResidencyEpisodeCount() {
        return tracker.getEpisodes().size();
    }

    public int snapshotCount() {
        return tracker.getSnapshots().size();
    }

    // === Determinism fingerprints ===

    /**
     * Deterministic fingerprint of live state: sorted "vmId:hostId" pairs.
     * Used to prove search did not mutate live state and that results are
     * reproducible.
     */
    public String liveFingerprint() {
        TreeMap<Integer, Integer> sorted = new TreeMap<>(livePlacementMap());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** Fingerprint of a mapping (sorted), same format as liveFingerprint. */
    public static String fingerprint(Map<Integer, Integer> mapping) {
        TreeMap<Integer, Integer> sorted = new TreeMap<>(mapping);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Number of VMs whose assignment differs between two mappings
     * (migration-count expectation: |final − initial|).
     */
    public static int changedAssignmentCount(
            Map<Integer, Integer> initial, Map<Integer, Integer> finalMap) {
        int count = 0;
        for (Map.Entry<Integer, Integer> e : finalMap.entrySet()) {
            Integer init = initial.get(e.getKey());
            if (init == null || init != e.getValue()) {
                count++;
            }
        }
        return count;
    }

    // === Helpers ===

    private Vm findVm(int vmId) {
        for (Vm vm : vms) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalArgumentException("VM not found: " + vmId);
    }

    private Host findHost(int hostId) {
        for (Host host : hosts) {
            if (host.getId() == hostId) {
                return host;
            }
        }
        throw new IllegalArgumentException("Host not found: " + hostId);
    }
}
