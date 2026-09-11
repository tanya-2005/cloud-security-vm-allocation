package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2d.CoResidencyDurationTracker;
import org.cloudbus.cloudsim.examples.stage2d.MigrationCostModel;
import org.cloudbus.cloudsim.examples.stage2d.VmMigrationService;

import java.util.List;
import java.util.Map;

/**
 * Security state snapshot for Stage 2C.
 *
 * <p>This captures the measurable CloudSim security state at a point
 * in time. It is designed to provide the inputs needed by future stages:
 * <ul>
 *   <li>Threat-aware placement (Stage 2D+)</li>
 *   <li>Adaptive thresholding (Stage 2D+)</li>
 *   <li>NSSAO (Stage 2E+)</li>
 *   <li>Migration (Stage 2D+)</li>
 *   <li>Final multi-objective evaluation (Stage 3+)</li>
 * </ul>
 *
 * <p>All values are derived from actual CloudSim simulation state
 * plus the Stage 2B threat metadata. No fabrication.
 */
public final class SecurityStateSnapshot {

    // === VM counts ===
    private final int totalVmCount;
    private final int activeHostCount;
    private final int highThreatVmCount;
    private final double highThreatFraction;

    // === Resource utilization ===
    private final double averageCpuUtilization;
    private final double averageMemoryUtilization;
    private final double averageOverallUtilization;
    private final double totalAllocatedMips;
    private final double totalHostMipsCapacity;

    // === Co-residency ===
    private final int crossEntityPairCount;
    private final int hostsWithCrossEntityCoResidency;
    private final boolean hasHighThreatWithOtherEntity;
    private final CoResidencyRiskCalculator.CoResidencyRiskMetrics coResidencyRisk;

    // === Server/host risk ===
    private final double averageHostRisk;
    private final double totalHostRisk;

    // === Defensive cost ===
    private final DefensiveCostModel.CostBreakdown costBreakdown;

    // === Co-residency duration ===
    private final CoResidencyDurationInfo durationInfo;
    private final double totalCoResidencyDuration;
    private final double totalHighThreatCoResidencyDuration;
    private final double totalNormalCoResidencyDuration;
    private final int totalCoResidencyEpisodes;
    private final int highThreatCoResidencyEpisodes;
    private final double migrationCount;
    private final double totalMigrationCost;

    // === Overall VM risk summary ===
    private final double averageVmThreatScore;
    private final double maxVmThreatScore;
    private final double minVmThreatScore;
    private final OverallVmRiskMetrics overallVmRisk;

    public SecurityStateSnapshot(List<Host> hosts,
                                 Map<Integer, VmThreatState> vmThreatStateMap,
                                 ServerRiskModel serverRiskModel,
                                 DefensiveCostModel costModel,
                                 double highThreatThreshold) {
        this(hosts, vmThreatStateMap, serverRiskModel, costModel,
             highThreatThreshold, null, null, null, 0.0, 0.0);
    }

