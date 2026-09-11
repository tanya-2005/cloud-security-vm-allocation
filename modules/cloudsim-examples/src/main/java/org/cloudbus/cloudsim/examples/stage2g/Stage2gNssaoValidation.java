package org.cloudbus.cloudsim.examples.stage2g;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveEvaluator;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stage 2G.2 — NSSAO Core Validation.
 *
 * <p>Small deterministic scenario (same construction pattern as the Stage 2F
 * validation):
 * <pre>
 * Hosts: H0 (2000 MIPS / 4096 RAM), H1 (1000/2048), H2 (1000/2048)
 * VMs:   A(0.9,e1)@H0  B(0.8,e2)@H0  C(0.2,e3)@H1  D(0.2,e3)@H1
 * Movable: A, B, C, D (all four)
 * </pre>
 *
 * <p>Verifies the 17 required checks: population size, one position per VM,
 * position bounds, legitimate co-residency, structural validity, repair/
 * reject behavior, ObjectiveEvaluator usage, no weighted-sum fitness,
 * minimization dominance, valid fronts, deterministic crowding distance,
 * stable population size, same-seed determinism, different-seed divergence,
 * no live-state mutation, feasible final candidate, completion within t_max.
 */
public class Stage2gNssaoValidation {

    private static final double EPS = 1e-9;
    private static int passed = 0;
    private static int failed = 0;

    private static final int HOST_0_ID = 0;
    private static final int HOST_1_ID = 1;
    private static final int HOST_2_ID = 2;

    private static final int VM_A_ID = 10;
    private static final int VM_B_ID = 11;
    private static final int VM_C_ID = 12;
    private static final int VM_D_ID = 13;

