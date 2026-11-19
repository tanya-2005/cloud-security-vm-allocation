// package org.cloudbus.cloudsim.examples.stage2k;

// import org.cloudbus.cloudsim.Host;
// import org.cloudbus.cloudsim.Log;
// import org.cloudbus.cloudsim.Pe;
// import org.cloudbus.cloudsim.Vm;
// import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
// import org.cloudbus.cloudsim.core.CloudSim;
// import org.cloudbus.cloudsim.examples.stage2b.ThreatRecord;
// import org.cloudbus.cloudsim.examples.stage2b.ThreatScoreCsvReader;
// import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
// import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
// import org.cloudbus.cloudsim.examples.stage2e.AdaptiveThreatThreshold;
// import org.cloudbus.cloudsim.examples.stage2g.NssaoConfig;
// import org.cloudbus.cloudsim.examples.stage2h.Stage2hCloudSimIntegration;
// import org.cloudbus.cloudsim.examples.stage2h.SimulationMetrics;
// import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
// import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
// import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.util.*;
// import java.util.stream.Collectors;

// /**
//  * Stage 2K — Final Demonstration Entry Point
//  * 
//  * Demonstrates the complete research pipeline using real threat scores
//  * and the actual adaptive threshold implementation (NOT hardcoded 0.5/0.6).
//  * 
//  * Uses existing Stage 2H CloudSimIntegration orchestrator unchanged.
//  */
// public final class Stage2kDemo {
    
//     private static final int DEMO_VM_COUNT = 10;
//     private static final long SEED = 20260911L;
    
//     public static void main(String[] args) {
//         try {
//             Log.println("========================================");
//             Log.println("STAGE 2K — FINAL DEMONSTRATION");
//             Log.println("Complete Research Pipeline End-to-End");
//             Log.println("========================================");
//             Log.println();
            
//             // === 1. Load real threat scores ===
//             Log.println("1. LOADING REAL THREAT SCORES");
//             Path projectRoot = Paths.get("").toAbsolutePath()
//         .getParent()
//         .getParent();

// Path csvPath = projectRoot.resolve("project/outputs/azure_to_azure_scores.csv");
            
//             if (!Files.exists(csvPath)) {
//                 Log.println("ERROR: CSV not found at " + csvPath);
//                 System.exit(1);
//             }
            
//             List<ThreatRecord> allRecords = new ThreatScoreCsvReader(csvPath).read();
//             Log.println("   Dataset: Azure V2");
//             Log.println("   Total entities: " + allRecords.size());
            
//             // Select moderate-score subset (use wider range for better threshold)
//             List<ThreatRecord> sorted = new ArrayList<>(allRecords);
//             sorted.sort(Comparator.comparingDouble(ThreatRecord::getThreatScore));
//             int start = sorted.size() * 2 / 5;  // Start at 40th percentile
//             int end = Math.min(start + DEMO_VM_COUNT, sorted.size());
//             List<ThreatRecord> selected = sorted.subList(start, end);
            
//             Log.println("   Selected: entities " + start + "-" + end + " (moderate pressure)");
//             Log.println("   Demo VMs: " + selected.size());
//             Log.println();
            
//             // === 2. Compute adaptive threshold ===
//             Log.println("2. ADAPTIVE THRESHOLD (Stage 2E)");
//             List<Double> scores = selected.stream()
//                     .map(ThreatRecord::getThreatScore)
//                     .collect(Collectors.toList());
            
//             AdaptiveThreatThreshold calc = new AdaptiveThreatThreshold();
//             double threshold = calc.compute(scores);
            
//             Log.println("   Score range: [" + String.format("%.6f", Collections.min(scores))
//                     + ", " + String.format("%.6f", Collections.max(scores)) + "]");
//             Log.println("   Computed threshold (Stage 2E): " + String.format("%.9f", threshold));
//             Log.println("   NOTE: This is computed from real Azure V2 scores, NOT hardcoded");
            
//             long highThreat = scores.stream().filter(s -> s >= threshold).count();
//             Log.println("   High-threat: " + highThreat + "/" + scores.size() 
//                     + " (" + String.format("%.1f%%", 100.0 * highThreat / scores.size()) + ")");
//             Log.println();
            
//             // === 3. Initialize CloudSim ===
//             CloudSim.init(1, Calendar.getInstance(), false);
            
//             // === 4. Build hosts ===
//             Log.println("3. CREATING HOSTS");
//             List<Host> hosts = new ArrayList<>();
//             hosts.add(createHost(0, 2000, 4096)); // Large
//             hosts.add(createHost(1, 1000, 2048)); // Small  
//             hosts.add(createHost(2, 1000, 2048)); // Small
            
//             for (Host h : hosts) {
//                 Log.println("   Host " + h.getId() + ": " + h.getTotalMips() 
//                         + " MIPS, " + h.getRam() + " MB");
//             }
//             Log.println();
            
//             // === 5. Create VMs with threat metadata ===
//             Log.println("4. CREATING VMs WITH THREAT METADATA");
//             List<Vm> vms = new ArrayList<>();
//             Map<Integer, VmThreatState> threatMap = new LinkedHashMap<>();
            
//             int vmId = 100;
//             for (ThreatRecord record : selected) {
//                 vms.add(createVm(vmId, 250, 256));
                
//                 // CRITICAL: Use adaptive threshold, NOT hardcoded 0.5 or 0.6
//                 threatMap.put(vmId, new VmThreatState(
//                         vmId,
//                         record.getThreatScore(),
//                         record.getDataset(),
//                         record.getEntityType(),
//                         record.getEntityId(),
//                         threshold  // <-- ADAPTIVE
//                 ));
//                 vmId++;
//             }
            