    /**
     * Full constructor including migration and co-residency duration data.
     * Used by Stage 2D and later stages.
     */
    public SecurityStateSnapshot(List<Host> hosts,
                                 Map<Integer, VmThreatState> vmThreatStateMap,
                                 ServerRiskModel serverRiskModel,
                                 DefensiveCostModel costModel,
                                 double highThreatThreshold,
                                 CoResidencyDurationTracker durationTracker,
                                 VmMigrationService migrationService,
                                 MigrationCostModel migrationCostModel,
                                 double totalCoResidencyDuration,
                                 double totalHighThreatCoResidencyDuration) {
        // Basic counts
        this.activeHostCount = countActiveHosts(hosts);
        this.totalVmCount = countTotalVms(hosts);

        // High-threat VMs
        this.highThreatVmCount = countHighThreatVms(vmThreatStateMap);
        this.highThreatFraction = totalVmCount > 0
                ? (double) highThreatVmCount / (double) totalVmCount
                : 0.0;

        // Resource utilization
        this.averageCpuUtilization = ResourceUtilizationCalculator
                .calculateAverageCpuUtilization(hosts);
        this.averageMemoryUtilization = ResourceUtilizationCalculator
                .calculateAverageMemoryUtilization(hosts);
        this.averageOverallUtilization = ResourceUtilizationCalculator
                .calculateAverageOverallUtilization(hosts);
        this.totalAllocatedMips = ResourceUtilizationCalculator
                .getTotalAllocatedMips(hosts);
        this.totalHostMipsCapacity = ResourceUtilizationCalculator
                .getTotalHostMipsCapacity(hosts);

        // Co-residency detection
        List<CoResidencyDetector.CrossEntityPair> allPairs =
                CoResidencyDetector.getAllCrossEntityPairs(hosts, vmThreatStateMap);
        this.crossEntityPairCount = allPairs.size();
        this.hostsWithCrossEntityCoResidency = countHostsWithCrossEntityCoResidency(hosts, vmThreatStateMap);
        this.hasHighThreatWithOtherEntity = CoResidencyDetector
                .hasHighThreatWithOtherEntity(hosts, vmThreatStateMap);
        this.coResidencyRisk = CoResidencyRiskCalculator
                .calculateMetrics(allPairs);

        // Server/host risk
        this.averageHostRisk = serverRiskModel.calculateAverageHostRisk(hosts);
        this.totalHostRisk = serverRiskModel.calculateTotalHostRisk(hosts);

        // Defensive cost
        this.costBreakdown = costModel.getCostBreakdown(hosts, 0);

        // Co-residency duration
        if (durationTracker != null && durationTracker.getEpisodes().size() > 0) {
            this.durationInfo = CoResidencyDurationInfo.available(
                    "Co-residency duration is measured from simulated CloudSim "
                            + "placement/migration events, not inferred from Azure or Alibaba "
                            + "trace timestamps.");
            this.totalCoResidencyDuration = durationTracker.getTotalCoResidencyDuration();
            this.totalHighThreatCoResidencyDuration = durationTracker.getTotalHighThreatCoResidencyDuration();
            this.totalNormalCoResidencyDuration = durationTracker.getTotalNormalCoResidencyDuration();
            this.totalCoResidencyEpisodes = durationTracker.getEpisodes().size();
            this.highThreatCoResidencyEpisodes = durationTracker.getEpisodesByType(
                    org.cloudbus.cloudsim.examples.stage2d.CoResidencyDurationTracker.CoResidencyType.HIGH_THREAT_NORMAL).size()
                    + durationTracker.getEpisodesByType(
                            org.cloudbus.cloudsim.examples.stage2d.CoResidencyDurationTracker.CoResidencyType.HIGH_THREAT_HIGH_THREAT).size();
        } else {
            this.durationInfo = CoResidencyDurationInfo.notAvailableStaticSnapshot();
            this.totalCoResidencyDuration = 0.0;
            this.totalHighThreatCoResidencyDuration = 0.0;
            this.totalNormalCoResidencyDuration = 0.0;
            this.totalCoResidencyEpisodes = 0;
            this.highThreatCoResidencyEpisodes = 0;
        }

        // Migration cost
        if (migrationService != null && migrationCostModel != null) {
            this.migrationCount = migrationService.getMigrationCount();
            MigrationCostModel.MigrationCostBreakdown breakdown =
                    migrationCostModel.getCostBreakdownFromService(migrationService);
            this.totalMigrationCost = breakdown.getTotalCost();
        } else {
            this.migrationCount = 0;
            this.totalMigrationCost = 0.0;
        }

        // Overall VM risk summary
        this.averageVmThreatScore = calculateAverageVmThreatScore(vmThreatStateMap);
        this.maxVmThreatScore = calculateMaxVmThreatScore(vmThreatStateMap);
        this.minVmThreatScore = calculateMinVmThreatScore(vmThreatStateMap);
        this.overallVmRisk = OverallVmRiskMetrics.fromThreatScores(
                averageVmThreatScore, maxVmThreatScore, minVmThreatScore,
                highThreatFraction, coResidencyRisk.getTotalRisk());
    }