    public static void main(String[] args) {
        Log.println("========================================");
        Log.println("STAGE 2G.2 VALIDATION");
        Log.println("NSSAO CORE — hypothetical placements only");
        Log.println("NO migration. NO live-state mutation.");
        Log.println("========================================");
        Log.println();

        try {
            // === Scenario (identical construction to Stage 2F validation) ===
            List<Host> hosts = buildHosts();
            List<Vm> vms = new ArrayList<>();
            Map<Integer, VmThreatState> threatMap = new LinkedHashMap<>();

            Vm vmA = createVm(VM_A_ID, 500, 512);
            Vm vmB = createVm(VM_B_ID, 500, 512);
            Vm vmC = createVm(VM_C_ID, 500, 512);
            Vm vmD = createVm(VM_D_ID, 500, 512);
            vms.add(vmA);
            vms.add(vmB);
            vms.add(vmC);
            vms.add(vmD);

            threatMap.put(VM_A_ID, new VmThreatState(VM_A_ID, 0.9,
                    "azure_v2", "deployment", "entity-e1", 0.5));
            threatMap.put(VM_B_ID, new VmThreatState(VM_B_ID, 0.8,
                    "azure_v2", "deployment", "entity-e2", 0.5));
            threatMap.put(VM_C_ID, new VmThreatState(VM_C_ID, 0.2,
                    "azure_v2", "deployment", "entity-e3", 0.5));
            threatMap.put(VM_D_ID, new VmThreatState(VM_D_ID, 0.2,
                    "azure_v2", "deployment", "entity-e3", 0.5));

            // Live placement: A,B@H0; C,D@H1; H2 empty. READ-ONLY input.
            hosts.get(0).getGuestList().add(vmA);
            hosts.get(0).getGuestList().add(vmB);
            hosts.get(1).getGuestList().add(vmC);
            hosts.get(1).getGuestList().add(vmD);

            Map<Integer, Integer> currentMapping = new LinkedHashMap<>();
            currentMapping.put(VM_A_ID, HOST_0_ID);
            currentMapping.put(VM_B_ID, HOST_0_ID);
            currentMapping.put(VM_C_ID, HOST_1_ID);
            currentMapping.put(VM_D_ID, HOST_1_ID);

            List<Integer> movable = Arrays.asList(
                    VM_A_ID, VM_B_ID, VM_C_ID, VM_D_ID);

            ObjectiveEvaluator evaluator =
                    new ObjectiveEvaluator(new ServerRiskModel(0.1, 0.3), 1.0, 1.0);

            NssaoConfig config = NssaoConfig.builder()
                    .populationSize(12)
                    .maxIterations(6)
                    .seed(42L)
                    .stallLimit(3)
                    .build();

            // === Fingerprints for live-state safety (T15) ===
            Map<Integer, List<Integer>> liveBefore = liveFingerprint(hosts);
            Map<Integer, Integer> mappingBefore = new LinkedHashMap<>(currentMapping);

            // === Run 1 (seed 42) ===
            NssaoOptimizer optimizer = new NssaoOptimizer(
                    hosts, vms, movable, currentMapping, threatMap, evaluator, config);
            NssaoOptimizer.RunResult run1 = optimizer.run();

            Log.println("Run 1 (seed=42): iterations=" + run1.getIterationsExecuted()
                    + " stallStop=" + run1.isStoppedByStall()
                    + " rejectedDecodes=" + run1.getRejectedDecodeCount());
            Log.println("Front 1 size: " + run1.getFronts().get(0).size()
                    + " / population " + run1.getPopulation().size());
            Log.println();

            // === T1: population has the configured size ===
            check("T1 population = configured size (N="
                    + config.getPopulationSize() + ")",
                    run1.getPopulation().size() == config.getPopulationSize());

            // === T2: every individual has exactly one position per VM ===
            boolean onePositionPerVm = true;
            for (NssaoOptimizer.Individual ind : run1.getPopulation()) {
                if (ind.getPosition().length != movable.size()) {
                    onePositionPerVm = false;
                }
            }
            check("T2 exactly one position per VM (dimension="
                            + movable.size() + ")", onePositionPerVm);

            // === T3: positions within [0, H-1] ===
            boolean boundsOk = true;
            for (NssaoOptimizer.Individual ind : run1.getPopulation()) {
                for (double x : ind.getPosition()) {
                    if (x < 0.0 || x > hosts.size() - 1 + 1e-12) {
                        boundsOk = false;
                    }
                }
            }
            check("T3 positions within [0, H-1]", boundsOk);

            // === T4: multiple VMs legitimately map to one host ===
            // All-zero positions decode every movable VM onto host index 0.
            PlacementDecoder decoder = new PlacementDecoder();
            double[] allZero = new double[movable.size()];
            PlacementDecoder.DecodeResult dz = decoder.decode(
                    allZero, movable, currentMapping, hosts, vms);
            Map<Integer, Integer> mz = dz.getCandidate().viewMapping();
            boolean coResidencyOk = dz.isSuccess()
                    && mz.get(VM_A_ID).equals(mz.get(VM_B_ID))
                    && mz.get(VM_A_ID).equals(mz.get(VM_C_ID))
                    && mz.get(VM_A_ID).equals(mz.get(VM_D_ID))
                    && mz.size() == 4;
            check("T4 multiple VMs map to one host (legitimate co-residency)",
                    coResidencyOk);

            // === T5: every generated CandidatePlacement structurally valid ===
            boolean allStructurallyValid = true;
            for (NssaoOptimizer.Individual ind : run1.getPopulation()) {
                CandidatePlacement cp = ind.getPlacement();
                if (cp == null) {
                    continue; // rejected decode; covered by T6/rejection count
                }
                if (!structurallyValid(cp, vms, hosts)) {
                    allStructurallyValid = false;
                }
                ObjectiveEvaluator.EvaluationResult er =
                        evaluator.evaluate(cp, vms, threatMap);
                if (!er.isValid()) {
                    allStructurallyValid = false;
                }
            }
            check("T5 every CandidatePlacement structurally valid + evaluator-accepted",
                    allStructurallyValid);

            // === T6: infeasible candidates are repaired or rejected ===
            boolean repairWorks = testRepairPath(hosts, vms, currentMapping, movable);
            check("T6 infeasible decode repaired deterministically (overflow host relieved)",
                    repairWorks);

            // === T7: ObjectiveEvaluator is used for f1-f4 (no duplication) ===
            boolean evaluatorUsed = true;
            for (NssaoOptimizer.Individual ind : run1.getPopulation()) {
                if (ind.getPlacement() == null || ind.getObjectives() == null) {
                    continue;
                }
                ObjectiveEvaluator.EvaluationResult er =
                        evaluator.evaluate(ind.getPlacement(), vms, threatMap);
                if (!er.isValid()) {
                    evaluatorUsed = false;
                    continue;
                }
                ObjectiveValues v = ind.getObjectives();
                ObjectiveValues w = er.getObjectives();
                if (Double.compare(v.getF1ThreatServerRisk(), w.getF1ThreatServerRisk()) != 0
                        || Double.compare(v.getF2CoResidencyRisk(), w.getF2CoResidencyRisk()) != 0
                        || Double.compare(v.getF3Cost(), w.getF3Cost()) != 0
                        || Double.compare(v.getF4UnusedCapacity(), w.getF4UnusedCapacity()) != 0) {
                    evaluatorUsed = false;
                }
            }
            check("T7 stored objectives identical to ObjectiveEvaluator output",
                    evaluatorUsed);

            // === T8: no weighted-sum fitness replaces Pareto evaluation ===
            check("T8 Pareto (not weighted sum) drives selection",
                    testNoWeightedSum());

            // === T9: Pareto dominance correct for minimization ===
            check("T9 minimization dominance correct (incl. equal vectors, incomparables)",
                    testDominance());

            // === T10: non-dominated sorting produces valid fronts ===
            check("T10 non-dominated sorting valid (partition, correct ranks)",
                    testNonDominatedSort());

            // === T11: crowding distance is deterministic ===
            check("T11 crowding distance deterministic (same inputs, same output)",
                    testCrowdingDeterministic());

            // === T12: population size stable + full front partition ===
            int covered = 0;
            for (List<Integer> front : run1.getFronts()) {
                covered += front.size();
            }
            check("T12 population stable (N=" + config.getPopulationSize()
                            + ", fronts cover " + covered + ")",
                    run1.getPopulation().size() == config.getPopulationSize()
                            && covered == config.getPopulationSize());

            // === T13: same seed produces identical results ===
            NssaoOptimizer optimizer2 = new NssaoOptimizer(
                    hosts, vms, movable, currentMapping, threatMap, evaluator, config);
            NssaoOptimizer.RunResult run2 = optimizer2.run();
            check("T13 same seed => identical results", runsIdentical(run1, run2));

            // === T14: different seed can produce a different trajectory ===
            NssaoConfig configB = NssaoConfig.builder()
                    .populationSize(12)
                    .maxIterations(6)
                    .seed(7L)
                    .stallLimit(3)
                    .build();
            NssaoOptimizer optimizer3 = new NssaoOptimizer(
                    hosts, vms, movable, currentMapping, threatMap, evaluator, configB);
            NssaoOptimizer.RunResult run3 = optimizer3.run();
            check("T14 different seed => different search trajectory",
                    !runsIdentical(run1, run3));

            // === T15: NSSAO does not mutate live CloudSim placement ===
            Map<Integer, List<Integer>> liveAfter = liveFingerprint(hosts);
            boolean noMutation = liveBefore.equals(liveAfter)
                    && mappingBefore.equals(currentMapping);
            check("T15 live CloudSim placement unchanged after optimization",
                    noMutation);

            // === T16: best/final candidate is feasible ===
            List<NssaoOptimizer.Individual> front1 = run1.getFirstFront();
            boolean finalFeasible = !front1.isEmpty();
            for (NssaoOptimizer.Individual ind : front1) {
                if (ind.getPlacement() == null) {
                    finalFeasible = false;
                    break;
                }
                ObjectiveEvaluator.EvaluationResult er =
                        evaluator.evaluate(ind.getPlacement(), vms, threatMap);
                if (!er.isValid()
                        || !structurallyValid(ind.getPlacement(), vms, hosts)) {
                    finalFeasible = false;
                    break;
                }
            }
            check("T16 final front-1 candidates all feasible", finalFeasible);

            // === T17: completes within configured iterations ===
            check("T17 iterations executed <= t_max ("
                            + run1.getIterationsExecuted() + " <= "
                            + config.getMaxIterations() + ")",
                    run1.getIterationsExecuted() <= config.getMaxIterations());

            // === Report ===
            Log.println();
            Log.println("========================================");
            Log.println("STAGE 2G.2 VALIDATION REPORT");
            Log.println("========================================");
            Log.println("Checks passed: " + passed);
            Log.println("Checks failed: " + failed);
            Log.println();
            if (failed > 0) {
                Log.println("STAGE 2G.2 VALIDATION FAILED");
                Log.println("========================================");
                System.exit(1);
            }
            Log.println("STAGE 2G.2 VALIDATION PASSED");
            Log.println("NSSAO core complete: hypothetical placements only.");
            Log.println("Actual VM migration NOT performed (later stage).");
            Log.println("========================================");

        } catch (Exception e) {
            Log.println("FATAL ERROR in Stage 2G.2 validation:");
            Log.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === focused tests ===

    /**
     * Positions send A,B,C,D to H1 (1000 MIPS): total demand 4x500 = 2000
     * > 1000 capacity -> repair must relieve H1 while keeping the placement
     * feasible, preserving as much of the proposal as possible.
     */
    private static boolean testRepairPath(List<Host> hosts,
                                          List<Vm> vms,
                                          Map<Integer, Integer> currentMapping,
                                          List<Integer> movable) {
        double[] allOnH1 = {1, 1, 1, 1};
        PlacementDecoder decoder = new PlacementDecoder();
        PlacementDecoder.DecodeResult dr = decoder.decode(
                allOnH1, movable, currentMapping, hosts, vms);
        if (!dr.isSuccess()) {
            return false; // repair should succeed in this scenario
        }
        // Demand check: no host over capacity
        Map<Integer, double[]> dem = new TreeMap<>();
        for (Host h : hosts) {
            dem.put(h.getId(), new double[]{0.0, 0.0});
        }
        for (Map.Entry<Integer, Integer> e : dr.getCandidate().viewMapping().entrySet()) {
            Vm vm = findById(vms, e.getKey());
            dem.get(e.getValue())[0] += vm.getTotalMips();
            dem.get(e.getValue())[1] += vm.getRam();
        }
        for (Map.Entry<Integer, double[]> e : dem.entrySet()) {
            Host h = hostById(hosts, e.getKey());
            if (e.getValue()[0] > h.getTotalMips() + 1e-9
                    || e.getValue()[1] > h.getRam() + 1e-9) {
                return false;
            }
        }
        // Preservation: H1 fits at most 2 VMs of 500 MIPS; at least one VM
        // must remain on the proposed host H1 after repair.
        Map<Integer, Integer> m = dr.getCandidate().viewMapping();
        int onH1 = 0;
        for (int vmId : movable) {
            if (m.get(vmId) == HOST_1_ID) {
                onH1++;
            }
        }
        return dr.wasRepaired() && onH1 >= 1 && onH1 <= 2;
    }

    /**
     * a wins f1, b wins f3: neither dominates. A weighted sum would rank one
     * above the other; Pareto keeps both in front 0.
     */
    private static boolean testNoWeightedSum() {
        ObjectiveValues a = new ObjectiveValues(0.1, 0.0, 10.0, 0.5);
        ObjectiveValues b = new ObjectiveValues(0.2, 0.0, 3.0, 0.5);
        boolean neitherDominates = !Pareto.dominates(a, b)
                && !Pareto.dominates(b, a);
        List<ObjectiveValues> combined = Arrays.asList(a, b);
        List<List<Integer>> fronts = Pareto.nonDominatedSort(combined);
        boolean bothInFront0 = fronts.get(0).size() == 2;
        return neitherDominates && bothInFront0;
    }

    private static boolean testDominance() {
        ObjectiveValues base = new ObjectiveValues(0.5, 0.5, 5.0, 0.5);
        ObjectiveValues worse = new ObjectiveValues(0.6, 0.5, 5.0, 0.5);
        ObjectiveValues better = new ObjectiveValues(0.4, 0.5, 5.0, 0.5);
        ObjectiveValues incomparable = new ObjectiveValues(0.4, 0.5, 6.0, 0.5);
        ObjectiveValues betterF2 = new ObjectiveValues(0.5, 0.4, 5.0, 0.5);

        boolean d1 = Pareto.dominates(base, worse);
        boolean d2 = Pareto.dominates(better, base);
        boolean d3 = !Pareto.dominates(base, incomparable)
                && !Pareto.dominates(incomparable, base);
        // betterF2 is strictly better in f2, equal elsewhere: it dominates base
        boolean d4 = Pareto.dominates(betterF2, base)
                && !Pareto.dominates(base, betterF2);
        boolean d5 = !Pareto.dominates(base, base);
        return d1 && d2 && d3 && d4 && d5;
    }

    private static boolean testNonDominatedSort() {
        List<ObjectiveValues> vals = Arrays.asList(
                new ObjectiveValues(0.1, 0.1, 1.0, 0.1),  // A
                new ObjectiveValues(0.2, 0.2, 2.0, 0.2),  // B (dominated by A)
                new ObjectiveValues(0.3, 0.0, 3.0, 0.3),  // C (incomparable w/ A)
                new ObjectiveValues(0.4, 0.4, 4.0, 0.4)); // D (dominated by A,B,C)
        List<List<Integer>> fronts = Pareto.nonDominatedSort(vals);
        boolean f1 = fronts.get(0).containsAll(Arrays.asList(0, 2))
                && fronts.get(0).size() == 2;
        boolean f2 = fronts.get(1).containsAll(Arrays.asList(1))
                && fronts.get(1).size() == 1;
        boolean f3 = fronts.get(2).containsAll(Arrays.asList(3))
                && fronts.get(2).size() == 1;
        int partition = 0;
        for (List<Integer> front : fronts) {
            partition += front.size();
        }
        return f1 && f2 && f3 && partition == 4;
    }

    private static boolean testCrowdingDeterministic() {
        List<ObjectiveValues> vals = Arrays.asList(
                new ObjectiveValues(0.0, 0.0, 0.0, 0.0),
                new ObjectiveValues(0.5, 0.5, 0.5, 0.5),
                new ObjectiveValues(1.0, 1.0, 1.0, 1.0));
        List<Integer> front = Arrays.asList(0, 1, 2);
        double[] cd1 = Pareto.crowdingDistance(front, vals);
        double[] cd2 = Pareto.crowdingDistance(front, vals);
        boolean same = Arrays.equals(cd1, cd2);
        boolean boundaryInf = Double.isInfinite(cd1[0]) && Double.isInfinite(cd1[2]);
        boolean interiorFinite = Double.isFinite(cd1[1]);
        return same && boundaryInf && interiorFinite;
    }

    private static boolean runsIdentical(NssaoOptimizer.RunResult a,
                                         NssaoOptimizer.RunResult b) {
        if (a.getIterationsExecuted() != b.getIterationsExecuted()
                || a.isStoppedByStall() != b.isStoppedByStall()
                || a.getRejectedDecodeCount() != b.getRejectedDecodeCount()) {
            return false;
        }
        if (a.getPopulation().size() != b.getPopulation().size()) {
            return false;
        }
        for (int i = 0; i < a.getPopulation().size(); i++) {
            if (!Arrays.equals(a.getPopulation().get(i).getPosition(),
                    b.getPopulation().get(i).getPosition())) {
                return false;
            }
            ObjectiveValues va = a.getPopulation().get(i).getObjectives();
            ObjectiveValues vb = b.getPopulation().get(i).getObjectives();
            if (va == null || vb == null) {
                return false;
            }
            if (Double.compare(va.getF1ThreatServerRisk(), vb.getF1ThreatServerRisk()) != 0
                    || Double.compare(va.getF2CoResidencyRisk(), vb.getF2CoResidencyRisk()) != 0
                    || Double.compare(va.getF3Cost(), vb.getF3Cost()) != 0
                    || Double.compare(va.getF4UnusedCapacity(), vb.getF4UnusedCapacity()) != 0) {
                return false;
            }
        }
        return a.getFronts().size() == b.getFronts().size()
                && a.getFronts().equals(b.getFronts());
    }

    private static boolean structurallyValid(CandidatePlacement cp,
                                             List<Vm> vms,
                                             List<Host> hosts) {
        Map<Integer, Integer> m = cp.viewMapping();
        if (m.size() != vms.size()) {
            return false;
        }
        for (Vm vm : vms) {
            if (!m.containsKey(vm.getId())) {
                return false;
            }
            int hostId = m.get(vm.getId());
            boolean hostExists = false;
            for (Host h : hosts) {
                if (h.getId() == hostId) {
                    hostExists = true;
                    break;
                }
            }
            if (!hostExists) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, List<Integer>> liveFingerprint(List<Host> hosts) {
        Map<Integer, List<Integer>> fp = new TreeMap<>();
        for (Host h : hosts) {
            List<Integer> ids = new ArrayList<>();
            for (org.cloudbus.cloudsim.core.GuestEntity g : h.getGuestList()) {
                if (g instanceof Vm vm) {
                    ids.add(vm.getId());
                }
            }
            ids.sort(Integer::compareTo);
            fp.put(h.getId(), ids);
        }
        return fp;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            Log.println("  PASS  " + label);
        } else {
            failed++;
            Log.println("  FAIL  " + label);
        }
    }

    // === scenario construction (Stage 2F/2D conventions) ===

    private static List<Host> buildHosts() {
        List<Host> hosts = new ArrayList<>();
        hosts.add(createHost(HOST_0_ID, 4096, 1000000, 1000000, 2000));
        hosts.add(createHost(HOST_1_ID, 2048, 1000000, 1000000, 1000));
        hosts.add(createHost(HOST_2_ID, 2048, 1000000, 1000000, 1000));
        return hosts;
    }

    private static Host createHost(int hostId, int ram, long storage, long bw, int mips) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(new PeProvisionerSimple(mips)));
        return new Host(hostId, new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw), storage, peList,
                new VmSchedulerTimeShared(peList));
    }

    private static Vm createVm(int vmId, int mips, int ram) {
        return new Vm(vmId, 0, mips, 1, ram, 1000L, 1000L, "Xen",
                new CloudletSchedulerTimeShared());
    }

    private static Vm findById(List<Vm> vms, int id) {
        for (Vm vm : vms) {
            if (vm.getId() == id) {
                return vm;
            }
        }
        throw new IllegalArgumentException("VM not found: " + id);
    }

    private static Host hostById(List<Host> hosts, int id) {
        for (Host h : hosts) {
            if (h.getId() == id) {
                return h;
            }
        }
        throw new IllegalArgumentException("Host not found: " + id);
    }
}