//             Log.println("   VMs created: " + vms.size());
//             Log.println("   Classification threshold: " + String.format("%.9f", threshold));
            
//             long highThreatVms = threatMap.values().stream()
//                     .filter(VmThreatState::isHighThreat).count();
//             Log.println("   High-threat VMs: " + highThreatVms + "/" + vms.size());
//             Log.println();
            
//             // === 6. Run complete pipeline via Stage 2H orchestrator ===
//             Log.println("5. RUNNING COMPLETE PIPELINE (Stage 2H)");
//             Log.println("   FFD → NSSAO → Pareto selection → Migration → Metrics");
//             Log.println();
            
//             ServerRiskModel riskModel = new ServerRiskModel(0.1, 0.3);
//             NssaoConfig nssaoConfig = NssaoConfig.builder()
//                     .seed(SEED)
//                     .populationSize(20)
//                     .maxIterations(8)
//                     .stallLimit(3)
//                     .build();
            
//             Log.println("   NSSAO config: N=" + nssaoConfig.getPopulationSize()
//                     + ", t_max=" + nssaoConfig.getMaxIterations() 
//                     + ", seed=" + nssaoConfig.getSeed());
            
//             Stage2hCloudSimIntegration orchestrator = new Stage2hCloudSimIntegration(
//                     hosts, vms, threatMap, riskModel, 1.0, 1.0, nssaoConfig);
            
//             SimulationMetrics metrics = orchestrator.run();
            
//             // === 7. Display results ===
//             Log.println();
//             Log.println("6. RESULTS");
//             SimulationMetrics m = orchestrator.getMetrics();
//             Log.println("   Objectives (final state):");
//             Log.println("      f1 (threat×server-risk): " 
//                     + String.format("%.6f", m.getF1ThreatServerRisk()));
//             Log.println("      f2 (co-residency risk):  " 
//                     + String.format("%.6f", m.getF2CoResidencyRisk()));
//             Log.println("      f3 (defensive cost):     " 
//                     + String.format("%.6f", m.getF3Cost()));
//             Log.println("      f4 (unused capacity):    " 
//                     + String.format("%.6f", m.getF4UnusedCapacity()));
//             Log.println();
            
//             Log.println("   Placement:");
//             Log.println("      Active hosts: " + m.getActiveHostCount());
//             Log.println("      Migrations: " + m.getMigrationCount() 
//                     + " (cost: " + String.format("%.1f", m.getMigrationCost()) + ")");
//             Log.println("      CPU utilization: " 
//                     + String.format("%.1f%%", m.getCpuUtilization() * 100));
//             Log.println("      Memory utilization: " 
//                     + String.format("%.1f%%", m.getMemoryUtilization() * 100));
//             Log.println();
            
//             Log.println("   Co-residency duration:");
//             Log.println("      Total: " + String.format("%.1f", m.getTotalCoResidencyDuration()));
//             Log.println("      High-threat: " + String.format("%.1f", m.getHighThreatCoResidencyDuration()));
//             Log.println("      Episodes: " + m.getCoResidencyEpisodeCount());
//             Log.println();
            
//             Log.println("   NSSAO:");
//             Log.println("      Iterations: " + m.getIterationsExecuted());
//             Log.println("      Stopped by stall: " + m.isStoppedByStall());
//             Log.println("      Front-1 size: " + m.getFront1RawSize());
//             Log.println("      Unique placements: " + m.getUniquePlacements());
//             Log.println();
            
//             // === 8. Verify ===
//             boolean success = true;
            
//             // Threshold should be in valid range [0.5, 0.9] - hitting boundary is OK for this dataset
//             if (threshold < 0.5 || threshold > 0.9) {
//                 Log.println("VERIFICATION FAILED: Threshold " + threshold 
//                         + " outside allowed range [0.5, 0.9]");
//                 success = false;
//             }
            
//             // Note: hitting the exact boundary (0.5 or 0.9) is valid for this dataset
//             // - 0.5 when raw threshold < 0.5 (clamped)
//             // - 0.9 when raw threshold > 0.9 (clamped)
//             if (threshold == 0.5) {
//                 Log.println("   NOTE: Threshold hit lower bound (0.5) - valid boundary case");
//             } else if (threshold == 0.9) {
//                 Log.println("   NOTE: Threshold hit upper bound (0.9) - valid boundary case");
//             }
            
//             if (!Double.isFinite(m.getF1ThreatServerRisk()) || !Double.isFinite(m.getF2CoResidencyRisk()) 
//                     || !Double.isFinite(m.getF3Cost()) || !Double.isFinite(m.getF4UnusedCapacity())) {
//                 Log.println("VERIFICATION FAILED: Non-finite objectives");
//                 success = false;
//             }
            
//             if (m.getIterationsExecuted() < 1) {
//                 Log.println("VERIFICATION FAILED: NSSAO did not run");
//                 success = false;
//             }
            
//             if (success) {
//                 Log.println("========================================");
//                 Log.println("STAGE 2K DEMONSTRATION: SUCCESS");
//                 Log.println("========================================");
//                 Log.println();
//                 Log.println("SUMMARY:");
//                 Log.println("✓ Real Azure V2 threat scores loaded");
//                 Log.println("✓ Adaptive threshold: " + String.format("%.9f", threshold));
//                 Log.println("✓ " + highThreatVms + "/" + vms.size() + " VMs classified as high-threat");
//                 Log.println("✓ NSSAO ran " + m.getIterationsExecuted() + " iterations");
//                 Log.println("✓ " + m.getMigrationCount() + " migrations executed");
//                 Log.println("✓ Final objectives computed");
//                 Log.println("✓ Co-residency tracked across " + m.getCoResidencyEpisodeCount() + " episodes");
//             } else {
//                 Log.println("========================================");
//                 Log.println("STAGE 2K DEMONSTRATION: FAILED");
//                 Log.println("========================================");
//                 System.exit(1);
//             }
            
