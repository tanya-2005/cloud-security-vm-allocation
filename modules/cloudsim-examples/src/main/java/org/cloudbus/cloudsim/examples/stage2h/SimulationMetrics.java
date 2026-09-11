package org.cloudbus.cloudsim.examples.stage2h;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2d.CoResidencyDurationTracker;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects and reports final metrics from the end-to-end simulation.
 *
 * <p>Metrics include:
 * <ul>
 *   <li>Objective values (f1, f2, f3, f4)</li>
 *   <li>Placement statistics (active hosts, utilization)</li>
 *   <li>Migration statistics (count, cost)</li>
 *   <li>Co-residency duration (from Stage 2D tracker)</li>
 *   <li>NSSAO statistics (iterations, stall, rejected decodes, front size)</li>
 *   <li>Repair rate (from decoder statistics)</li>
 * </ul>
 *
 * <p>Per Stage 2H design, all metrics are immutable post-collection.
 */
public final class SimulationMetrics {

    // Objective values
    private final double f1ThreatServerRisk;
    private final double f2CoResidencyRisk;
    private final double f3Cost;
    private final double f4UnusedCapacity;

    // Placement statistics
    private final int activeHostCount;
    private final double cpuUtilization;
    private final double memoryUtilization;
    private final double overallUtilization;

    // Migration statistics
    private final int migrationCount;
    private final double migrationCost;

    // Co-residency duration (Stage 2D)
    private final double totalCoResidencyDuration;
    private final double highThreatCoResidencyDuration;
    private final int coResidencyEpisodeCount;

    // NSSAO statistics
    private final int iterationsExecuted;
    private final boolean stoppedByStall;
    private final int rejectedDecodeCount;
    private final int front1RawSize;
    private final int uniquePlacements;
    private final int duplicatesRemoved;

    // Repair statistics
    private final int repairCount;
    private final double repairRate;

    private SimulationMetrics(Builder builder) {
        this.f1ThreatServerRisk = builder.f1ThreatServerRisk;
        this.f2CoResidencyRisk = builder.f2CoResidencyRisk;
        this.f3Cost = builder.f3Cost;
        this.f4UnusedCapacity = builder.f4UnusedCapacity;
        this.activeHostCount = builder.activeHostCount;
        this.cpuUtilization = builder.cpuUtilization;
        this.memoryUtilization = builder.memoryUtilization;
        this.overallUtilization = builder.overallUtilization;
        this.migrationCount = builder.migrationCount;
        this.migrationCost = builder.migrationCost;
        this.totalCoResidencyDuration = builder.totalCoResidencyDuration;
        this.highThreatCoResidencyDuration = builder.highThreatCoResidencyDuration;
        this.coResidencyEpisodeCount = builder.coResidencyEpisodeCount;
        this.iterationsExecuted = builder.iterationsExecuted;
        this.stoppedByStall = builder.stoppedByStall;
        this.rejectedDecodeCount = builder.rejectedDecodeCount;
        this.front1RawSize = builder.front1RawSize;
        this.uniquePlacements = builder.uniquePlacements;
        this.duplicatesRemoved = builder.duplicatesRemoved;
        this.repairCount = builder.repairCount;
        this.repairRate = builder.repairRate;
    }

    // === Objective values ===
    public double getF1ThreatServerRisk() { return f1ThreatServerRisk; }
    public double getF2CoResidencyRisk() { return f2CoResidencyRisk; }
    public double getF3Cost() { return f3Cost; }
    public double getF4UnusedCapacity() { return f4UnusedCapacity; }

    // === Placement statistics ===
    public int getActiveHostCount() { return activeHostCount; }
    public double getCpuUtilization() { return cpuUtilization; }
    public double getMemoryUtilization() { return memoryUtilization; }
    public double getOverallUtilization() { return overallUtilization; }

    // === Migration statistics ===
    public int getMigrationCount() { return migrationCount; }
    public double getMigrationCost() { return migrationCost; }

    // === Co-residency duration ===
    public double getTotalCoResidencyDuration() { return totalCoResidencyDuration; }
    public double getHighThreatCoResidencyDuration() { return highThreatCoResidencyDuration; }
    public int getCoResidencyEpisodeCount() { return coResidencyEpisodeCount; }

    // === NSSAO statistics ===
    public int getIterationsExecuted() { return iterationsExecuted; }
    public boolean isStoppedByStall() { return stoppedByStall; }
    public int getRejectedDecodeCount() { return rejectedDecodeCount; }
    public int getFront1RawSize() { return front1RawSize; }
    public int getUniquePlacements() { return uniquePlacements; }
    public int getDuplicatesRemoved() { return duplicatesRemoved; }

    // === Repair statistics ===
    public int getRepairCount() { return repairCount; }
    public double getRepairRate() { return repairRate; }

    /**
     * Format metrics as a readable report string.
     */
    public String formatReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("STAGE 2H FINAL METRICS\n");
        sb.append("========================================\n");
        sb.append("\nOBJECTIVE VALUES:\n");
        sb.append(String.format("  f1 (threat×server-risk):     %.6f\n", f1ThreatServerRisk));
        sb.append(String.format("  f2 (co-residency risk):       %.6f\n", f2CoResidencyRisk));
        sb.append(String.format("  f3 (cost):                    %.6f\n", f3Cost));
        sb.append(String.format("  f4 (unused capacity):         %.6f\n", f4UnusedCapacity));

