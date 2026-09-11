package org.cloudbus.cloudsim.examples.stage2d;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.examples.stage2c.*;

import java.util.*;

/**
 * Stage 2D — VM Migration + Co-residency Duration Validation.
 *
 * <p>Purpose: implement minimum machinery to model VM migration and
 * measure co-residency duration in the CloudSim simulation.
 *
 * <p>Multi-snapshot validation scenario (deterministic):
 * <pre>
 * t=0:
 *   Host 0 (ID=0): VM A (high threat, entity-1) + VM B (normal, entity-2)
 *   Host 1 (ID=1): VM C (entity-3) + VM D (entity-4)
 *
 * t=100: VM B migrates Host 0 -> Host 1
 *   Host 0: VM A (alone)
 *   Host 1: VM B + VM C + VM D
 *
 * t=200: VM B migrates Host 1 -> Host 0
 *   Host 0: VM A + VM B
 *   Host 1: VM C + VM D
 * </pre>
 *
 * <p>Expected co-residency episodes:
 * <ul>
 *   <li>A-B on Host 0: t=0 -> t=100, duration=100</li>
 *   <li>B-C on Host 1: t=100 -> t=200, duration=100</li>
 *   <li>B-D on Host 1: t=100 -> t=200, duration=100</li>
 *   <li>C-D on Host 1: t=0 -> t=200, duration=200 (continuous)</li>
 *   <li>A-B on Host 0: t=200 -> t=200 (still present at end, ongoing)</li>
 * </ul>
 *
 * <p>Same-pair multiple episodes test:
 * <ul>
 *   <li>A-B has TWO separate episodes on Host 0:
 *       <ul>
 *         <li>Episode 1: t=0 -> t=100, duration=100</li>
 *         <li>Episode 2: t=200 -> t=200 (ongoing at simulation end)</li>
 *       </ul>
 *   </li>
 *   <li>These must NOT be merged into a single episode.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   java -cp "cloudsim/target/cloudsim-7.0.1.jar:cloudsim-examples/target/classes" \
 *     org.cloudbus.cloudsim.examples.stage2d.Stage2dMigrationValidation
 * </pre>
 */
public class Stage2dMigrationValidation {

    private static final double HIGH_THREAT_THRESHOLD = 0.5; // TESTING ONLY
    private static final double SIMULATION_TIME_0 = 0.0;
    private static final double SIMULATION_TIME_1 = 100.0; // After first migration
    private static final double SIMULATION_TIME_2 = 200.0; // After second migration

    // VM IDs
    private static final int VM_A_ID = 10;
    private static final int VM_B_ID = 11;
    private static final int VM_C_ID = 12;
    private static final int VM_D_ID = 13;

    // Host IDs
    private static final int HOST_0_ID = 0;
    private static final int HOST_1_ID = 1;