//         } catch (Exception e) {
//             Log.println("========================================");
//             Log.println("STAGE 2K DEMONSTRATION: EXCEPTION");
//             Log.println("========================================");
//             e.printStackTrace();
//             System.exit(1);
//         }
//     }
    
//     private static Host createHost(int id, int mips, int ram) {
//         List<Pe> peList = new ArrayList<>();
//         peList.add(new Pe(0, new PeProvisionerSimple(mips)));
//         return new Host(id, new RamProvisionerSimple(ram),
//                 new BwProvisionerSimple(100000), 1000000, peList,
//                 new VmSchedulerSpaceShared(peList));
//     }
    
//     private static Vm createVm(int id, int mips, int ram) {
//         return new Vm(id, 0, mips, 1, ram, 100, 1000, "Xen", null);
//     }
// }



























// package org.cloudbus.cloudsim.examples.stage2k;

// import org.cloudbus.cloudsim.Host;
// import org.cloudbus.cloudsim.Log;
// import org.cloudbus.cloudsim.Pe;
// import org.cloudbus.cloudsim.Vm;
// import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
// import org.cloudbus.cloudsim.core.CloudSim;
// import org.cloudbus.cloudsim.examples.stage2b.ThreatRecord;
// import org.cloudbus.cloudsim.examples.stage2b.ThreatScoreCsvReader;
// import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
// import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
// import org.cloudbus.cloudsim.examples.stage2e.AdaptiveThreatThreshold;
// import org.cloudbus.cloudsim.examples.stage2g.NssaoConfig;
// import org.cloudbus.cloudsim.examples.stage2h.Stage2hCloudSimIntegration;
// import org.cloudbus.cloudsim.examples.stage2h.SimulationMetrics;
// import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
// import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
// import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.util.*;
// import java.util.stream.Collectors;

// /**
//  * Stage 2K — Final Demonstration Entry Point
//  *
//  * Demonstrates the complete research pipeline using real threat scores
//  * and the actual adaptive threshold implementation (NOT hardcoded 0.5/0.6).
//  *
//  * Uses existing Stage 2H CloudSimIntegration orchestrator unchanged.
//  *
//  * Usage (program argument selects the dataset):
//  *   (no argument) or "azure"   -> Azure V2 only      (original behaviour)
//  *   "alibaba"                  -> Alibaba V2018 only
//  *   "both"                     -> Azure, then Alibaba, in the same run
//  */
// public final class Stage2kDemo {

//     private static final int DEMO_VM_COUNT = 10;
//     private static final long SEED = 20260911L;

//     /** Supported datasets: key -> (display name, score CSV under project/outputs/). */
//     private enum Dataset {
//         AZURE("azure", "Azure V2", "azure_to_azure_scores.csv"),
//         ALIBABA("alibaba", "Alibaba V2018", "alibaba_to_alibaba_scores.csv");

//         final String key;
//         final String displayName;
//         final String csvFile;

//         Dataset(String key, String displayName, String csvFile) {
//             this.key = key;
//             this.displayName = displayName;
//             this.csvFile = csvFile;
//         }
//     }

//     public static void main(String[] args) {
//         String choice = (args.length > 0) ? args[0].trim().toLowerCase(Locale.ROOT) : "azure";

//         List<Dataset> toRun = new ArrayList<>();
//         switch (choice) {
//             case "azure":
//                 toRun.add(Dataset.AZURE);
//                 break;
//             case "alibaba":
//                 toRun.add(Dataset.ALIBABA);
//                 break;
//             case "both":
//                 toRun.add(Dataset.AZURE);
//                 toRun.add(Dataset.ALIBABA);
//                 break;
//             default:
//                 System.err.println("Unknown dataset '" + choice
//                         + "'. Use: azure | alibaba | both");
//                 System.exit(2);
//                 return;
//         }

//         boolean allOk = true;
//         Map<Dataset, Boolean> outcome = new LinkedHashMap<>();
//         for (Dataset ds : toRun) {
//             boolean ok;
//             try {
//                 ok = runDemo(ds);
//             } catch (Exception e) {
//                 Log.println("========================================");
//                 Log.println("STAGE 2K DEMONSTRATION (" + ds.displayName + "): EXCEPTION");
//                 Log.println("========================================");
//                 e.printStackTrace();
//                 ok = false;
//             }
//             outcome.put(ds, ok);
//             allOk &= ok;
//             Log.println();
//         }

//         if (toRun.size() > 1) {
//             Log.println("========================================");
//             Log.println("STAGE 2K OVERALL");
//             for (Map.Entry<Dataset, Boolean> e : outcome.entrySet()) {
//                 Log.println("   " + e.getKey().displayName + ": "
//                         + (e.getValue() ? "SUCCESS" : "FAILED"));
//             }
//             Log.println("========================================");
//         }

//         if (!allOk) {
//             System.exit(1);
//         }
//     }

//     /**
//      * Runs the full Stage 2K pipeline for one dataset.
//      *
//      * @return true if all verification checks passed
//      */
//     private static boolean runDemo(Dataset ds) throws Exception {
//         Log.println("========================================");
//         Log.println("STAGE 2K — FINAL DEMONSTRATION [" + ds.displayName + "]");
//         Log.println("Complete Research Pipeline End-to-End");
//         Log.println("========================================");
//         Log.println();

