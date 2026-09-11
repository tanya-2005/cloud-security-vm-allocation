package org.cloudbus.cloudsim.examples.stage2f;

import java.util.Collections;
import java.util.Map;

/**
 * Raw (un-normalized) objective values for one candidate placement.
 *
 * <p>All four objectives are MINIMIZED. Lower is better for every field.
 *
 * <p>This is a pure value carrier. Normalization is deliberately kept OUT of
 * this class: raw values and normalized values must never be mixed, per the
 * Stage 2F contract. See {@link ObjectiveNormalizer}.
 */
public final class ObjectiveValues {

    private final double f1ThreatServerRisk;
    private final double f2CoResidencyRisk;
    private final double f3Cost;
    private final double f4UnusedCapacity;

    public ObjectiveValues(double f1ThreatServerRisk,
                           double f2CoResidencyRisk,
                           double f3Cost,
                           double f4UnusedCapacity) {
        if (Double.isNaN(f1ThreatServerRisk) || Double.isInfinite(f1ThreatServerRisk)) {
            throw new IllegalArgumentException("f1 must be finite, got: " + f1ThreatServerRisk);
        }
        if (Double.isNaN(f2CoResidencyRisk) || Double.isInfinite(f2CoResidencyRisk)) {
            throw new IllegalArgumentException("f2 must be finite, got: " + f2CoResidencyRisk);
        }
        if (Double.isNaN(f3Cost) || Double.isInfinite(f3Cost)) {
            throw new IllegalArgumentException("f3 must be finite, got: " + f3Cost);
        }
        if (Double.isNaN(f4UnusedCapacity) || Double.isInfinite(f4UnusedCapacity)) {
            throw new IllegalArgumentException("f4 must be finite, got: " + f4UnusedCapacity);
        }
        this.f1ThreatServerRisk = f1ThreatServerRisk;
        this.f2CoResidencyRisk = f2CoResidencyRisk;
        this.f3Cost = f3Cost;
        this.f4UnusedCapacity = f4UnusedCapacity;
    }

    /**
     * f1 = mean over VMs of threat(vm) × serverRisk(candidateHost(vm)).
     * Minimize.
     */
    public double getF1ThreatServerRisk() {
        return f1ThreatServerRisk;
    }

    /**
     * f2 = sum of max(threatA, threatB) over cross-entity VM pairs sharing
     * the same candidate host. Minimize.
     */
    public double getF2CoResidencyRisk() {
        return f2CoResidencyRisk;
    }

    /**
     * f3 = activeHosts × perHostCost + migrationCount × perMigrationCost.
     * Minimize.
     */
    public double getF3Cost() {
        return f3Cost;
    }

    /**
     * f4 = 1 − ((avgCPUUtil + avgMemUtil) / 2), averaged across ALL hosts
     * including empty ones. Minimize (minimizing f4 = maximizing packing
     * efficiency).
     */
    public double getF4UnusedCapacity() {
        return f4UnusedCapacity;
    }

    /**
     * Component breakdown of f3 for diagnostics and validation.
     */
    public static final class CostComponents {
        private final int activeHostCount;
        private final double perHostCost;
        private final int migrationCount;
        private final double perMigrationCost;

        public CostComponents(int activeHostCount, double perHostCost,
                              int migrationCount, double perMigrationCost) {
            this.activeHostCount = activeHostCount;
            this.perHostCost = perHostCost;
            this.migrationCount = migrationCount;
            this.perMigrationCost = perMigrationCost;
        }

        public int getActiveHostCount() {
            return activeHostCount;
        }

        public double getPerHostCost() {
            return perHostCost;
        }

        public int getMigrationCount() {
            return migrationCount;
        }

        public double getPerMigrationCost() {
            return perMigrationCost;
        }

        public double getTotalCost() {
            return activeHostCount * perHostCost + migrationCount * perMigrationCost;
        }

        @Override
        public String toString() {
            return "CostComponents{activeHosts=" + activeHostCount
                    + ", perHostCost=" + perHostCost
                    + ", migrations=" + migrationCount
                    + ", perMigrationCost=" + perMigrationCost
                    + ", total=" + getTotalCost() + "}";
        }
    }

    @Override
    public String toString() {
        return "ObjectiveValues{"
                + "f1=" + String.format("%.6f", f1ThreatServerRisk)
                + ", f2=" + String.format("%.6f", f2CoResidencyRisk)
                + ", f3=" + String.format("%.6f", f3Cost)
                + ", f4=" + String.format("%.6f", f4UnusedCapacity)
                + '}';
    }
}