    public static void main(String[] args) {
        Log.println("========================================");
        Log.println("STAGE 2D VALIDATION");
        Log.println("VM Migration + Co-residency Duration");
        Log.println("========================================");
        Log.println();
        Log.println("TEMPORARY HIGH-TREAT THREAT THRESHOLD: " + HIGH_THREAT_THRESHOLD);
        Log.println("TESTING ONLY — NOT our final adaptive threshold.");
        Log.println("NOT 0.6. NOT adaptive. Just for testing Stage 2D machinery.");
        Log.println();

        try {
            // === Step 1: Build initial placement ===
            Log.println("Step 1: Building initial placement (t=0)...");
            List<Host> hosts = new ArrayList<>();

            Map<Integer, VmThreatState> vmThreatStateMap = new LinkedHashMap<>();
            List<Vm> vms = new ArrayList<>();

            // Create hosts with explicit IDs
            Host host0 = createHost(HOST_0_ID, 4096, 1000000, 1000000, 3000);
            Host host1 = createHost(HOST_1_ID, 4096, 1000000, 1000000, 3000);
            hosts.add(host0);
            hosts.add(host1);

            Log.println("  Created hosts with IDs: Host 0 ID=" + HOST_0_ID + ", Host 1 ID=" + HOST_1_ID);

            // Host 0: VM A (high threat, entity-1) + VM B (normal, entity-2)
            Vm vmA = createVm(VM_A_ID, 0, 500.0, 256);
            VmThreatState stateA = new VmThreatState(VM_A_ID, 0.85,
                    "azure_v2", "deployment", "entity-1-high", HIGH_THREAT_THRESHOLD);
            vms.add(vmA);
            vmThreatStateMap.put(VM_A_ID, stateA);
            host0.getGuestList().add(vmA);

            Vm vmB = createVm(VM_B_ID, 0, 500.0, 256);
            VmThreatState stateB = new VmThreatState(VM_B_ID, 0.20,
                    "azure_v2", "deployment", "entity-2-normal", HIGH_THREAT_THRESHOLD);
            vms.add(vmB);
            vmThreatStateMap.put(VM_B_ID, stateB);
            host0.getGuestList().add(vmB);

            // Host 1: VM C (entity-3) + VM D (entity-4)
            Vm vmC = createVm(VM_C_ID, 0, 500.0, 256);
            VmThreatState stateC = new VmThreatState(VM_C_ID, 0.30,
                    "azure_v2", "deployment", "entity-3", HIGH_THREAT_THRESHOLD);
            vms.add(vmC);
            vmThreatStateMap.put(VM_C_ID, stateC);
            host1.getGuestList().add(vmC);

            Vm vmD = createVm(VM_D_ID, 0, 500.0, 256);
            VmThreatState stateD = new VmThreatState(VM_D_ID, 0.10,
                    "azure_v2", "deployment", "entity-4", HIGH_THREAT_THRESHOLD);
            vms.add(vmD);
            vmThreatStateMap.put(VM_D_ID, stateD);
            host1.getGuestList().add(vmD);

            Log.println("  VM A (id=" + VM_A_ID + "): threat=0.85, entity-1-high, HIGH THREAT, on host " + HOST_0_ID);
            Log.println("  VM B (id=" + VM_B_ID + "): threat=0.20, entity-2-normal, normal, on host " + HOST_0_ID);
            Log.println("  VM C (id=" + VM_C_ID + "): threat=0.30, entity-3, normal, on host " + HOST_1_ID);
            Log.println("  VM D (id=" + VM_D_ID + "): threat=0.10, entity-4, normal, on host " + HOST_1_ID);

            // === Step 2: Record initial snapshot (t=0) ===
            Log.println();
            Log.println("Step 2: Recording initial snapshot (t=0)...");

            CoResidencyDurationTracker durationTracker = new CoResidencyDurationTracker(
                    vmThreatStateMap, HIGH_THREAT_THRESHOLD);
            durationTracker.recordSnapshot(hosts, SIMULATION_TIME_0);

            Log.println("  Snapshot 0: " + durationTracker.getSnapshots().get(0));
            Log.println("  Pairs at t=0:");
            for (var pair : durationTracker.getSnapshots().get(0).getPairs()) {
                Log.println("    " + pair);
            }

            // === Step 3: Print pre-migration security state ===
            Log.println();
            Log.println("Pre-migration security state (t=0):");
            SecurityStateSnapshot preSnapshot = new SecurityStateSnapshot(
                    hosts, vmThreatStateMap,
                    new ServerRiskModel(0.1, 0.3),
                    new DefensiveCostModel(1.0, 0.1),
                    HIGH_THREAT_THRESHOLD);
            preSnapshot.printSummary("PRE-MIGRATION (t=0)");

            // === Step 4: First migration (t=100): VM B: Host 0 -> Host 1 ===
            Log.println();
            Log.println("Step 3: Migration 1 (VM B: Host 0 -> Host 1 at t=100)...");

            VmMigrationService migrationService = VmMigrationService.create(vmThreatStateMap);
            MigrationEvent event1 = migrationService.migrateVmById(
                    vmB, hosts, HOST_0_ID, HOST_1_ID, SIMULATION_TIME_1);

            Log.println("  Migration event 1: " + event1);

            // Record snapshot after migration 1
            durationTracker.recordSnapshot(hosts, SIMULATION_TIME_1);

            Log.println("  Snapshot 1: " + durationTracker.getSnapshots().get(1));
            Log.println("  Pairs at t=100:");
            for (var pair : durationTracker.getSnapshots().get(1).getPairs()) {
                Log.println("    " + pair);
            }

            // === VALIDATE MIGRATION 1 STATE IMMEDIATELY ===
            Log.println();
            Log.println("=== Validating Migration 1 state (t=100) ===");
            
            boolean allPassed = true;

            // [C] After migration 1: VM B has moved from Host 0 to Host 1
            boolean afterM1Host0NoLongerHasVmB = !VmMigrationService.hostContainsVm(hosts.get(0), VM_B_ID);
            boolean afterM1Host1HasVmB = VmMigrationService.hostContainsVm(hosts.get(1), VM_B_ID);
            Log.println("  [C] After M1 (t=100): Host 0 no longer has VM B: "
                    + (afterM1Host0NoLongerHasVmB ? "PASS" : "FAIL"));
            allPassed &= afterM1Host0NoLongerHasVmB;

            // [D] After migration 1: VM B is now on Host 1
            Log.println("  [D] After M1 (t=100): Host 1 now has VM B: "
                    + (afterM1Host1HasVmB ? "PASS" : "FAIL"));
            allPassed &= afterM1Host1HasVmB;

            // [G] After migration 1: A-B co-residency on Host 0 should NOT exist (VM B moved to Host 1)
            boolean noAbOnHost0AfterM1 = !hostHasCrossEntityPair(
                    hosts.get(0), VM_A_ID, VM_B_ID, vmThreatStateMap);
            Log.println("  [G] After M1 (t=100): A-B co-residency on Host 0 absent: "
                    + (noAbOnHost0AfterM1 ? "PASS" : "FAIL"));
            allPassed &= noAbOnHost0AfterM1;

            // [H] After migration 1: B-C co-residency should exist on Host 1 (VM B now on Host 1 with C)
            boolean hasBcCohostAfterM1 = hostHasCrossEntityPair(
                    hosts.get(1), VM_B_ID, VM_C_ID, vmThreatStateMap);
            Log.println("  [H] After M1 (t=100): B-C co-residency on Host 1 present: "
                    + (hasBcCohostAfterM1 ? "PASS" : "FAIL"));
            allPassed &= hasBcCohostAfterM1;

            // === Step 5: Second migration (t=200): VM B: Host 1 -> Host 0 ===
            Log.println();
            Log.println("Step 4: Migration 2 (VM B: Host 1 -> Host 0 at t=200)...");

            MigrationEvent event2 = migrationService.migrateVmById(
                    vmB, hosts, HOST_1_ID, HOST_0_ID, SIMULATION_TIME_2);

            Log.println("  Migration event 2: " + event2);

            // Record snapshot after migration 2
            durationTracker.recordSnapshot(hosts, SIMULATION_TIME_2);

            Log.println("  Snapshot 2: " + durationTracker.getSnapshots().get(2));
            Log.println("  Pairs at t=200:");
            for (var pair : durationTracker.getSnapshots().get(2).getPairs()) {
                Log.println("    " + pair);
            }

            // === Step 6: Print post-migration security state ===
            Log.println();
            Log.println("Post-migration security state (t=200):");
            SecurityStateSnapshot postSnapshot = new SecurityStateSnapshot(
                    hosts, vmThreatStateMap,
                    new ServerRiskModel(0.1, 0.3),
                    new DefensiveCostModel(1.0, 0.1),
                    HIGH_THREAT_THRESHOLD,
                    durationTracker,
                    migrationService,
                    new MigrationCostModel(1.0),
                    durationTracker.getTotalCoResidencyDuration(),
                    durationTracker.getTotalHighThreatCoResidencyDuration());
            postSnapshot.printSummary("POST-MIGRATION (t=200)");

            // === Step 7: Final validation checks ===
            Log.println();
            Log.println("========================================");
            Log.println("FINAL VALIDATION CHECKS");
            Log.println("========================================");

            // [A] VM B's threat score unchanged
            boolean threatPreserved = stateB.getThreatScore() == 0.20;
            Log.println("  [A] VM B threat score unchanged (0.20): "
                    + (threatPreserved ? "PASS" : "FAIL"));
            allPassed &= threatPreserved;

            // [B] VM B's entity metadata unchanged
            boolean entityPreserved = "entity-2-normal".equals(stateB.getEntityId());
            Log.println("  [B] VM B entity ID unchanged (entity-2-normal): "
                    + (entityPreserved ? "PASS" : "FAIL"));
            allPassed &= entityPreserved;

            // [E] After migration 2: VM B returns to Host 0
            boolean vmBBackOnHost0 = VmMigrationService.hostContainsVm(hosts.get(0), VM_B_ID);
            Log.println("  [E] After M2 (t=200): VM B returns to Host 0: "
                    + (vmBBackOnHost0 ? "PASS" : "FAIL"));
            allPassed &= vmBBackOnHost0;

            // [F] Migration count = 2
            boolean migrationCountCorrect = migrationService.getMigrationCount() == 2;
            Log.println("  [F] Migration count = 2: "
                    + (migrationCountCorrect ? "PASS" : "FAIL"));
            allPassed &= migrationCountCorrect;

            // [I] After migration 2: A-B co-residency on Host 0 should exist again
            boolean abOnHost0AfterM2 = hostHasCrossEntityPair(
                    hosts.get(0), VM_A_ID, VM_B_ID, vmThreatStateMap);
            Log.println("  [I] After M2 (t=200): A-B co-residency on Host 0 present again: "
                    + (abOnHost0AfterM2 ? "PASS" : "FAIL"));
            allPassed &= abOnHost0AfterM2;

            // [J] Resource utilization after migration 2
            // Host 0: VM A (500) + VM B (500) = 1000 MIPS / 3000 = 0.3333
            // Host 1: VM C (500) + VM D (500) = 1000 MIPS / 3000 = 0.3333
            double host0CpuAfterM2 = ResourceUtilizationCalculator.calculateCpuUtilization(hosts.get(0));
            double host1CpuAfterM2 = ResourceUtilizationCalculator.calculateCpuUtilization(hosts.get(1));
            boolean utilizationCorrect = Math.abs(host0CpuAfterM2 - 1000.0/3000.0) < 1e-9
                    && Math.abs(host1CpuAfterM2 - 1000.0/3000.0) < 1e-9;
            Log.println("  [J] Resource utilization after M2 correct: "
                    + (utilizationCorrect ? "PASS" : "FAIL"));
            Log.println("      Host 0 CPU: " + host0CpuAfterM2 + " (expected " + (1000.0/3000.0) + ")");
            Log.println("      Host 1 CPU: " + host1CpuAfterM2 + " (expected " + (1000.0/3000.0) + ")");
            allPassed &= utilizationCorrect;

            // [K] No unrelated VM metadata changes
            boolean noUnrelatedChanges = stateA.getThreatScore() == 0.85
                    && stateC.getThreatScore() == 0.30
                    && stateD.getThreatScore() == 0.10;
            Log.println("  [K] No unrelated VM metadata changes: "
                    + (noUnrelatedChanges ? "PASS" : "FAIL"));
            allPassed &= noUnrelatedChanges;

            // [L] Co-residency episodes detected (>=4 unique episodes)
            List<CoResidencyDurationTracker.CoResidencyEpisode> episodes =
                    durationTracker.getEpisodes();
            Set<String> uniqueEpisodeKeys = new HashSet<>();
            for (var ep : episodes) {
                uniqueEpisodeKeys.add(ep.getVmId1() + "-" + ep.getVmId2() + "-host" + ep.getHostId() + "-start" + ep.getStartTime());
            }
            boolean episodesDetected = uniqueEpisodeKeys.size() >= 4;
            Log.println("  [L] Co-residency episodes detected (>=4 unique): "
                    + uniqueEpisodeKeys.size() + " unique episodes: "
                    + (episodesDetected ? "PASS" : "FAIL"));
            allPassed &= episodesDetected;

            // [M] A-B episode 1 on Host 0: start=0, end=100, duration=100
            CoResidencyDurationTracker.CoResidencyEpisode abEpisode1 = findEpisode(
                    episodes, VM_A_ID, VM_B_ID, HOST_0_ID, 0.0);
            if (abEpisode1 == null) {
                // Search for any A-B episode on Host 0 with start=0
                for (var ep : episodes) {
                    if (ep.getVmId1() == VM_A_ID && ep.getVmId2() == VM_B_ID
                            && ep.getHostId() == HOST_0_ID
                            && Math.abs(ep.getStartTime() - 0.0) < 1e-9) {
                        abEpisode1 = ep;
                        break;
                    }
                }
            }
            boolean abEpisode1Correct = abEpisode1 != null
                    && Math.abs(abEpisode1.getStartTime() - 0.0) < 1e-9
                    && Math.abs(abEpisode1.getEndTime() - 100.0) < 1e-9
                    && Math.abs(abEpisode1.getDuration() - 100.0) < 1e-9;
            Log.println("  [M] A-B episode 1 on Host 0: start=0, end=100, duration=100: "
                    + (abEpisode1Correct ? "PASS" : "FAIL"));
            Log.println("      Found: " + (abEpisode1 != null ? abEpisode1 : "NONE"));
            allPassed &= abEpisode1Correct;

            // [N] A-B episode 2 on Host 0: start=200, end=200 (ongoing)
            CoResidencyDurationTracker.CoResidencyEpisode abEpisode2 = findEpisode(
                    episodes, VM_A_ID, VM_B_ID, HOST_0_ID, 200.0);
            if (abEpisode2 == null) {
                for (var ep : episodes) {
                    if (ep.getVmId1() == VM_A_ID && ep.getVmId2() == VM_B_ID
                            && ep.getHostId() == HOST_0_ID
                            && Math.abs(ep.getStartTime() - 200.0) < 1e-9) {
                        abEpisode2 = ep;
                        break;
                    }
                }
            }
            boolean abEpisode2Correct = abEpisode2 != null
                    && Math.abs(abEpisode2.getStartTime() - 200.0) < 1e-9
                    && Math.abs(abEpisode2.getEndTime() - 200.0) < 1e-9;
            Log.println("  [N] A-B episode 2 on Host 0: start=200, end=200 (ongoing): "
                    + (abEpisode2Correct ? "PASS" : "FAIL"));
            Log.println("      Found: " + (abEpisode2 != null ? abEpisode2 : "NONE"));
            allPassed &= abEpisode2Correct;

            // [O] Multiple episodes for same pair (A-B) — must have exactly 2 unique episodes
            long abUniqueEpisodesLong = uniqueEpisodeKeys.stream()
                    .filter(key -> key.startsWith(VM_A_ID + "-" + VM_B_ID + "-host" + HOST_0_ID))
                    .count();
            int abUniqueEpisodes = (int) abUniqueEpisodesLong;
            Log.println("  [O] A-B has exactly 2 unique episodes on Host 0: "
                    + abUniqueEpisodes + " (expected 2): "
                    + (abUniqueEpisodes == 2 ? "PASS" : "FAIL"));
            Log.println("      Unique episode keys for A-B on Host 0:");
            for (var key : uniqueEpisodeKeys) {
                if (key.startsWith(VM_A_ID + "-" + VM_B_ID + "-host" + HOST_0_ID)) {
                    Log.println("        " + key);
                }
            }
            allPassed &= (abUniqueEpisodes == 2);

            // [P] B-C episode on Host 1: start=100, end=200, duration=100
            CoResidencyDurationTracker.CoResidencyEpisode bcEpisode = findEpisode(
                    episodes, VM_B_ID, VM_C_ID, HOST_1_ID, 100.0);
            if (bcEpisode == null) {
                // Search for any B-C or C-B episode on Host 1 with start=100
                for (var ep : episodes) {
                    if ((ep.getVmId1() == VM_B_ID && ep.getVmId2() == VM_C_ID
                            || ep.getVmId1() == VM_C_ID && ep.getVmId2() == VM_B_ID)
                            && ep.getHostId() == HOST_1_ID
                            && Math.abs(ep.getStartTime() - 100.0) < 1e-9) {
                        bcEpisode = ep;
                        break;
                    }
                }
            }
            boolean bcEpisodeCorrect = bcEpisode != null
                    && Math.abs(bcEpisode.getStartTime() - 100.0) < 1e-9
                    && Math.abs(bcEpisode.getEndTime() - 200.0) < 1e-9
                    && Math.abs(bcEpisode.getDuration() - 100.0) < 1e-9;
            Log.println("  [P] B-C episode on Host 1: start=100, end=200, duration=100: "
                    + (bcEpisodeCorrect ? "PASS" : "FAIL"));
            Log.println("      Found: " + (bcEpisode != null ? bcEpisode : "NONE"));
            allPassed &= bcEpisodeCorrect;

            // [Q] C-D episode on Host 1: start=0, end=200, duration=200
            // GENUINE DURATION ASSERTION — C-D pair is continuously co-resident
            // on Host 1 from t=0 through t=200 (all snapshots).
            // This must produce ONE episode: start=0.0, end=200.0, duration=200.0
            CoResidencyDurationTracker.CoResidencyEpisode cdEpisode = findEpisode(
                    episodes, VM_C_ID, VM_D_ID, HOST_1_ID, 0.0);
            if (cdEpisode == null) {
                // Search for any C-D or D-C episode on Host 1 with start=0
                for (var ep : episodes) {
                    if ((ep.getVmId1() == VM_C_ID && ep.getVmId2() == VM_D_ID
                            || ep.getVmId1() == VM_D_ID && ep.getVmId2() == VM_C_ID)
                            && ep.getHostId() == HOST_1_ID
                            && Math.abs(ep.getStartTime() - 0.0) < 1e-9) {
                        cdEpisode = ep;
                        break;
                    }
                }
            }
            boolean cdEpisodeCorrect = cdEpisode != null
                    && Math.abs(cdEpisode.getStartTime() - 0.0) < 1e-9
                    && Math.abs(cdEpisode.getEndTime() - 200.0) < 1e-9
                    && Math.abs(cdEpisode.getDuration() - 200.0) < 1e-9;
            Log.println("  [Q] C-D episode on Host 1: start=0.0, end=200.0, duration=200.0: "
                    + (cdEpisodeCorrect ? "PASS" : "FAIL"));
            Log.println("      Found: " + (cdEpisode != null ? cdEpisode : "NONE"));
            allPassed &= cdEpisodeCorrect;

            // [R] Same-host migration rejected
            boolean sameHostRejected = false;
            try {
                migrationService.migrateVmById(vmA, hosts, HOST_0_ID, HOST_0_ID, SIMULATION_TIME_2 + 1);
            } catch (IllegalArgumentException e) {
                sameHostRejected = e.getMessage().contains("Same-host");
            }
            Log.println("  [R] Same-host migration rejected: "
                    + (sameHostRejected ? "PASS" : "FAIL"));
            allPassed &= sameHostRejected;

            // [T] Invalid-host migration rejected
            boolean invalidHostRejected = false;
            try {
                migrationService.migrateVmById(vmA, hosts, HOST_0_ID, 999, SIMULATION_TIME_2 + 2);
            } catch (IllegalArgumentException e) {
                invalidHostRejected = e.getMessage().contains("Destination host not found");
            }
            // Verify VM placement unchanged after failed migration
            boolean vmPlacementUnchanged = VmMigrationService.hostContainsVm(hosts.get(0), VM_A_ID)
                    && !VmMigrationService.hostContainsVm(hosts.get(1), VM_A_ID);
            boolean noFakeEvent = migrationService.getMigrationCount() == 2; // Should still be 2
            Log.println("  [T] Invalid-host migration rejected: "
                    + (invalidHostRejected ? "PASS" : "FAIL"));
            Log.println("      VM placement unchanged: " + (vmPlacementUnchanged ? "PASS" : "FAIL"));
            Log.println("      No fake migration event: " + (noFakeEvent ? "PASS" : "FAIL"));
            allPassed &= invalidHostRejected && vmPlacementUnchanged && noFakeEvent;

            // [U] Migration cost assertion
            MigrationCostModel costModel = new MigrationCostModel(1.0);
            double totalMigrationCost = costModel.calculateTotalCostFromService(migrationService);
            int migrationCount = migrationService.getMigrationCount();
            double expectedCost = 2.0; // 2 migrations * 1.0 per migration
            boolean costCorrect = Math.abs(totalMigrationCost - expectedCost) < 1e-9
                    && migrationCount == 2;
            Log.println("  [U] Migration cost: " + totalMigrationCost + " (expected " + expectedCost + "): "
                    + (costCorrect ? "PASS" : "FAIL"));
            Log.println("      Migration count: " + migrationCount + " (expected 2)");
            Log.println("      Per-migration cost: 1.0 (SIMULATION ABSTRACTION, not real cloud cost)");
            allPassed &= costCorrect;

            // [S] Deterministic: run again and compare
            Log.println();
            Log.println("Step 5: Determinism test — rerunning scenario...");
            CoResidencyDurationTracker tracker2 = new CoResidencyDurationTracker(
                    vmThreatStateMap, HIGH_THREAT_THRESHOLD);
            tracker2.recordSnapshot(hosts, SIMULATION_TIME_0);
            VmMigrationService migrationService2 = VmMigrationService.create(vmThreatStateMap);
            migrationService2.migrateVmById(vmB, hosts, HOST_0_ID, HOST_1_ID, SIMULATION_TIME_1);
            tracker2.recordSnapshot(hosts, SIMULATION_TIME_1);
            migrationService2.migrateVmById(vmB, hosts, HOST_1_ID, HOST_0_ID, SIMULATION_TIME_2);
            tracker2.recordSnapshot(hosts, SIMULATION_TIME_2);

            Set<String> uniqueKeys2 = new HashSet<>();
            for (var ep : tracker2.getEpisodes()) {
                uniqueKeys2.add(ep.getVmId1() + "-" + ep.getVmId2() + "-host" + ep.getHostId() + "-start" + ep.getStartTime());
            }
            boolean determinismCorrect = uniqueEpisodeKeys.size() == uniqueKeys2.size();
            Log.println("  [S] Deterministic results (same unique episode count both runs): "
                    + uniqueEpisodeKeys.size() + " vs " + uniqueKeys2.size()
                    + " — " + (determinismCorrect ? "PASS" : "FAIL"));
            allPassed &= determinismCorrect;

            // === Step 8: Final report ===
            Log.println();
            Log.println("========================================");
            Log.println("STAGE 2D VALIDATION REPORT");
            Log.println("========================================");
            Log.println("Scenario: VM B migrates Host 0->1 at t=100, then Host 1->0 at t=200");
            Log.println("VMs: 4 (VM A: high threat, VM B/C/D: normal)");
            Log.println("Hosts: 2");
            Log.println("High-threat threshold: " + HIGH_THREAT_THRESHOLD + " (TESTING ONLY)");
            Log.println("Snapshots: " + durationTracker.getSnapshots().size());
            Log.println("  t=0: " + durationTracker.getSnapshots().get(0));
            Log.println("  t=100: " + durationTracker.getSnapshots().get(1));
            Log.println("  t=200: " + durationTracker.getSnapshots().get(2));
            Log.println("Migration events: " + migrationService.getMigrationCount());
            Log.println("Co-residency episodes (total): " + episodes.size());
            Log.println("Co-residency episodes (unique): " + uniqueEpisodeKeys.size());
            Log.println("  Unique episode keys:");
            for (var key : uniqueEpisodeKeys) {
                Log.println("    " + key);
            }
            Log.println("  A-B episode 1 (start=0): " + (abEpisode1 != null ? abEpisode1.getDuration() : "NONE"));
            Log.println("  A-B episode 2 (start=200): " + (abEpisode2 != null ? abEpisode2.toString() : "NONE"));
            Log.println("  B-C episode (start=100): " + (bcEpisode != null ? bcEpisode.getDuration() : "NONE"));
            Log.println("  C-D episode on Host 1 (start=0, duration=200): " + (cdEpisode != null ? cdEpisode.getDuration() : "NONE"));
            Log.println("  Episode types:");
            Log.println("    NORMAL_NORMAL: " + durationTracker.getEpisodesByType(CoResidencyDurationTracker.CoResidencyType.NORMAL_NORMAL).size());
            Log.println("    HIGH_THREAT_NORMAL: " + durationTracker.getEpisodesByType(CoResidencyDurationTracker.CoResidencyType.HIGH_THREAT_NORMAL).size());
            Log.println("    HIGH_THREAT_HIGH_THREAT: " + durationTracker.getEpisodesByType(CoResidencyDurationTracker.CoResidencyType.HIGH_THREAT_HIGH_THREAT).size());
            Log.println("Total co-residency duration: " + durationTracker.getTotalCoResidencyDuration());
            Log.println("Total high-threat co-residency duration: " + durationTracker.getTotalHighThreatCoResidencyDuration());
            Log.println("Total normal co-residency duration: " + durationTracker.getTotalNormalCoResidencyDuration());
            Log.println("All checks passed: " + allPassed);
            Log.println();

            if (!allPassed) {
                Log.println("STAGE 2D VALIDATION FAILED");
                System.exit(1);
            }

            Log.println("STAGE 2D VALIDATION PASSED");
            Log.println("========================================");

        } catch (Exception e) {
            Log.println("FATAL ERROR in Stage 2D validation:");
            Log.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === Helper methods ===

    private static Host createHost(int hostId, int ram, long storage,
                                   long bw, int mips) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(new PeProvisionerSimple(mips)));
        return new Host(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw),
                storage,
                peList,
                new VmSchedulerTimeShared(peList));
    }

    private static Vm createVm(int vmId, int hostId, double mips, int ram) {
        return new Vm(vmId, hostId, mips, 1, ram, 1000L, 1000L,
                "Xen", new CloudletSchedulerTimeShared());
    }

    private static boolean hostHasCrossEntityPair(Host host, int vmId1, int vmId2,
                                                   Map<Integer, VmThreatState> vmThreatStateMap) {
        VmThreatState state1 = vmThreatStateMap.get(vmId1);
        VmThreatState state2 = vmThreatStateMap.get(vmId2);
        if (state1 == null || state2 == null) return false;
        return state1.isDifferentEntityFrom(state2)
                && VmMigrationService.hostContainsVm(host, vmId1)
                && VmMigrationService.hostContainsVm(host, vmId2);
    }

    private static CoResidencyDurationTracker.CoResidencyEpisode findEpisode(
            List<CoResidencyDurationTracker.CoResidencyEpisode> episodes,
            int vmId1, int vmId2, int hostId, double expectedStartTime) {
        for (var ep : episodes) {
            if (ep.getVmId1() == vmId1 && ep.getVmId2() == vmId2
                    && ep.getHostId() == hostId
                    && Math.abs(ep.getStartTime() - expectedStartTime) < 1e-9) {
                return ep;
            }
            // Also check reverse order
            if (ep.getVmId1() == vmId2 && ep.getVmId2() == vmId1
                    && ep.getHostId() == hostId
                    && Math.abs(ep.getStartTime() - expectedStartTime) < 1e-9) {
                return ep;
            }
        }
        return null;
    }
}