//         // === 1. Load real threat scores ===
//         Log.println("1. LOADING REAL THREAT SCORES");
//         Path csvPath = locateScoresCsv(ds.csvFile);

//         if (csvPath == null) {
//             Log.println("ERROR: " + ds.csvFile + " not found under project/outputs "
//                     + "(searched from working dir " + Paths.get("").toAbsolutePath() + " upwards)");
//             return false;
//         }

//         List<ThreatRecord> allRecords = new ThreatScoreCsvReader(csvPath).read();
//         Log.println("   Dataset: " + ds.displayName);
//         Log.println("   Source file: " + csvPath);
//         Log.println("   Total entities: " + allRecords.size());

//         if (allRecords.isEmpty()) {
//             Log.println("ERROR: no records loaded");
//             return false;
//         }

//         // Select moderate-score subset (use wider range for better threshold)
//         List<ThreatRecord> sorted = new ArrayList<>(allRecords);
//         sorted.sort(Comparator.comparingDouble(ThreatRecord::getThreatScore));
//         int start = sorted.size() * 2 / 5;  // Start at 40th percentile
//         int end = Math.min(start + DEMO_VM_COUNT, sorted.size());
//         List<ThreatRecord> selected = sorted.subList(start, end);

//         Log.println("   Selected: entities " + start + "-" + end + " (moderate pressure)");
//         Log.println("   Demo VMs: " + selected.size());
//         Log.println();

//         // === 2. Compute adaptive threshold ===
//         Log.println("2. ADAPTIVE THRESHOLD (Stage 2E)");
//         List<Double> scores = selected.stream()
//                 .map(ThreatRecord::getThreatScore)
//                 .collect(Collectors.toList());

//         AdaptiveThreatThreshold calc = new AdaptiveThreatThreshold();
//         double threshold = calc.compute(scores);

//         Log.println("   Score range: [" + String.format("%.6f", Collections.min(scores))
//                 + ", " + String.format("%.6f", Collections.max(scores)) + "]");
//         Log.println("   Computed threshold (Stage 2E): " + String.format("%.9f", threshold));
//         Log.println("   NOTE: This is computed from real " + ds.displayName
//                 + " scores, NOT hardcoded");

//         long highThreat = scores.stream().filter(s -> s >= threshold).count();
//         Log.println("   High-threat: " + highThreat + "/" + scores.size()
//                 + " (" + String.format("%.1f%%", 100.0 * highThreat / scores.size()) + ")");
//         Log.println();

//         // === 3. Initialize CloudSim ===
//         // CloudSim.init() resets the simulation's static state, so it is safe
//         // to call once per dataset when running "both".
//         CloudSim.init(1, Calendar.getInstance(), false);

//         // === 4. Build hosts ===
//         Log.println("3. CREATING HOSTS");
//         List<Host> hosts = new ArrayList<>();
//         hosts.add(createHost(0, 2000, 4096)); // Large
//         hosts.add(createHost(1, 1000, 2048)); // Small
//         hosts.add(createHost(2, 1000, 2048)); // Small

//         for (Host h : hosts) {
//             Log.println("   Host " + h.getId() + ": " + h.getTotalMips()
//                     + " MIPS, " + h.getRam() + " MB");
//         }
//         Log.println();

//         // === 5. Create VMs with threat metadata ===
//         Log.println("4. CREATING VMs WITH THREAT METADATA");
//         List<Vm> vms = new ArrayList<>();
//         Map<Integer, VmThreatState> threatMap = new LinkedHashMap<>();

//         int vmId = 100;
//         for (ThreatRecord record : selected) {
//             vms.add(createVm(vmId, 250, 256));

//             // CRITICAL: Use adaptive threshold, NOT hardcoded 0.5 or 0.6
//             threatMap.put(vmId, new VmThreatState(
//                     vmId,
//                     record.getThreatScore(),
//                     record.getDataset(),
//                     record.getEntityType(),
//                     record.getEntityId(),
//                     threshold  // <-- ADAPTIVE
//             ));
//             vmId++;
//         }

//         Log.println("   VMs created: " + vms.size());
//         Log.println("   Classification threshold: " + String.format("%.9f", threshold));

//         long highThreatVms = threatMap.values().stream()
//                 .filter(VmThreatState::isHighThreat).count();
//         Log.println("   High-threat VMs: " + highThreatVms + "/" + vms.size());
//         Log.println();

//         // === 6. Run complete pipeline via Stage 2H orchestrator ===
//         Log.println("5. RUNNING COMPLETE PIPELINE (Stage 2H)");
//         Log.println("   FFD → NSSAO → Pareto selection → Migration → Metrics");
//         Log.println();

//         ServerRiskModel riskModel = new ServerRiskModel(0.1, 0.3);
//         NssaoConfig nssaoConfig = NssaoConfig.builder()
//                 .seed(SEED)
//                 .populationSize(20)
//                 .maxIterations(8)
//                 .stallLimit(3)
//                 .build();

//         Log.println("   NSSAO config: N=" + nssaoConfig.getPopulationSize()
//                 + ", t_max=" + nssaoConfig.getMaxIterations()
//                 + ", seed=" + nssaoConfig.getSeed());

//         Stage2hCloudSimIntegration orchestrator = new Stage2hCloudSimIntegration(
//                 hosts, vms, threatMap, riskModel, 1.0, 1.0, nssaoConfig);

//         orchestrator.run();

