package org.cloudbus.cloudsim.examples.stage2f;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
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
 * Stage 2F — Objective Evaluation Validation.
 *
 * <p>Purpose: prove that the four approved objectives (f1..f4, all
 * minimized) are computed correctly from a CANDIDATE VM-to-host mapping,
 * that invalid candidates are rejected, and that evaluation never mutates
 * live CloudSim state.
 *
 * <p>This stage implements ONLY objective evaluation. It does NOT implement
 * NSSAO, optimization, non-dominated sorting, Sparrow Search, mutation/
 * crossover, or any new migration logic.
 *
 * <h3>Deterministic validation scenario</h3>
 * <pre>
 * Hosts (heterogeneous capacities so f4 is placement-sensitive):
 *   H0: 2000 MIPS, 4096 RAM
 *   H1: 1000 MIPS, 2048 RAM
 *   H2: 1000 MIPS, 2048 RAM
 *
 * VMs (all 500 MIPS, 512 RAM):
 *   A (id=10): threat=0.9, entity e1 — currently on H0
 *   B (id=11): threat=0.8, entity e2 — currently on H0
 *   C (id=12): threat=0.2, entity e3 — currently on H1
 *   D (id=13): threat=0.2, entity e3 (SAME entity as C) — currently on H1
 *   E (id=14): threat=0.4, entity e4, 100 MIPS but 3000 RAM (RAM-overflow test only)
 *
 * Simulation parameters: ServerRiskModel(base=0.1, utilWeight=0.3),
 * perHostCost=1.0, perMigrationCost=1.0.
 * </pre>
 *
 * <p>Hand-computed expected values (see project/docs/stage_2f_objective_evaluation.md):
 * <pre>
 * Candidate X: A→H0, B→H0, C→H1, D→H1
 *   f1=0.1228125  f2=0.9  f3=2.0 (2 hosts, 0 migrations)  f4=0.625
 * Candidate Y: A→H0, B→H1, C→H2, D→H2
 *   f1=0.11015625 f2=0.0 (C+D same entity) f3=6.0 (3 hosts, 3 migrations) f4=0.5625
 * Candidate ALL0: A,B,C,D→H0
 *   f3=3.0 (1 host, 2 migrations)  f4=0.75
 * Candidate V: A→H0, B→H1, C→H0, D→H1
 *   f2=1.7 (two cross-entity pairs summed across hosts)
 * </pre>
 *
 * <p>Run with (Windows classpath separator is ';'):
 * <pre>
 *   java -cp "cloudsim/modules/cloudsim/target/cloudsim-7.0.1.jar;cloudsim/modules/cloudsim-examples/target/classes" \
 *     org.cloudbus.cloudsim.examples.stage2f.Stage2fObjectiveValidation
 * </pre>
 */
public class Stage2fObjectiveValidation {

    private static final double EPS = 1e-9;

    // Host IDs
    private static final int HOST_0_ID = 0;
    private static final int HOST_1_ID = 1;
    private static final int HOST_2_ID = 2;

    // VM IDs
    private static final int VM_A_ID = 10;
    private static final int VM_B_ID = 11;
    private static final int VM_C_ID = 12;
    private static final int VM_D_ID = 13;
    private static final int VM_E_ID = 14; // RAM-overflow test VM (not in main candidates)

    private static final double HIGH_THREAT_THRESHOLD = 0.5; // TESTING ONLY (Stage 2C convention)

    // Counters for the final report
    private static int checksPassed = 0;
    private static int checksFailed = 0;