        sb.append("\nPLACEMENT STATISTICS:\n");
        sb.append(String.format("  Active hosts:                 %d\n", activeHostCount));
        sb.append(String.format("  CPU utilization:              %.2f%%\n", cpuUtilization * 100));
        sb.append(String.format("  Memory utilization:           %.2f%%\n", memoryUtilization * 100));
        sb.append(String.format("  Overall utilization:          %.2f%%\n", overallUtilization * 100));

        sb.append("\nMIGRATION STATISTICS:\n");
        sb.append(String.format("  Migration count:              %d\n", migrationCount));
        sb.append(String.format("  Migration cost:               %.6f\n", migrationCost));

        sb.append("\nCO-RESIDENCY DURATION:\n");
        sb.append(String.format("  Total duration:               %.6f\n", totalCoResidencyDuration));
        sb.append(String.format("  High-threat duration:         %.6f\n", highThreatCoResidencyDuration));
        sb.append(String.format("  Episode count:                %d\n", coResidencyEpisodeCount));

        sb.append("\nNSSAO STATISTICS:\n");
        sb.append(String.format("  Iterations executed:          %d\n", iterationsExecuted));
        sb.append(String.format("  Stopped by stall:             %s\n", stoppedByStall));
        sb.append(String.format("  Rejected decodes:             %d\n", rejectedDecodeCount));
        sb.append(String.format("  Front-1 raw size:             %d\n", front1RawSize));
        sb.append(String.format("  Unique placements:            %d\n", uniquePlacements));
        sb.append(String.format("  Duplicates removed:           %d\n", duplicatesRemoved));

        sb.append("\nREPAIR STATISTICS:\n");
        sb.append(String.format("  Repair count:                 %d\n", repairCount));
        sb.append(String.format("  Repair rate:                  %.2f%%\n", repairRate * 100));

        sb.append("========================================\n");
        return sb.toString();
    }

    /**
     * Builder for SimulationMetrics.
     */
    public static final class Builder {
        private double f1ThreatServerRisk = 0.0;
        private double f2CoResidencyRisk = 0.0;
        private double f3Cost = 0.0;
        private double f4UnusedCapacity = 0.0;
        private int activeHostCount = 0;
        private double cpuUtilization = 0.0;
        private double memoryUtilization = 0.0;
        private double overallUtilization = 0.0;
        private int migrationCount = 0;
        private double migrationCost = 0.0;
        private double totalCoResidencyDuration = 0.0;
        private double highThreatCoResidencyDuration = 0.0;
        private int coResidencyEpisodeCount = 0;
        private int iterationsExecuted = 0;
        private boolean stoppedByStall = false;
        private int rejectedDecodeCount = 0;
        private int front1RawSize = 0;
        private int uniquePlacements = 0;
        private int duplicatesRemoved = 0;
        private int repairCount = 0;
        private double repairRate = 0.0;

        public Builder f1ThreatServerRisk(double value) {
            this.f1ThreatServerRisk = value;
            return this;
        }

        public Builder f2CoResidencyRisk(double value) {
            this.f2CoResidencyRisk = value;
            return this;
        }

        public Builder f3Cost(double value) {
            this.f3Cost = value;
            return this;
        }

        public Builder f4UnusedCapacity(double value) {
            this.f4UnusedCapacity = value;
            return this;
        }

        public Builder activeHostCount(int value) {
            this.activeHostCount = value;
            return this;
        }

        public Builder cpuUtilization(double value) {
            this.cpuUtilization = value;
            return this;
        }

        public Builder memoryUtilization(double value) {
            this.memoryUtilization = value;
            return this;
        }

        public Builder overallUtilization(double value) {
            this.overallUtilization = value;
            return this;
        }

        public Builder migrationCount(int value) {
            this.migrationCount = value;
            return this;
        }

        public Builder migrationCost(double value) {
            this.migrationCost = value;
            return this;
        }

        public Builder totalCoResidencyDuration(double value) {
            this.totalCoResidencyDuration = value;
            return this;
        }

        public Builder highThreatCoResidencyDuration(double value) {
            this.highThreatCoResidencyDuration = value;
            return this;
        }

        public Builder coResidencyEpisodeCount(int value) {
            this.coResidencyEpisodeCount = value;
            return this;
        }

        public Builder iterationsExecuted(int value) {
            this.iterationsExecuted = value;
            return this;
        }

        public Builder stoppedByStall(boolean value) {
            this.stoppedByStall = value;
            return this;
        }

        public Builder rejectedDecodeCount(int value) {
            this.rejectedDecodeCount = value;
            return this;
        }

        public Builder front1RawSize(int value) {
            this.front1RawSize = value;
            return this;
        }

        public Builder uniquePlacements(int value) {
            this.uniquePlacements = value;
            return this;
        }

        public Builder duplicatesRemoved(int value) {
            this.duplicatesRemoved = value;
            return this;
        }

        public Builder repairCount(int value) {
            this.repairCount = value;
            return this;
        }

        public Builder repairRate(double value) {
            this.repairRate = value;
            return this;
        }

        public SimulationMetrics build() {
            return new SimulationMetrics(this);
        }
    }
}