//         // === 7. Display results ===
//         Log.println();
//         Log.println("6. RESULTS [" + ds.displayName + "]");
//         SimulationMetrics m = orchestrator.getMetrics();
//         Log.println("   Objectives (final state):");
//         Log.println("      f1 (threat×server-risk): "
//                 + String.format("%.6f", m.getF1ThreatServerRisk()));
//         Log.println("      f2 (co-residency risk):  "
//                 + String.format("%.6f", m.getF2CoResidencyRisk()));
//         Log.println("      f3 (defensive cost):     "
//                 + String.format("%.6f", m.getF3Cost()));
//         Log.println("      f4 (unused capacity):    "
//                 + String.format("%.6f", m.getF4UnusedCapacity()));
//         Log.println();

//         Log.println("   Placement:");
//         Log.println("      Active hosts: " + m.getActiveHostCount());
//         Log.println("      Migrations: " + m.getMigrationCount()
//                 + " (cost: " + String.format("%.1f", m.getMigrationCost()) + ")");
//         Log.println("      CPU utilization: "
//                 + String.format("%.1f%%", m.getCpuUtilization() * 100));
//         Log.println("      Memory utilization: "
//                 + String.format("%.1f%%", m.getMemoryUtilization() * 100));
//         Log.println();

//         Log.println("   Co-residency duration:");
//         Log.println("      Total: " + String.format("%.1f", m.getTotalCoResidencyDuration()));
//         Log.println("      High-threat: " + String.format("%.1f", m.getHighThreatCoResidencyDuration()));
//         Log.println("      Episodes: " + m.getCoResidencyEpisodeCount());
//         Log.println();

//         Log.println("   NSSAO:");
//         Log.println("      Iterations: " + m.getIterationsExecuted());
//         Log.println("      Stopped by stall: " + m.isStoppedByStall());
//         Log.println("      Front-1 size: " + m.getFront1RawSize());
//         Log.println("      Unique placements: " + m.getUniquePlacements());
//         Log.println();

//         // === 8. Verify ===
//         boolean success = true;

//         // Threshold should be in valid range [0.5, 0.9] - hitting boundary is OK
//         if (threshold < 0.5 || threshold > 0.9) {
//             Log.println("VERIFICATION FAILED: Threshold " + threshold
//                     + " outside allowed range [0.5, 0.9]");
//             success = false;
//         }

//         // Hitting the exact boundary (0.5 or 0.9) is valid:
//         // - 0.5 when raw threshold < 0.5 (clamped)
//         // - 0.9 when raw threshold > 0.9 (clamped)
//         if (threshold == 0.5) {
//             Log.println("   NOTE: Threshold hit lower bound (0.5) - valid boundary case");
//         } else if (threshold == 0.9) {
//             Log.println("   NOTE: Threshold hit upper bound (0.9) - valid boundary case");
//         }

//         if (!Double.isFinite(m.getF1ThreatServerRisk()) || !Double.isFinite(m.getF2CoResidencyRisk())
//                 || !Double.isFinite(m.getF3Cost()) || !Double.isFinite(m.getF4UnusedCapacity())) {
//             Log.println("VERIFICATION FAILED: Non-finite objectives");
//             success = false;
//         }

//         if (m.getIterationsExecuted() < 1) {
//             Log.println("VERIFICATION FAILED: NSSAO did not run");
//             success = false;
//         }

//         if (success) {
//             Log.println("========================================");
//             Log.println("STAGE 2K DEMONSTRATION [" + ds.displayName + "]: SUCCESS");
//             Log.println("========================================");
//             Log.println();
//             Log.println("SUMMARY:");
//             Log.println("✓ Real " + ds.displayName + " threat scores loaded");
//             Log.println("✓ Adaptive threshold: " + String.format("%.9f", threshold));
//             Log.println("✓ " + highThreatVms + "/" + vms.size() + " VMs classified as high-threat");
//             Log.println("✓ NSSAO ran " + m.getIterationsExecuted() + " iterations");
//             Log.println("✓ " + m.getMigrationCount() + " migrations executed");
//             Log.println("✓ Final objectives computed");
//             Log.println("✓ Co-residency tracked across " + m.getCoResidencyEpisodeCount() + " episodes");
//         } else {
//             Log.println("========================================");
//             Log.println("STAGE 2K DEMONSTRATION [" + ds.displayName + "]: FAILED");
//             Log.println("========================================");
//         }
//         return success;
//     }

//     /**
//      * Finds project/outputs/<fileName> by checking the working directory and
//      * walking up through its parents. This works whether the demo is started
//      * from the repository root, from modules/, or from an IDE module folder
//      * (the original code only looked in the parent of the working directory).
//      */
//     private static Path locateScoresCsv(String fileName) {
//         Path dir = Paths.get("").toAbsolutePath();
//         while (dir != null) {
//             Path candidate = dir.resolve("project/outputs").resolve(fileName);
//             if (Files.exists(candidate)) {
//                 return candidate;
//             }
//             dir = dir.getParent();
//         }
//         return null;
//     }

//     private static Host createHost(int id, int mips, int ram) {
//         List<Pe> peList = new ArrayList<>();
//         peList.add(new Pe(0, new PeProvisionerSimple(mips)));
//         return new Host(id, new RamProvisionerSimple(ram),
//                 new BwProvisionerSimple(100000), 1000000, peList,
//                 new VmSchedulerSpaceShared(peList));
//     }

//     private static Vm createVm(int id, int mips, int ram) {
//         return new Vm(id, 0, mips, 1, ram, 100, 1000, "Xen", null);
//     }
// }


