    // === Count helpers ===

    private static int countActiveHosts(List<Host> hosts) {
        if (hosts == null) return 0;
        int count = 0;
        for (Host host : hosts) {
            if (!host.isFailed()) {
                count++;
            }
        }
        return count;
    }

    private static int countTotalVms(List<Host> hosts) {
        int count = 0;
        if (hosts == null) return 0;
        for (Host host : hosts) {
            count += host.getGuestList().size();
        }
        return count;
    }

    private static int countHighThreatVms(Map<Integer, VmThreatState> vmThreatStateMap) {
        if (vmThreatStateMap == null) return 0;
        int count = 0;
        for (VmThreatState state : vmThreatStateMap.values()) {
            if (state.isHighThreat()) {
                count++;
            }
        }
        return count;
    }

    private static int countHostsWithCrossEntityCoResidency(List<Host> hosts,
                                                            Map<Integer, VmThreatState> vmThreatStateMap) {
        int count = 0;
        if (hosts == null) return 0;
        for (Host host : hosts) {
            if (CoResidencyDetector.detectHost(host, vmThreatStateMap)
                    .getCrossEntityPairCount() > 0) {
                count++;
            }
        }
        return count;
    }

    private static double calculateAverageVmThreatScore(Map<Integer, VmThreatState> vmThreatStateMap) {
        if (vmThreatStateMap == null || vmThreatStateMap.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (VmThreatState state : vmThreatStateMap.values()) {
            sum += state.getThreatScore();
        }
        return sum / vmThreatStateMap.size();
    }

    private static double calculateMaxVmThreatScore(Map<Integer, VmThreatState> vmThreatStateMap) {
        if (vmThreatStateMap == null || vmThreatStateMap.isEmpty()) {
            return 0.0;
        }
        double max = 0.0;
        for (VmThreatState state : vmThreatStateMap.values()) {
            if (state.getThreatScore() > max) {
                max = state.getThreatScore();
            }
        }
        return max;
    }

    private static double calculateMinVmThreatScore(Map<Integer, VmThreatState> vmThreatStateMap) {
        if (vmThreatStateMap == null || vmThreatStateMap.isEmpty()) {
            return 0.0;
        }
        double min = 1.0;
        for (VmThreatState state : vmThreatStateMap.values()) {
            if (state.getThreatScore() < min) {
                min = state.getThreatScore();
            }
        }
        return min;
    }

    // === Getters ===

    public int getTotalVmCount() {
        return totalVmCount;
    }

    public int getActiveHostCount() {
        return activeHostCount;
    }

    public int getHighThreatVmCount() {
        return highThreatVmCount;
    }

    public double getHighThreatFraction() {
        return highThreatFraction;
    }

    public double getAverageCpuUtilization() {
        return averageCpuUtilization;
    }

    public double getAverageMemoryUtilization() {
        return averageMemoryUtilization;
    }

    public double getAverageOverallUtilization() {
        return averageOverallUtilization;
    }

    public double getTotalAllocatedMips() {
        return totalAllocatedMips;
    }

    public double getTotalHostMipsCapacity() {
        return totalHostMipsCapacity;
    }

    public int getCrossEntityPairCount() {
        return crossEntityPairCount;
    }

    public int getHostsWithCrossEntityCoResidency() {
        return hostsWithCrossEntityCoResidency;
    }

    public boolean isHasHighThreatWithOtherEntity() {
        return hasHighThreatWithOtherEntity;
    }

    public CoResidencyRiskCalculator.CoResidencyRiskMetrics getCoResidencyRisk() {
        return coResidencyRisk;
    }

    public double getAverageHostRisk() {
        return averageHostRisk;
    }

    public double getTotalHostRisk() {
        return totalHostRisk;
    }

    public DefensiveCostModel.CostBreakdown getCostBreakdown() {
        return costBreakdown;
    }

    public CoResidencyDurationInfo getDurationInfo() {
        return durationInfo;
    }

    public double getTotalCoResidencyDuration() {
        return totalCoResidencyDuration;
    }

    public double getTotalHighThreatCoResidencyDuration() {
        return totalHighThreatCoResidencyDuration;
    }

    public double getTotalNormalCoResidencyDuration() {
        return totalNormalCoResidencyDuration;
    }

    public int getTotalCoResidencyEpisodes() {
        return totalCoResidencyEpisodes;
    }

    public int getHighThreatCoResidencyEpisodes() {
        return highThreatCoResidencyEpisodes;
    }

    public double getMigrationCount() {
        return migrationCount;
    }

    public double getTotalMigrationCost() {
        return totalMigrationCost;
    }

    public double getAverageVmThreatScore() {
        return averageVmThreatScore;
    }

    public double getMaxVmThreatScore() {
        return maxVmThreatScore;
    }

    public double getMinVmThreatScore() {
        return minVmThreatScore;
    }

    public OverallVmRiskMetrics getOverallVmRisk() {
        return overallVmRisk;
    }

    /**
     * Print a human-readable snapshot summary.
     */
    public void printSummary(String title) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("SECURITY STATE SNAPSHOT: " + title);
        System.out.println("========================================");
        System.out.println("VM/Host Counts:");
        System.out.println("  Total VMs: " + totalVmCount);
        System.out.println("  Active hosts: " + activeHostCount);
        System.out.println("  High-threat VMs: " + highThreatVmCount
                + " (fraction: " + String.format("%.4f", highThreatFraction) + ")");
        System.out.println();
        System.out.println("Resource Utilization ( averages across hosts):");
        System.out.println("  CPU utilization: "
                + String.format("%.4f", averageCpuUtilization));
        System.out.println("  Memory utilization: "
                + String.format("%.4f", averageMemoryUtilization));
        System.out.println("  Overall utilization: "
                + String.format("%.4f", averageOverallUtilization));
        System.out.println("  Total allocated MIPS: "
                + String.format("%.1f", totalAllocatedMips)
                + " / " + String.format("%.1f", totalHostMipsCapacity));
        System.out.println();
        System.out.println("Co-residency:");
        System.out.println("  Cross-entity pairs: " + crossEntityPairCount);
        System.out.println("  Hosts with cross-entity co-residency: "
                + hostsWithCrossEntityCoResidency);
        System.out.println("  High-threat + other-entity co-residency: "
                + hasHighThreatWithOtherEntity);
        System.out.println("  Co-residency risk: " + coResidencyRisk);
        System.out.println();
        System.out.println("Server/Host Risk:");
        System.out.println("  Average host risk: "
                + String.format("%.4f", averageHostRisk));
        System.out.println("  Total host risk: "
                + String.format("%.4f", totalHostRisk));
        System.out.println();
        System.out.println("Defensive Cost:");
        System.out.println("  " + costBreakdown);
        System.out.println();
        System.out.println("VM Threat Scores:");
        System.out.println("  Average: "
                + String.format("%.4f", averageVmThreatScore));
        System.out.println("  Max: " + String.format("%.4f", maxVmThreatScore));
        System.out.println("  Min: " + String.format("%.4f", minVmThreatScore));
        System.out.println("  Overall VM risk: " + overallVmRisk);
        System.out.println();
        System.out.println("Co-residency Duration:");
        System.out.println("  " + durationInfo);
        if (durationInfo.isAvailable()) {
            System.out.println("  Total duration: " + String.format("%.4f", totalCoResidencyDuration));
            System.out.println("  Total episodes: " + totalCoResidencyEpisodes);
            System.out.println("  High-threat episodes: " + highThreatCoResidencyEpisodes);
            System.out.println("  High-threat duration: " + String.format("%.4f", totalHighThreatCoResidencyDuration));
            System.out.println("  Normal duration: " + String.format("%.4f", totalNormalCoResidencyDuration));
        }
        System.out.println();
        System.out.println("Migration:");
        System.out.println("  Migration count: " + migrationCount);
        System.out.println("  Total migration cost: " + String.format("%.4f", totalMigrationCost));
        System.out.println("========================================");
        System.out.println();
    }

