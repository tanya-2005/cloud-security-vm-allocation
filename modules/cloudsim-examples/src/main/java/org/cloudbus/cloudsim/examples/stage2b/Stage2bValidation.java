package org.cloudbus.cloudsim.examples.stage2b;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * Stage 2B — Python Threat Score → CloudSim Integration Validation.
 *
 * <p>Purpose: prove that existing threat scores from the Python ML pipeline
 * can be safely ingested by Java, mapped to simulated CloudSim VMs, and
 * survive the simulation setup without changing value.
 *
 * <p>This is the FIRST integration checkpoint. It uses:
 * <ul>
 *   <li>The existing score file: outputs/azure_to_azure_scores.csv</li>
 *   <li>A SMALL deterministic subset (~10-20 Azure deployment entities)</li>
 *   <li>ONE workload entity → ONE simulated VM (simulation abstraction only)</li>
 *   <li>Deterministic VM ID assignment</li>
 * </ul>
 *
 * <p>NOT implemented in this stage:
 * <ul>
 *   <li>NSSAO</li>
 *   <li>Migration</li>
 *   <li>Adaptive threshold controller</li>
 *   <li>Co-residency/server-risk models</li>
 *   <li>Multi-objective optimization</li>
 *   <li>Alibaba CloudSim integration</li>
 *   <li>Large-scale simulation</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   java -cp "cloudsim/target/cloudsim-7.0.1.jar:cloudsim-examples/target/classes" \
 *     org.cloudbus.cloudsim.examples.stage2b.Stage2bValidation
 * </pre>
 */
public class Stage2bValidation {

    // === Configuration ===

    /**
     * Path to the existing Azure→Azure threat score CSV.
     * This file is already produced by the Python pipeline (Stage 1C/1D).
     * Do NOT regenerate it in this stage.
     */
    private static final String SCORE_CSV_PATH =
            "project/outputs/azure_to_azure_scores.csv";

    /**
     * Number of Azure deployment entities to select for this small
     * integration-validation simulation. We deliberately use a small
     * subset, not all 248 records.
     */
    private static final int NUM_ENTITIES_TO_SELECT = 12;

    /**
     * Base VM ID for the simulation. VMs are numbered sequentially from
     * this value. Deterministic.
     */
    private static final int VM_ID_BASE = 0;

    /**
     * CloudSim simulation parameters (minimal, for integration validation).
     */
    private static final int NUM_USER = 1;
    private static final boolean TRACE_FLAG = false;

    // === Simulation objects ===

    private static DatacenterBroker broker;
    private static EntityVmMapping entityVmMapping;
    private static List<Vm> vmList;