package org.cloudbus.cloudsim.examples.stage2k;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.examples.stage2b.ThreatRecord;
import org.cloudbus.cloudsim.examples.stage2b.ThreatScoreCsvReader;
import org.cloudbus.cloudsim.examples.stage2c.ServerRiskModel;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
import org.cloudbus.cloudsim.examples.stage2e.AdaptiveThreatThreshold;
import org.cloudbus.cloudsim.examples.stage2g.NssaoConfig;
import org.cloudbus.cloudsim.examples.stage2h.Stage2hCloudSimIntegration;
import org.cloudbus.cloudsim.examples.stage2h.SimulationMetrics;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Stage 2K — Final Demonstration Entry Point
 *
 * Demonstrates the complete research pipeline using real threat scores
 * and the actual adaptive threshold implementation (NOT hardcoded 0.5/0.6).
 *
 * Uses existing Stage 2H CloudSimIntegration orchestrator unchanged.
 *
 * Usage: Stage2kDemo [dataset] [selection]
 *
 *   dataset   (default "azure")
 *     azure    -> Azure V2 only      (original behaviour)
 *     alibaba  -> Alibaba V2018 only
 *     both     -> Azure, then Alibaba, in the same run
 *
 *   selection (default "moderate"): which 10 entities become the demo VMs
 *     moderate -> 10 entities starting at the 40th percentile of threat
 *                 score (original behaviour; yields NO high-threat VMs)
 *     mixed    -> 10 entities evenly spread from the 40th percentile up
 *                 to the maximum score: a realistic mix of normal and
 *                 high-threat VMs
 *     high     -> the 10 highest-scoring entities (same idea as the
 *                 Stage 2I "profile C": every VM is a likely threat)
 */
public final class Stage2kDemo {

    private static final int DEMO_VM_COUNT = 10;
    private static final long SEED = 20260911L;

    /** Supported datasets: key -> (display name, score CSV under project/outputs/). */
    private enum Dataset {
        AZURE("azure", "Azure V2", "azure_to_azure_scores.csv"),
        ALIBABA("alibaba", "Alibaba V2018", "alibaba_to_alibaba_scores.csv");

        final String key;
        final String displayName;
        final String csvFile;

        Dataset(String key, String displayName, String csvFile) {
            this.key = key;
            this.displayName = displayName;
            this.csvFile = csvFile;
        }
    }

    /** How the demo VMs are chosen from the score-sorted entity list. */
    private enum Selection {
        MODERATE("moderate", "moderate pressure: 10 entities from the 40th percentile"),
        MIXED("mixed", "mixed pressure: 10 entities spread from the 40th percentile to the maximum"),
        HIGH("high", "high pressure: the 10 highest-scoring entities");

        final String key;
        final String description;

        Selection(String key, String description) {
            this.key = key;
            this.description = description;
        }
    }

    public static void main(String[] args) {
        String choice = (args.length > 0) ? args[0].trim().toLowerCase(Locale.ROOT) : "azure";

        List<Dataset> toRun = new ArrayList<>();
        switch (choice) {
            case "azure":
                toRun.add(Dataset.AZURE);
                break;
            case "alibaba":
                toRun.add(Dataset.ALIBABA);
                break;
            case "both":
                toRun.add(Dataset.AZURE);
                toRun.add(Dataset.ALIBABA);
                break;
            default:
                System.err.println("Unknown dataset '" + choice
                        + "'. Use: azure | alibaba | both");
                System.exit(2);
                return;
        }

        String selChoice = (args.length > 1) ? args[1].trim().toLowerCase(Locale.ROOT) : "moderate";
        Selection selection = null;
        for (Selection sel : Selection.values()) {
            if (sel.key.equals(selChoice)) {
                selection = sel;
            }
        }
        if (selection == null) {
            System.err.println("Unknown selection '" + selChoice
                    + "'. Use: moderate | mixed | high");
            System.exit(2);
            return;
        }

        boolean allOk = true;
        Map<Dataset, Boolean> outcome = new LinkedHashMap<>();
        for (Dataset ds : toRun) {
            boolean ok;
            try {
                ok = runDemo(ds, selection);
            } catch (Exception e) {
                Log.println("========================================");
                Log.println("STAGE 2K DEMONSTRATION (" + ds.displayName + "): EXCEPTION");
                Log.println("========================================");
                e.printStackTrace();
                ok = false;
            }
            outcome.put(ds, ok);
            allOk &= ok;
            Log.println();
        }

        if (toRun.size() > 1) {
            Log.println("========================================");
            Log.println("STAGE 2K OVERALL");
            for (Map.Entry<Dataset, Boolean> e : outcome.entrySet()) {
                Log.println("   " + e.getKey().displayName + ": "
                        + (e.getValue() ? "SUCCESS" : "FAILED"));
            }
            Log.println("========================================");
        }

        if (!allOk) {
            System.exit(1);
        }
    }