    /**
     * Information about co-residency duration availability.
     */
    public static final class CoResidencyDurationInfo {
        private final boolean isAvailable;
        private final String status;

        private CoResidencyDurationInfo(boolean isAvailable, String status) {
            this.isAvailable = isAvailable;
            this.status = status;
        }

        public static CoResidencyDurationInfo notAvailableStaticSnapshot() {
            return new CoResidencyDurationInfo(false,
                    "NOT AVAILABLE: Static snapshot has no temporal data. "
                            + "Migration events are not implemented in Stage 2C. "
                            + "Co-residency duration tracking requires migration lifecycle (Stage 2D+).");
        }

        public static CoResidencyDurationInfo available(String status) {
            return new CoResidencyDurationInfo(true, status);
        }

        public boolean isAvailable() {
            return isAvailable;
        }

        public String getStatus() {
            return status;
        }

        @Override
        public String toString() {
            return status;
        }
    }

    /**
     * Overall VM risk summary combining threat scores and co-residency.
     */
    public static final class OverallVmRiskMetrics {
        private final double averageThreatScore;
        private final double maxThreatScore;
        private final double minThreatScore;
        private final double highThreatFraction;
        private final double coResidencyRiskContribution;
        private final double totalRiskScore;

        private OverallVmRiskMetrics(double averageThreatScore, double maxThreatScore,
                                     double minThreatScore, double highThreatFraction,
                                     double coResidencyRiskContribution) {
            this.averageThreatScore = averageThreatScore;
            this.maxThreatScore = maxThreatScore;
            this.minThreatScore = minThreatScore;
            this.highThreatFraction = highThreatFraction;
            this.coResidencyRiskContribution = coResidencyRiskContribution;
            // Simple composite: average threat + co-residency contribution
            this.totalRiskScore = averageThreatScore + 0.3 * coResidencyRiskContribution;
        }