    public static void main(String[] args) {
        Log.println("========================================");
        Log.println("STAGE 2F VALIDATION");
        Log.println("Objective Evaluation (f1..f4) — candidates only");
        Log.println("NO NSSAO. NO optimization. NO new migration logic.");
        Log.println("========================================");
        Log.println();

        try {
            // CloudSim.init is not required because this validation never
            // creates entities or advances simulation time; it only builds
            // Host/Vm descriptor objects and reads them (same pattern as
            // Stage2dMigrationValidation).

            // === Scenario setup (this DOES build live state, like Stage 2D) ===
            List<Host> hosts = buildHosts();
            List<Vm> vms = new ArrayList<>();
            Map<Integer, VmThreatState> threatMap = new LinkedHashMap<>();

            Vm vmA = createVm(VM_A_ID, 500, 512);
            Vm vmB = createVm(VM_B_ID, 500, 512);
            Vm vmC = createVm(VM_C_ID, 500, 512);
            Vm vmD = createVm(VM_D_ID, 500, 512);
            Vm vmE = createVm(VM_E_ID, 100, 3000); // RAM-overflow probe only

            vms.add(vmA);
            vms.add(vmB);
            vms.add(vmC);
            vms.add(vmD);
            // vmE deliberately NOT part of the main placement set

            threatMap.put(VM_A_ID, new VmThreatState(VM_A_ID, 0.9,
                    "azure_v2", "deployment", "entity-e1", HIGH_THREAT_THRESHOLD));
            threatMap.put(VM_B_ID, new VmThreatState(VM_B_ID, 0.8,
                    "azure_v2", "deployment", "entity-e2", HIGH_THREAT_THRESHOLD));
            threatMap.put(VM_C_ID, new VmThreatState(VM_C_ID, 0.2,
                    "azure_v2", "deployment", "entity-e3", HIGH_THREAT_THRESHOLD));
            threatMap.put(VM_D_ID, new VmThreatState(VM_D_ID, 0.2,
                    "azure_v2", "deployment", "entity-e3", HIGH_THREAT_THRESHOLD));
            threatMap.put(VM_E_ID, new VmThreatState(VM_E_ID, 0.4,
                    "azure_v2", "deployment", "entity-e4", HIGH_THREAT_THRESHOLD));

            // Initial LIVE placement: A,B on H0; C,D on H1; H2 empty
            hosts.get(0).getGuestList().add(vmA);
            hosts.get(0).getGuestList().add(vmB);
            hosts.get(1).getGuestList().add(vmC);
            hosts.get(1).getGuestList().add(vmD);

            Log.println("Scenario: 3 hosts (H0=2000/4096, H1=1000/2048, H2=1000/2048 MIPS/RAM)");
            Log.println("          A(t=0.9,e1)@H0  B(t=0.8,e2)@H0  C(t=0.2,e3)@H1  D(t=0.2,e3)@H1");
            Log.println("          E(100 MIPS/3000 RAM) held out for the RAM-overflow test");
            Log.println();

            ObjectiveEvaluator evaluator =
                    new ObjectiveEvaluator(new ServerRiskModel(0.1, 0.3), 1.0, 1.0);

            // Candidate X: A→H0, B→H0, C→H1, D→H1 (matches live placement, 0 migrations)
            CandidatePlacement candX = new CandidatePlacement(mapOf(
                    entry(VM_A_ID, HOST_0_ID), entry(VM_B_ID, HOST_0_ID),
                    entry(VM_C_ID, HOST_1_ID), entry(VM_D_ID, HOST_1_ID)), hosts);

            // Candidate Y: A→H0, B→H1, C→H2, D→H2
            CandidatePlacement candY = new CandidatePlacement(mapOf(
                    entry(VM_A_ID, HOST_0_ID), entry(VM_B_ID, HOST_1_ID),
                    entry(VM_C_ID, HOST_2_ID), entry(VM_D_ID, HOST_2_ID)), hosts);

            // Candidate ALL0: everything on H0
            CandidatePlacement candAll0 = new CandidatePlacement(mapOf(
                    entry(VM_A_ID, HOST_0_ID), entry(VM_B_ID, HOST_0_ID),
                    entry(VM_C_ID, HOST_0_ID), entry(VM_D_ID, HOST_0_ID)), hosts);

            // Candidate V: two cross-entity pairs on two different hosts
            CandidatePlacement candV = new CandidatePlacement(mapOf(
                    entry(VM_A_ID, HOST_0_ID), entry(VM_C_ID, HOST_0_ID),
                    entry(VM_B_ID, HOST_1_ID), entry(VM_D_ID, HOST_1_ID)), hosts);

            ObjectiveEvaluator.EvaluationResult rx = evaluator.evaluate(candX, vms, threatMap);
            ObjectiveEvaluator.EvaluationResult ry = evaluator.evaluate(candY, vms, threatMap);
            ObjectiveEvaluator.EvaluationResult rAll0 = evaluator.evaluate(candAll0, vms, threatMap);
            ObjectiveEvaluator.EvaluationResult rv = evaluator.evaluate(candV, vms, threatMap);

            Log.println("Candidate X (A→H0,B→H0,C→H1,D→H1): " + rx.getObjectives());
            Log.println("Candidate Y (A→H0,B→H1,C→H2,D→H2): " + ry.getObjectives());
            Log.println("Candidate ALL0 (all→H0):           " + rAll0.getObjectives());
            Log.println("Candidate V (A,C→H0; B,D→H1):      " + rv.getObjectives());
            Log.println();

            // === Test 1: f1 changes with host assignment ===
            check("T1 f1 changes with host assignment (X vs Y)",
                    rx.isValid() && ry.isValid()
                            && diff(rx.getObjectives().getF1ThreatServerRisk(),
                                    ry.getObjectives().getF1ThreatServerRisk()) > 0);

            // === Test 2: f2 changes with co-residency ===
            check("T2 f2 changes with co-residency (X=0.9 vs Y=0.0)",
                    near(rx.getObjectives().getF2CoResidencyRisk(), 0.9)
                            && near(ry.getObjectives().getF2CoResidencyRisk(), 0.0));

            // === Test 3: same-entity pairs are excluded ===
            // Y has C+D (same entity) co-resident on H2, yet f2=0.
            // V has two cross-entity pairs summing to max(0.9,0.2)+max(0.8,0.2)=1.7.
            check("T3 same-entity pairs excluded (Y f2=0 with C+D co-resident; V f2=1.7)",
                    near(ry.getObjectives().getF2CoResidencyRisk(), 0.0)
                            && near(rv.getObjectives().getF2CoResidencyRisk(), 1.7));

            // === Test 4: migration count is correct ===
            // X matches live placement (0), Y moves B,C,D (3), ALL0 moves C,D (2).
            check("T4 migration count correct (X=0, Y=3, ALL0=2)",
                    rx.getMigrationCount() == 0
                            && ry.getMigrationCount() == 3
                            && rAll0.getMigrationCount() == 2);

            // === Test 5: active-host count is correct ===
            check("T5 active-host count correct (X=2, Y=3, ALL0=1)",
                    rx.getActiveHostCount() == 2
                            && ry.getActiveHostCount() == 3
                            && rAll0.getActiveHostCount() == 1);

            // === Test 6: f4 changes with placement ===
            check("T6 f4 changes with placement (X=0.625, Y=0.5625, ALL0=0.75)",
                    near(rx.getObjectives().getF4UnusedCapacity(), 0.625)
                            && near(ry.getObjectives().getF4UnusedCapacity(), 0.5625)
                            && near(rAll0.getObjectives().getF4UnusedCapacity(), 0.75));

            // === Test 7: invalid candidates are rejected ===
            runInvalidCandidateTests(evaluator, hosts, vms, threatMap, vmE);

            // === Test 8: live CloudSim state is unchanged ===
            Map<Integer, List<Integer>> fingerprintBefore = liveFingerprint(hosts);
            evaluator.evaluate(candX, vms, threatMap);
            evaluator.evaluate(candY, vms, threatMap);
            evaluator.evaluate(candAll0, vms, threatMap);
            Map<Integer, List<Integer>> fingerprintAfter = liveFingerprint(hosts);
            check("T8 live CloudSim state unchanged after evaluations",
                    fingerprintBefore.equals(fingerprintAfter));

            // === Test 9: A→B→A evaluation is deterministic ===
            ObjectiveEvaluator.EvaluationResult r1 = evaluator.evaluate(candX, vms, threatMap);
            ObjectiveEvaluator.EvaluationResult rMid = evaluator.evaluate(candY, vms, threatMap);
            ObjectiveEvaluator.EvaluationResult r2 = evaluator.evaluate(candX, vms, threatMap);
            check("T9 A→B→A deterministic (X then Y then X identical)",
                    r1.isValid() && r2.isValid() && rMid.isValid()
                            && objectivesIdentical(r1.getObjectives(), r2.getObjectives()));

            // === Test 10: normalization works ===
            runNormalizationTests(rx, ry);

            // === Report ===
            Log.println();
            Log.println("========================================");
            Log.println("STAGE 2F VALIDATION REPORT");
            Log.println("========================================");
            Log.println("Checks passed: " + checksPassed);
            Log.println("Checks failed: " + checksFailed);
            Log.println();

            if (checksFailed > 0) {
                Log.println("STAGE 2F VALIDATION FAILED");
                Log.println("========================================");
                System.exit(1);
            }

            Log.println("STAGE 2F VALIDATION PASSED");
            Log.println("HARD STOP: objective evaluation only. NSSAO NOT implemented.");
            Log.println("========================================");

        } catch (Exception e) {
            Log.println("FATAL ERROR in Stage 2F validation:");
            Log.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === Invalid candidate tests (part of Test 7) ===

    private static void runInvalidCandidateTests(ObjectiveEvaluator evaluator,
                                                 List<Host> hosts,
                                                 List<Vm> vms,
                                                 Map<Integer, VmThreatState> threatMap,
                                                 Vm vmE) {
        // (a) Missing VM assignment: D is not in the map
        CandidatePlacement missingD = new CandidatePlacement(mapOf(
                entry(VM_A_ID, HOST_0_ID), entry(VM_B_ID, HOST_0_ID),
                entry(VM_C_ID, HOST_1_ID)), hosts);
        List<Vm> vmsForMissing = Arrays.asList(vmA(vms), vmB(vms), vmC(vms), vmD(vms));
        boolean missingRejected = expectRejected(evaluator, missingD, vmsForMissing, threatMap,
                "Missing assignment");

        // (b) Duplicate VM assignment: VM A appears twice in the placement list
        List<Vm> duplicated = Arrays.asList(vmA(vms), vmA(vms), vmB(vms), vmC(vms), vmD(vms));
        CandidatePlacement dupCandidate = new CandidatePlacement(mapOf(
                entry(VM_A_ID, HOST_0_ID), entry(VM_B_ID, HOST_0_ID),
                entry(VM_C_ID, HOST_1_ID), entry(VM_D_ID, HOST_1_ID)), hosts);
        boolean duplicateRejected = expectRejected(evaluator, dupCandidate, duplicated, threatMap,
                "Duplicate VM assignment");

        // (c) Invalid host: A assigned to non-existent host 99
        CandidatePlacement invalidHost = new CandidatePlacement(mapOf(
                entry(VM_A_ID, 99), entry(VM_B_ID, HOST_0_ID),
                entry(VM_C_ID, HOST_1_ID), entry(VM_D_ID, HOST_1_ID)), hosts);
        boolean invalidHostRejected = expectRejected(evaluator, invalidHost, vms, threatMap,
                "Invalid host");

        // (d) CPU capacity exceeded: 4×500=2000 MIPS onto H1 (1000 MIPS)
        CandidatePlacement cpuOverflow = new CandidatePlacement(mapOf(
                entry(VM_A_ID, HOST_1_ID), entry(VM_B_ID, HOST_1_ID),
                entry(VM_C_ID, HOST_1_ID), entry(VM_D_ID, HOST_1_ID)), hosts);
        boolean cpuRejected = expectRejected(evaluator, cpuOverflow, vms, threatMap,
                "CPU capacity exceeded");

        // (e) RAM capacity exceeded: VM E (3000 RAM) onto H2 (2048 RAM);
        //     CPU (100 ≤ 1000) is fine so ONLY the RAM rule fires.
        CandidatePlacement ramOverflow = new CandidatePlacement(mapOf(
                entry(VM_E_ID, HOST_2_ID)), hosts);
        List<Vm> onlyE = Arrays.asList(vmE);
        boolean ramRejected = expectRejected(evaluator, ramOverflow, onlyE, threatMap,
                "RAM capacity exceeded");

        // Also verify invalid results expose NO objective values
        ObjectiveEvaluator.EvaluationResult bad = evaluator.evaluate(cpuOverflow, vms, threatMap);
        boolean noObjectivesWhenInvalid = !bad.isValid()
                && bad.getViolations().size() > 0
                && (bad.getObjectives() == null);

        check("T7 invalid candidates rejected "
                        + "(missing=" + missingRejected
                        + ", duplicate=" + duplicateRejected
                        + ", invalidHost=" + invalidHostRejected
                        + ", cpu=" + cpuRejected
                        + ", ram=" + ramRejected
                        + ", noObjectives=" + noObjectivesWhenInvalid + ")",
                missingRejected && duplicateRejected && invalidHostRejected
                        && cpuRejected && ramRejected && noObjectivesWhenInvalid);
    }

    private static boolean expectRejected(ObjectiveEvaluator evaluator,
                                          CandidatePlacement candidate,
                                          List<Vm> vms,
                                          Map<Integer, VmThreatState> threatMap,
                                          String expectedViolationSubstring) {
        ObjectiveEvaluator.EvaluationResult result = evaluator.evaluate(candidate, vms, threatMap);
        if (result.isValid()) {
            Log.println("    [reject] EXPECTED REJECTION but candidate was accepted ("
                    + expectedViolationSubstring + "): " + candidate);
            return false;
        }
        for (String violation : result.getViolations()) {
            if (violation.contains(expectedViolationSubstring)) {
                Log.println("    [reject] " + expectedViolationSubstring + " -> " + violation);
                return true;
            }
        }
        Log.println("    [reject] rejected but with unexpected reason(s): " + result.getViolations());
        return false;
    }

    // === Normalization tests (Tests 10 and 11) ===

    private static void runNormalizationTests(ObjectiveEvaluator.EvaluationResult rx,
                                              ObjectiveEvaluator.EvaluationResult ry) {
        // Basic min-max
        boolean basic = near(ObjectiveNormalizer.normalize(5.0, 0.0, 10.0), 0.5)
                && near(ObjectiveNormalizer.normalize(0.0, 0.0, 10.0), 0.0)
                && near(ObjectiveNormalizer.normalize(10.0, 0.0, 10.0), 1.0)
                && near(ObjectiveNormalizer.normalize(2.5, 0.0, 10.0), 0.25);

        // Across-candidate normalization: f1(X) is the max of the two → 1.0,
        // f1(Y) is the min → 0.0
        double[] f1Values = {
                rx.getObjectives().getF1ThreatServerRisk(),
                ry.getObjectives().getF1ThreatServerRisk()
        };
        boolean across = near(ObjectiveNormalizer.normalizeAcross(f1Values, 0), 1.0)
                && near(ObjectiveNormalizer.normalizeAcross(f1Values, 1), 0.0);

        // Out-of-range inputs clamp into [0,1]
        boolean clamped = near(ObjectiveNormalizer.normalize(-3.0, 0.0, 10.0), 0.0)
                && near(ObjectiveNormalizer.normalize(15.0, 0.0, 10.0), 1.0);

        check("T10 normalization works (basic=" + basic
                        + ", across=" + across + ", clamped=" + clamped + ")",
                basic && across && clamped);

        // Equal-value normalization returns 0.5
        boolean equalScalar = near(ObjectiveNormalizer.normalize(7.0, 7.0, 7.0), 0.5);
        boolean equalSet = near(ObjectiveNormalizer.normalizeAcross(
                new double[]{0.42, 0.42, 0.42}, 1), 0.5);

        check("T11 equal-value normalization returns 0.5 (scalar=" + equalScalar
                        + ", set=" + equalSet + ")",
                equalScalar && equalSet);
    }

    // === Helpers ===

    private static Map<Integer, List<Integer>> liveFingerprint(List<Host> hosts) {
        Map<Integer, List<Integer>> fingerprint = new TreeMap<>();
        for (Host host : hosts) {
            List<Integer> vmIds = new ArrayList<>();
            for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    vmIds.add(vm.getId());
                }
            }
            vmIds.sort(Integer::compareTo);
            fingerprint.put(host.getId(), vmIds);
        }
        return fingerprint;
    }