    /**
     * Runs the full Stage 2K pipeline for one dataset.
     *
     * @return true if all verification checks passed
     */
    private static boolean runDemo(Dataset ds, Selection selection) throws Exception {
        Log.println("========================================");
        Log.println("STAGE 2K — FINAL DEMONSTRATION [" + ds.displayName + "]");
        Log.println("Complete Research Pipeline End-to-End");
        Log.println("========================================");
        Log.println();

        // === 1. Load real threat scores ===
        Log.println("1. LOADING REAL THREAT SCORES");
        Path csvPath = locateScoresCsv(ds.csvFile);

        if (csvPath == null) {
            Log.println("ERROR: " + ds.csvFile + " not found under project/outputs "
                    + "(searched from working dir " + Paths.get("").toAbsolutePath() + " upwards)");
            return false;
        }

        List<ThreatRecord> allRecords = new ThreatScoreCsvReader(csvPath).read();
        Log.println("   Dataset: " + ds.displayName);
        Log.println("   Source file: " + csvPath);
        Log.println("   Total entities: " + allRecords.size());

        if (allRecords.isEmpty()) {
            Log.println("ERROR: no records loaded");
            return false;
        }

        // Select moderate-score subset (use wider range for better threshold)
        List<ThreatRecord> sorted = new ArrayList<>(allRecords);
        sorted.sort(Comparator.comparingDouble(ThreatRecord::getThreatScore));
        List<ThreatRecord> selected = selectEntities(sorted, selection);

        Log.println("   Selection mode: " + selection.key + " (" + selection.description + ")");
        Log.println("   Selected score-ranks (0 = lowest): " + describeRanks(sorted, selected));
        Log.println("   Demo VMs: " + selected.size());
        Log.println();

        // === 2. Compute adaptive threshold ===
        Log.println("2. ADAPTIVE THRESHOLD (Stage 2E)");
        List<Double> scores = selected.stream()
                .map(ThreatRecord::getThreatScore)
                .collect(Collectors.toList());

        AdaptiveThreatThreshold calc = new AdaptiveThreatThreshold();
        double threshold = calc.compute(scores);

        Log.println("   Score range: [" + String.format("%.6f", Collections.min(scores))
                + ", " + String.format("%.6f", Collections.max(scores)) + "]");
        Log.println("   Computed threshold (Stage 2E): " + String.format("%.9f", threshold));
        Log.println("   NOTE: This is computed from real " + ds.displayName
                + " scores, NOT hardcoded");

        long highThreat = scores.stream().filter(s -> s >= threshold).count();
        Log.println("   High-threat: " + highThreat + "/" + scores.size()
                + " (" + String.format("%.1f%%", 100.0 * highThreat / scores.size()) + ")");
        Log.println();

        // === 3. Initialize CloudSim ===
        // CloudSim.init() resets the simulation's static state, so it is safe
        // to call once per dataset when running "both".
        CloudSim.init(1, Calendar.getInstance(), false);

        // === 4. Build hosts ===
        Log.println("3. CREATING HOSTS");
        List<Host> hosts = new ArrayList<>();
        hosts.add(createHost(0, 2000, 4096)); // Large
        hosts.add(createHost(1, 1000, 2048)); // Small
        hosts.add(createHost(2, 1000, 2048)); // Small

        for (Host h : hosts) {
            Log.println("   Host " + h.getId() + ": " + h.getTotalMips()
                    + " MIPS, " + h.getRam() + " MB");
        }
        Log.println();

        // === 5. Create VMs with threat metadata ===
        Log.println("4. CREATING VMs WITH THREAT METADATA");
        List<Vm> vms = new ArrayList<>();
        Map<Integer, VmThreatState> threatMap = new LinkedHashMap<>();

        int vmId = 100;
        for (ThreatRecord record : selected) {
            vms.add(createVm(vmId, 250, 256));

            // CRITICAL: Use adaptive threshold, NOT hardcoded 0.5 or 0.6
            threatMap.put(vmId, new VmThreatState(
                    vmId,
                    record.getThreatScore(),
                    record.getDataset(),
                    record.getEntityType(),
                    record.getEntityId(),
                    threshold  // <-- ADAPTIVE
            ));
            vmId++;
        }

        Log.println("   VMs created: " + vms.size());
        Log.println("   Classification threshold: " + String.format("%.9f", threshold));

        long highThreatVms = threatMap.values().stream()
                .filter(VmThreatState::isHighThreat).count();
        Log.println("   High-threat VMs: " + highThreatVms + "/" + vms.size());
        Log.println();

        // === 6. Run complete pipeline via Stage 2H orchestrator ===
        Log.println("5. RUNNING COMPLETE PIPELINE (Stage 2H)");
        Log.println("   FFD → NSSAO → Pareto selection → Migration → Metrics");
        Log.println();

        ServerRiskModel riskModel = new ServerRiskModel(0.1, 0.3);
        NssaoConfig nssaoConfig = NssaoConfig.builder()
                .seed(SEED)
                .populationSize(20)
                .maxIterations(8)
                .stallLimit(3)
                .build();

        Log.println("   NSSAO config: N=" + nssaoConfig.getPopulationSize()
                + ", t_max=" + nssaoConfig.getMaxIterations()
                + ", seed=" + nssaoConfig.getSeed());

        Stage2hCloudSimIntegration orchestrator = new Stage2hCloudSimIntegration(
                hosts, vms, threatMap, riskModel, 1.0, 1.0, nssaoConfig);

        orchestrator.run();

        // === 7. Display results ===
        Log.println();
        Log.println("6. RESULTS [" + ds.displayName + "]");
        SimulationMetrics m = orchestrator.getMetrics();
        Log.println("   Objectives (final state):");
        Log.println("      f1 (threat×server-risk): "
                + String.format("%.6f", m.getF1ThreatServerRisk()));
        Log.println("      f2 (co-residency risk):  "
                + String.format("%.6f", m.getF2CoResidencyRisk()));
        Log.println("      f3 (defensive cost):     "
                + String.format("%.6f", m.getF3Cost()));
        Log.println("      f4 (unused capacity):    "
                + String.format("%.6f", m.getF4UnusedCapacity()));
        Log.println();

        Log.println("   Placement:");
        Log.println("      Active hosts: " + m.getActiveHostCount());
        Log.println("      Migrations: " + m.getMigrationCount()
                + " (cost: " + String.format("%.1f", m.getMigrationCost()) + ")");
        Log.println("      CPU utilization: "
                + String.format("%.1f%%", m.getCpuUtilization() * 100));
        Log.println("      Memory utilization: "
                + String.format("%.1f%%", m.getMemoryUtilization() * 100));
        Log.println();

        Log.println("   Co-residency duration:");
        Log.println("      Total: " + String.format("%.1f", m.getTotalCoResidencyDuration()));
        Log.println("      High-threat: " + String.format("%.1f", m.getHighThreatCoResidencyDuration()));
        Log.println("      Episodes: " + m.getCoResidencyEpisodeCount());
        Log.println();

        Log.println("   NSSAO:");
        Log.println("      Iterations: " + m.getIterationsExecuted());
        Log.println("      Stopped by stall: " + m.isStoppedByStall());
        Log.println("      Front-1 size: " + m.getFront1RawSize());
        Log.println("      Unique placements: " + m.getUniquePlacements());
        Log.println();

        // === 8. Verify ===
        boolean success = true;

        // Threshold should be in valid range [0.5, 0.9] - hitting boundary is OK
        if (threshold < 0.5 || threshold > 0.9) {
            Log.println("VERIFICATION FAILED: Threshold " + threshold
                    + " outside allowed range [0.5, 0.9]");
            success = false;
        }

        // Hitting the exact boundary (0.5 or 0.9) is valid:
        // - 0.5 when raw threshold < 0.5 (clamped)
        // - 0.9 when raw threshold > 0.9 (clamped)
        if (threshold == 0.5) {
            Log.println("   NOTE: Threshold hit lower bound (0.5) - valid boundary case");
        } else if (threshold == 0.9) {
            Log.println("   NOTE: Threshold hit upper bound (0.9) - valid boundary case");
        }

        if (!Double.isFinite(m.getF1ThreatServerRisk()) || !Double.isFinite(m.getF2CoResidencyRisk())
                || !Double.isFinite(m.getF3Cost()) || !Double.isFinite(m.getF4UnusedCapacity())) {
            Log.println("VERIFICATION FAILED: Non-finite objectives");
            success = false;
        }

        if (m.getIterationsExecuted() < 1) {
            Log.println("VERIFICATION FAILED: NSSAO did not run");
            success = false;
        }

        if (success) {
            Log.println("========================================");
            Log.println("STAGE 2K DEMONSTRATION [" + ds.displayName + "]: SUCCESS");
            Log.println("========================================");
            Log.println();
            Log.println("SUMMARY:");
            Log.println("✓ Real " + ds.displayName + " threat scores loaded");
            Log.println("✓ Adaptive threshold: " + String.format("%.9f", threshold));
            Log.println("✓ " + highThreatVms + "/" + vms.size() + " VMs classified as high-threat");
            Log.println("✓ NSSAO ran " + m.getIterationsExecuted() + " iterations");
            Log.println("✓ " + m.getMigrationCount() + " migrations executed");
            Log.println("✓ Final objectives computed");
            Log.println("✓ Co-residency tracked across " + m.getCoResidencyEpisodeCount() + " episodes");
        } else {
            Log.println("========================================");
            Log.println("STAGE 2K DEMONSTRATION [" + ds.displayName + "]: FAILED");
            Log.println("========================================");
        }
        return success;
    }