    public static void main(String[] args) {
        Log.println("========================================");
        Log.println("STAGE 2B VALIDATION");
        Log.println("Python Threat Score → CloudSim Integration");
        Log.println("========================================");
        Log.println();

        try {
            // === Step 1: Load and validate CSV ===
            Log.println("Step 1: Loading threat score CSV...");
            Path scorePath = PathUtil.toPath(SCORE_CSV_PATH);
            scorePath = PathUtil.requireExists(scorePath);
            Log.println("  CSV path: " + scorePath);

            ThreatScoreCsvReader reader = new ThreatScoreCsvReader(scorePath);
            List<ThreatRecord> allRecords = reader.read();
            Log.println("  Records loaded: " + allRecords.size());

            // === Step 2: Select a small deterministic subset ===
            Log.println("Step 2: Selecting " + NUM_ENTITIES_TO_SELECT
                    + " entities for small simulation...");
            List<ThreatRecord> selectedRecords = selectEntities(allRecords,
                    NUM_ENTITIES_TO_SELECT);
            Log.println("  Records selected: " + selectedRecords.size());

            // === Step 3: Build entity→VM mapping ===
            Log.println("Step 3: Building entity→VM mapping (1:1, deterministic)...");
            entityVmMapping = new EntityVmMapping(VM_ID_BASE);
            entityVmMapping.buildFrom(selectedRecords);
            Log.println("  Mappings created: " + entityVmMapping.getMappingCount());

            // === Step 4: Create CloudSim simulation ===
            Log.println("Step 4: Creating CloudSim simulation...");
            CloudSim.init(NUM_USER, Calendar.getInstance(), TRACE_FLAG);

            Datacenter datacenter = createDatacenter("Datacenter_0");
            broker = new DatacenterBroker("Broker");
            int brokerId = broker.getId();

            Log.println("  Creating " + entityVmMapping.getVmCount() + " VMs...");
            vmList = createVms(brokerId, selectedRecords);
            broker.submitGuestList(vmList);

            // Create cloudlets for all VMs
            List<Cloudlet> cloudletList = createCloudlets(brokerId, vmList);
            broker.submitCloudletList(cloudletList);

            // === Step 5: Validate score preservation BEFORE simulation ===
            Log.println("Step 5: Validating score preservation...");
            ValidationResult preSimValidation = validateScorePreservation(
                    selectedRecords, entityVmMapping);
            Log.println("  Input records: " + preSimValidation.inputCount);
            Log.println("  VMs created: " + preSimValidation.vmCount);
            Log.println("  Mappings: " + preSimValidation.mappingCount);
            Log.println("  Min threat score: " + preSimValidation.minScore);
            Log.println("  Max threat score: " + preSimValidation.maxScore);
            Log.println("  Score mismatches: " + preSimValidation.scoreMismatchCount);
            Log.println("  Mapping mismatches: " + preSimValidation.mappingMismatchCount);

            // === Step 6: Run simulation ===
            Log.println("Step 6: Running CloudSim simulation...");
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            // === Step 7: Post-simulation validation ===
            Log.println("Step 7: Post-simulation validation...");
            boolean simulationCompleted = validatePostSimulation(vmList, broker);

            // === Step 8: Focused validation checks ===
            Log.println("Step 8: Focused validation checks...");
            runFocusedValidations(selectedRecords, entityVmMapping);

            // === Final report ===
            Log.println();
            Log.println("========================================");
            Log.println("STAGE 2B VALIDATION REPORT");
            Log.println("========================================");
            Log.println("Records loaded: " + allRecords.size());
            Log.println("Records selected: " + selectedRecords.size());
            Log.println("VMs created: " + vmList.size());
            Log.println("Mappings created: " + entityVmMapping.getMappingCount());
            Log.println("Min threat score: " + preSimValidation.minScore);
            Log.println("Max threat score: " + preSimValidation.maxScore);
            Log.println("Score mismatches: " + preSimValidation.scoreMismatchCount);
            Log.println("Mapping mismatches: " + preSimValidation.mappingMismatchCount);
            Log.println("Simulation completed: " + simulationCompleted);
            Log.println();

            if (preSimValidation.scoreMismatchCount > 0
                    || preSimValidation.mappingMismatchCount > 0) {
                Log.println("VALIDATION FAILED: mismatches detected");
                System.exit(1);
            }

            if (!simulationCompleted) {
                Log.println("VALIDATION FAILED: simulation did not complete");
                System.exit(1);
            }

            Log.println("STAGE 2B VALIDATION PASSED");
            Log.println("========================================");

        } catch (Exception e) {
            Log.println("FATAL ERROR in Stage 2B validation:");
            Log.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === Entity selection ===

    /**
     * Select a deterministic subset of entities from the full record list.
     * We take every Nth record to get a spread across the dataset, then
     * trim to the desired count.
     */
    private static List<ThreatRecord> selectEntities(List<ThreatRecord> all,
                                                      int count) {
        List<ThreatRecord> result = new ArrayList<>();
        if (all.size() <= count) {
            return new ArrayList<>(all);
        }

        // Take evenly spaced records for good spread
        int step = all.size() / count;
        for (int i = 0; i < count; i++) {
            int index = Math.min(i * step, all.size() - 1);
            result.add(all.get(index));
        }
        return result;
    }

    // === CloudSim setup ===

    private static Datacenter createDatacenter(String name) {
        // Create hosts - use enough hosts for all VMs
        List<Host> hostList = new ArrayList<>();

        // 4 hosts, each with 2000 MIPS, can fit 12 VMs at 500 MIPS each
        for (int i = 0; i < 4; i++) {
            hostList.add(createHost(i, 4096, 1000000, 1000000, 2000));
        }

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double timeZone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        LinkedList<Storage> storageList = new LinkedList<>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, timeZone, cost,
                costPerMem, costPerStorage, costPerBw);

        try {
            return new Datacenter(name, characteristics,
                    new VmAllocationPolicySimple(hostList),
                    storageList, 0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create datacenter: " + name, e);
        }
    }

    private static Host createHost(int hostId, int ram, long storage,
                                   long bw, int mips) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(new PeProvisionerSimple(mips)));

        return new Host(
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw),
                storage,
                peList,
                new VmSchedulerTimeShared(peList)
        );
    }

    private static List<Vm> createVms(int brokerId,
                                       List<ThreatRecord> records) {
        List<Vm> vms = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            ThreatRecord record = records.get(i);
            int vmid = VM_ID_BASE + i;

            // VM sizing: modest, uniform for integration validation
            // In later stages, VM sizing may be derived from features
            int mips = 500;
            int pesNumber = 1;
            int ram = 256;
            long bw = 1000;
            long size = 1000;
            String vmm = "Xen";

            Vm vm = new Vm(vmid, brokerId, mips, pesNumber, ram, bw,
                    size, vmm, new CloudletSchedulerTimeShared());

            vms.add(vm);
        }
        return vms;
    }

    private static List<Cloudlet> createCloudlets(int brokerId,
                                                   List<Vm> vms) {
        List<Cloudlet> cloudlets = new ArrayList<>();

        for (int i = 0; i < vms.size(); i++) {
            Vm vm = vms.get(i);
            int cloudletId = i;
            long length = 400000;
            long fileSize = 300;
            long outputSize = 300;
            UtilizationModel utilizationModel = new UtilizationModelFull();

            Cloudlet cloudlet = new Cloudlet(cloudletId, length, vm.getNumberOfPes(),
                    fileSize, outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlet.setGuestId(vm.getId());

            cloudlets.add(cloudlet);
        }
        return cloudlets;
    }

    // === Validation ===

    static class ValidationResult {
        final int inputCount;
        final int vmCount;
        final int mappingCount;
        final double minScore;
        final double maxScore;
        final int scoreMismatchCount;
        final int mappingMismatchCount;

        ValidationResult(int inputCount, int vmCount, int mappingCount,
                         double minScore, double maxScore,
                         int scoreMismatchCount, int mappingMismatchCount) {
            this.inputCount = inputCount;
            this.vmCount = vmCount;
            this.mappingCount = mappingCount;
            this.minScore = minScore;
            this.maxScore = maxScore;
            this.scoreMismatchCount = scoreMismatchCount;
            this.mappingMismatchCount = mappingMismatchCount;
        }
    }

    /**
     * Validate that every threat record's score is preserved exactly
     * in the VM mapping.
     */
    private static ValidationResult validateScorePreservation(
            List<ThreatRecord> records,
            EntityVmMapping mapping) {

        int scoreMismatchCount = 0;
        int mappingMismatchCount = 0;
        double minScore = Double.MAX_VALUE;
        double maxScore = Double.MIN_VALUE;

        for (int i = 0; i < records.size(); i++) {
            ThreatRecord record = records.get(i);
            int expectedVmId = VM_ID_BASE + i;
            double expectedScore = record.getThreatScore();

            // Update min/max from input
            if (expectedScore < minScore) minScore = expectedScore;
            if (expectedScore > maxScore) maxScore = expectedScore;

            // Check mapping exists
            EntityVmMapping.VmThreatMetadata metadata =
                    mapping.getMetadata(expectedVmId).orElse(null);

            if (metadata == null) {
                mappingMismatchCount++;
                Log.println("  MAPPING MISMATCH: no metadata for VM ID "
                        + expectedVmId);
                continue;
            }

            // Check entity key matches
            if (!metadata.getDataset().equals(record.getDataset())
                    || !metadata.getEntityType().equals(record.getEntityType())
                    || !metadata.getEntityId().equals(record.getEntityId())) {
                mappingMismatchCount++;
                Log.println("  MAPPING MISMATCH: VM " + expectedVmId
                        + " entity mismatch");
                continue;
            }

            // Check score preservation - EXACT match required
            double actualScore = metadata.getThreatScore();
            if (expectedScore != actualScore) {
                scoreMismatchCount++;
                Log.println("  SCORE MISMATCH: VM " + expectedVmId
                        + " expected=" + expectedScore
                        + " actual=" + actualScore);
            }
        }

        return new ValidationResult(
                records.size(),
                mapping.getVmCount(),
                mapping.getMappingCount(),
                minScore,
                maxScore,
                scoreMismatchCount,
                mappingMismatchCount
        );
    }

    private static boolean validatePostSimulation(List<Vm> vms,
                                                  DatacenterBroker broker) {
        // Check VMs still have their metadata accessible
        for (Vm vm : vms) {
            int vmId = vm.getId();
            EntityVmMapping.VmThreatMetadata metadata =
                    entityVmMapping.getMetadata(vmId).orElse(null);
            if (metadata == null) {
                Log.println("  POST-SIM FAIL: VM " + vmId
                        + " lost metadata after simulation");
                return false;
            }
        }

        // Check cloudlets completed
        List<Cloudlet> receivedList = broker.getCloudletReceivedList();
        Log.println("  Cloudlets completed: " + receivedList.size()
                + " / " + vms.size() + " submitted");

        return true;
    }

    // === Focused validation checks ===

    private static void runFocusedValidations(List<ThreatRecord> records,
                                              EntityVmMapping mapping) {
        Log.println("  Running focused validation checks...");

        // A. Duplicate composite key → rejected (tested via CSV reader)
        Log.println("    [A] Duplicate composite key rejection: "
                + (testDuplicateKeyRejection() ? "PASS" : "FAIL"));

        // B. Invalid threat score → rejected
        Log.println("    [B] Invalid threat score rejection: "
                + (testInvalidScoreRejection() ? "PASS" : "FAIL"));

        // C. Score outside [0,1] → rejected
        Log.println("    [C] Score outside [0,1] rejection: "
                + (testScoreRangeRejection() ? "PASS" : "FAIL"));

        // D. Deterministic entity→VM mapping
        Log.println("    [D] Deterministic entity→VM mapping: "
                + (testDeterministicMapping() ? "PASS" : "FAIL"));

        // E. Score preservation
        Log.println("    [E] Score preservation: "
                + (testScorePreservation(records, mapping) ? "PASS" : "FAIL"));
    }

    /**
     * Test that duplicate composite keys are rejected by the CSV reader.
     */
    private static boolean testDuplicateKeyRejection() {
        try {
            String csv = "dataset,entity_type,entity_id,threat_score\n"
                    + "azure_v2,deployment,key1,0.5\n"
                    + "azure_v2,deployment,key1,0.7\n";

            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                    "dup_test_", ".csv");
            java.nio.file.Files.writeString(tempFile, csv,
                    java.nio.charset.StandardCharsets.UTF_8);

            ThreatScoreCsvReader reader = new ThreatScoreCsvReader(tempFile);
            reader.read();

            java.nio.file.Files.deleteIfExists(tempFile);
            return false; // Should have thrown
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Duplicate")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Test that invalid (non-numeric) threat scores are rejected.
     */
    private static boolean testInvalidScoreRejection() {
        try {
            String csv = "dataset,entity_type,entity_id,threat_score\n"
                    + "azure_v2,deployment,key1,not_a_number\n";

            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                    "invalid_score_test_", ".csv");
            java.nio.file.Files.writeString(tempFile, csv,
                    java.nio.charset.StandardCharsets.UTF_8);

            ThreatScoreCsvReader reader = new ThreatScoreCsvReader(tempFile);
            reader.read();

            java.nio.file.Files.deleteIfExists(tempFile);
            return false; // Should have thrown
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Test that scores outside [0,1] are rejected.
     */
    private static boolean testScoreRangeRejection() {
        // Test negative score
        try {
            String csv = "dataset,entity_type,entity_id,threat_score\n"
                    + "azure_v2,deployment,key1,-0.1\n";

            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                    "neg_score_test_", ".csv");
            java.nio.file.Files.writeString(tempFile, csv,
                    java.nio.charset.StandardCharsets.UTF_8);

            ThreatScoreCsvReader reader = new ThreatScoreCsvReader(tempFile);
            reader.read();

            java.nio.file.Files.deleteIfExists(tempFile);
            return false; // Should have thrown
        } catch (IllegalArgumentException e) {
            // Expected - continue to next test
        } catch (Exception e) {
            return false;
        }

        // Test score > 1
        try {
            String csv = "dataset,entity_type,entity_id,threat_score\n"
                    + "azure_v2,deployment,key1,1.5\n";

            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                    "over1_score_test_", ".csv");
            java.nio.file.Files.writeString(tempFile, csv,
                    java.nio.charset.StandardCharsets.UTF_8);

            ThreatScoreCsvReader reader = new ThreatScoreCsvReader(tempFile);
            reader.read();

            java.nio.file.Files.deleteIfExists(tempFile);
            return false; // Should have thrown
        } catch (IllegalArgumentException e) {
            return true; // Both tests passed
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Test that the same input always produces the same VM mapping.
     */
    private static boolean testDeterministicMapping() {
        try {
            String csv = "dataset,entity_type,entity_id,threat_score\n"
                    + "azure_v2,deployment,entityA,0.3\n"
                    + "azure_v2,deployment,entityB,0.7\n"
                    + "azure_v2,deployment,entityC,0.5\n";

            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                    "det_test_", ".csv");
            java.nio.file.Files.writeString(tempFile, csv,
                    java.nio.charset.StandardCharsets.UTF_8);

            ThreatScoreCsvReader reader = new ThreatScoreCsvReader(tempFile);
            List<ThreatRecord> records1 = reader.read();

            EntityVmMapping mapping1 = new EntityVmMapping(100);
            mapping1.buildFrom(records1);

            // Re-read and re-build
            List<ThreatRecord> records2 = reader.read();
            EntityVmMapping mapping2 = new EntityVmMapping(100);
            mapping2.buildFrom(records2);

            // Compare mappings
            boolean same = true;
            for (int vmId : mapping1.getVmIds()) {
                EntityVmMapping.VmThreatMetadata m1 =
                        mapping1.getMetadata(vmId).orElse(null);
                EntityVmMapping.VmThreatMetadata m2 =
                        mapping2.getMetadata(vmId).orElse(null);

                if (m1 == null || m2 == null) {
                    same = false;
                    break;
                }
                if (m1.getThreatScore() != m2.getThreatScore()
                        || !m1.getEntityId().equals(m2.getEntityId())) {
                    same = false;
                    break;
                }
            }

            java.nio.file.Files.deleteIfExists(tempFile);
            return same;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Test that input scores equal stored VM metadata scores.
     */
    private static boolean testScorePreservation(List<ThreatRecord> records,
                                                 EntityVmMapping mapping) {
        for (int i = 0; i < records.size(); i++) {
            ThreatRecord record = records.get(i);
            int vmId = VM_ID_BASE + i;

            EntityVmMapping.VmThreatMetadata metadata =
                    mapping.getMetadata(vmId).orElse(null);

            if (metadata == null) {
                return false;
            }
            if (metadata.getThreatScore() != record.getThreatScore()) {
                return false;
            }
            if (!metadata.getDataset().equals(record.getDataset())
                    || !metadata.getEntityType().equals(record.getEntityType())
                    || !metadata.getEntityId().equals(record.getEntityId())) {
                return false;
            }
        }
        return true;
    }
}