        public static OverallVmRiskMetrics fromThreatScores(
                double averageThreat, double maxThreat, double minThreat,
                double highThreatFraction, double coResidencyRisk) {
            return new OverallVmRiskMetrics(averageThreat, maxThreat, minThreat,
                    highThreatFraction, coResidencyRisk);
        }

        public double getAverageThreatScore() {
            return averageThreatScore;
        }

        public double getMaxThreatScore() {
            return maxThreatScore;
        }

        public double getMinThreatScore() {
            return minThreatScore;
        }

        public double getHighThreatFraction() {
            return highThreatFraction;
        }

        public double getCoResidencyRiskContribution() {
            return coResidencyRiskContribution;
        }

        public double getTotalRiskScore() {
            return totalRiskScore;
        }

        @Override
        public String toString() {
            return "OverallVmRiskMetrics{" +
                    "avgThreat=" + String.format("%.4f", averageThreatScore) +
                    ", maxThreat=" + String.format("%.4f", maxThreatScore) +
                    ", minThreat=" + String.format("%.4f", minThreatScore) +
                    ", highThreatFrac=" + String.format("%.4f", highThreatFraction) +
                    ", coResidencyRisk=" + String.format("%.4f", coResidencyRiskContribution) +
                    ", totalRisk=" + String.format("%.4f", totalRiskScore) +
                    '}';
        }
    }
}