    /**
     * Picks the demo entities from the ascending-by-threat-score list.
     */
    private static List<ThreatRecord> selectEntities(List<ThreatRecord> sorted, Selection selection) {
        int size = sorted.size();
        int n = Math.min(DEMO_VM_COUNT, size);
        int start40 = size * 2 / 5;                 // 40th percentile rank

        switch (selection) {
            case HIGH:
                // The n highest-scoring entities (ascending order kept).
                return new ArrayList<>(sorted.subList(size - n, size));

            case MIXED: {
                // n ranks evenly spaced from the 40th percentile to the top.
                // First pick is the 40th-percentile entity, last is the maximum.
                List<ThreatRecord> picked = new ArrayList<>();
                int lastRank = size - 1;
                int firstRank = Math.min(start40, lastRank);
                for (int i = 0; i < n; i++) {
                    int rank = (n == 1)
                            ? firstRank
                            : firstRank + (int) Math.round((double) i * (lastRank - firstRank) / (n - 1));
                    picked.add(sorted.get(rank));
                }
                return picked;
            }

            case MODERATE:
            default: {
                // Original behaviour: n consecutive entities from the 40th percentile.
                int end = Math.min(start40 + n, size);
                return new ArrayList<>(sorted.subList(start40, end));
            }
        }
    }

    /** Human-readable rank list, e.g. "99..108" or "99, 115, 131, ...". */
    private static String describeRanks(List<ThreatRecord> sorted, List<ThreatRecord> selected) {
        List<Integer> ranks = new ArrayList<>();
        for (ThreatRecord r : selected) {
            ranks.add(sorted.indexOf(r));
        }
        boolean consecutive = true;
        for (int i = 1; i < ranks.size(); i++) {
            if (ranks.get(i) != ranks.get(i - 1) + 1) {
                consecutive = false;
            }
        }
        if (consecutive && !ranks.isEmpty()) {
            return ranks.get(0) + ".." + ranks.get(ranks.size() - 1)
                    + " of " + sorted.size();
        }
        return ranks + " of " + sorted.size();
    }

    /**
     * Finds project/outputs/<fileName> by checking the working directory and
     * walking up through its parents. This works whether the demo is started
     * from the repository root, from modules/, or from an IDE module folder
     * (the original code only looked in the parent of the working directory).
     */
    private static Path locateScoresCsv(String fileName) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("project/outputs").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Host createHost(int id, int mips, int ram) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));
        return new Host(id, new RamProvisionerSimple(ram),
                new BwProvisionerSimple(100000), 1000000, peList,
                new VmSchedulerSpaceShared(peList));
    }

    private static Vm createVm(int id, int mips, int ram) {
        return new Vm(id, 0, mips, 1, ram, 100, 1000, "Xen", null);
    }
}
