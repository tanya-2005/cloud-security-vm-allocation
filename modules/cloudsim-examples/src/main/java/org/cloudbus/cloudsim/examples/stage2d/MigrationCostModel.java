package org.cloudbus.cloudsim.examples.stage2d;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

import java.util.List;

/**
 * Migration cost model for Stage 2D.
 *
 * <p>This is a CONFIGURABLE SIMULATION ABSTRACTION. It does NOT claim
 * to reproduce the paper's migration cost equation unless verified.
 *
 * <p>The model provides:
 * <ul>
 *   <li>Per-migration cost (configurable simulation parameter)</li>
 *   <li>Total migration cost = migration count * per-migration cost</li>
 *   <li>Migration count from recorded MigrationEvents</li>
 * </ul>
 *
 * <p>IMPORTANT: Migration cost is a configurable simulation abstraction
 * UNLESS supported by measured CloudSim quantities. In this Stage 2D
 * implementation, we use a simple configurable per-migration cost because
 * the current CloudSim setup does not provide real migration cost data
 * (downtime, network transfer, memory copy, etc.).
 *
 * <p>Real migration cost factors NOT modeled in Stage 2D:
 * <ul>
 *   <li>VM downtime during migration</li>
 *   <li>Network bandwidth consumption</li>
 *   <li>Memory dirty-page iteration overhead</li>
 *   <li>Storage migration cost (if VM has local storage)</li>
 *   <li>Energy consumption during migration</li>
 * </ul>
 *
 * <p>These can be added in later stages if needed, but for Stage 2D
 * we use a simple configurable abstraction and document it clearly.
 */
public final class MigrationCostModel {

    private final double perMigrationCost;

    /**
     * Create a migration cost model with explicit per-migration cost.
     *
     * @param perMigrationCost cost per migration event in simulation currency units.
     *                        This is a configurable simulation parameter.
     * @throws IllegalArgumentException if cost is negative
     */
    public MigrationCostModel(double perMigrationCost) {
        if (perMigrationCost < 0.0) {
            throw new IllegalArgumentException(
                    "perMigrationCost cannot be negative, got: " + perMigrationCost);
        }
        this.perMigrationCost = perMigrationCost;
    }

    /**
     * Create a migration cost model with default simulation parameter.
     *
     * <p>Default: perMigrationCost = 1.0 simulation currency units per migration.
     *
     * <p>This default is a SIMULATION PARAMETER, NOT a real cost measurement.
     * It provides a baseline for Stage 2D testing and can be adjusted
     * for later experiments.
     */
    public MigrationCostModel() {
        this(1.0);
    }

    /**
     * Calculate total migration cost from a list of migration events.
     *
     * @param migrationEvents list of MigrationEvent objects
     * @return total migration cost = events.size() * perMigrationCost
     */
    public double calculateTotalCost(List<MigrationEvent> migrationEvents) {
        if (migrationEvents == null) {
            return 0.0;
        }
        return migrationEvents.size() * perMigrationCost;
    }

    /**
     * Calculate total migration cost from a migration service.
     *
     * @param migrationService the VmMigrationService that recorded events
     * @return total migration cost
     */
    public double calculateTotalCostFromService(VmMigrationService migrationService) {
        if (migrationService == null) {
            return 0.0;
        }
        return calculateTotalCost(migrationService.getMigrationEvents());
    }

    /**
     * Get cost breakdown for reporting.
     */
    public MigrationCostBreakdown getCostBreakdown(List<MigrationEvent> migrationEvents) {
        int count = (migrationEvents == null) ? 0 : migrationEvents.size();
        double total = count * perMigrationCost;
        return new MigrationCostBreakdown(count, perMigrationCost, total);
    }

    /**
     * Get cost breakdown from a migration service.
     */
    public MigrationCostBreakdown getCostBreakdownFromService(VmMigrationService migrationService) {
        if (migrationService == null) {
            return new MigrationCostBreakdown(0, perMigrationCost, 0.0);
        }
        return getCostBreakdown(migrationService.getMigrationEvents());
    }

    public double getPerMigrationCost() {
        return perMigrationCost;
    }

    /**
     * Migration cost breakdown for reporting.
     */
    public static final class MigrationCostBreakdown {
        private final int migrationCount;
        private final double perMigrationCost;
        private final double totalCost;

        MigrationCostBreakdown(int migrationCount, double perMigrationCost,
                               double totalCost) {
            this.migrationCount = migrationCount;
            this.perMigrationCost = perMigrationCost;
            this.totalCost = totalCost;
        }

        public int getMigrationCount() {
            return migrationCount;
        }

        public double getPerMigrationCost() {
            return perMigrationCost;
        }

        public double getTotalCost() {
            return totalCost;
        }

        @Override
        public String toString() {
            return "MigrationCostBreakdown{"
                    + "migrations=" + migrationCount
                    + ", costPerMigration=" + perMigrationCost
                    + ", totalCost=" + totalCost
                    + '}';
        }
    }
}