    private static boolean objectivesIdentical(ObjectiveValues a, ObjectiveValues b) {
        return Double.compare(a.getF1ThreatServerRisk(), b.getF1ThreatServerRisk()) == 0
                && Double.compare(a.getF2CoResidencyRisk(), b.getF2CoResidencyRisk()) == 0
                && Double.compare(a.getF3Cost(), b.getF3Cost()) == 0
                && Double.compare(a.getF4UnusedCapacity(), b.getF4UnusedCapacity()) == 0;
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            checksPassed++;
            Log.println("  PASS  " + label);
        } else {
            checksFailed++;
            Log.println("  FAIL  " + label);
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPS;
    }

    private static double diff(double a, double b) {
        return Math.abs(a - b);
    }

    private static Vm vmA(List<Vm> vms) {
        return findById(vms, VM_A_ID);
    }

    private static Vm vmB(List<Vm> vms) {
        return findById(vms, VM_B_ID);
    }

    private static Vm vmC(List<Vm> vms) {
        return findById(vms, VM_C_ID);
    }

    private static Vm vmD(List<Vm> vms) {
        return findById(vms, VM_D_ID);
    }

    private static Vm findById(List<Vm> vms, int id) {
        for (Vm vm : vms) {
            if (vm.getId() == id) {
                return vm;
            }
        }
        throw new IllegalArgumentException("VM not found in list: " + id);
    }

    // === Scenario construction (Stage 2D conventions) ===

    private static List<Host> buildHosts() {
        List<Host> hosts = new ArrayList<>();
        // Heterogeneous capacities: f4 must be placement-sensitive
        hosts.add(createHost(HOST_0_ID, 4096, 1000000, 1000000, 2000));
        hosts.add(createHost(HOST_1_ID, 2048, 1000000, 1000000, 1000));
        hosts.add(createHost(HOST_2_ID, 2048, 1000000, 1000000, 1000));
        return hosts;
    }

    private static Host createHost(int hostId, int ram, long storage, long bw, int mips) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(new PeProvisionerSimple(mips)));
        return new Host(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw),
                storage,
                peList,
                new VmSchedulerTimeShared(peList)
        );
    }

    private static Vm createVm(int vmId, int mips, int ram) {
        return new Vm(vmId, 0, mips, 1, ram, 1000L, 1000L, "Xen",
                new CloudletSchedulerTimeShared());
    }

    private static Map<Integer, Integer> mapOf(Entry... entries) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            map.put(e.vmId, e.hostId);
        }
        return map;
    }

    private static Entry entry(int vmId, int hostId) {
        return new Entry(vmId, hostId);
    }

    private record Entry(int vmId, int hostId) {
    }
}
