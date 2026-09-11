package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

import java.util.List;
import java.util.Map;

/**
 * Defensive cost model for Stage 2C.
 *
 * <p>This is an EXTENSIBLE cost interface. Components that are NOT yet
 * implemented are explicitly reported as N/A.
 *
 * <p>=== CURRENTLY AVAILABLE ===
 * <ul>
 *   <li>Host operational cost: based on number of active hosts and
 *       configurable per-host cost (simulation abstraction)</li>
 * </ul>
 *
 * <p>=== NOT YET AVAILABLE (explicit N/A) ===
 * <ul>
 *   <li>Migration cost: migration is NOT implemented yet in Stage 2C.
 *       Any migration cost reporting would be fabrication.</li>
 *   <li>Server activation/deactivation cost: not yet modeled as a
 *       dynamic decision.</li>
 *   <li>Defensive action cost (isolating VMs, applying patches, etc.):
 *       not yet implemented.</li>
 * </ul>
 *
 * <p>=== FUTURE EXTENSIONS ===
 * This class is designed to accommodate:
 * <ul>
 *   <li>Migration cost: add when VM migration is implemented (Stage 2D+)</li>
 *   <li>Server activation cost: add when dynamic scaling is modeled</li>
 *   <li>Security action costs: add when defensive actions are modeled</li>
 * </ul>
 */
public final class DefensiveCostModel {

    private final double perHostCost;
    private final double perVmCost;

    /**
     * Create a defensive cost model with explicit parameters.
     *
     * @param perHostCost cost per active host (simulation currency units)
     * @param perVmCost cost per VM (simulation currency units)
     * @throws IllegalArgumentException if costs are negative
     */
    public DefensiveCostModel(double perHostCost, double perVmCost) {
        if (perHostCost < 0.0) {
            throw new IllegalArgumentException(
                    "perHostCost cannot be negative, got: " + perHostCost);
        }
        if (perVmCost < 0.0) {
            throw new IllegalArgumentException(
                    "perVmCost cannot be negative, got: " + perVmCost);
        }
        this.perHostCost = perHostCost;
        this.perVmCost = perVmCost;
    }

    /**
     * Create a defensive cost model with default simulation parameters.
     *
     * <p>Defaults:
     * <ul>
     *   <li>perHostCost = 1.0 (simulation currency units per host)</li>
     *   <li>perVmCost = 0.1 (simulation currency units per VM)</li>
     * </ul>
     *
     * <p>These defaults are simulation parameters, NOT real cost measurements.
     */
    public DefensiveCostModel() {
        this(1.0, 0.1);
    }

    /**
     * Calculate operational cost for active hosts.
     *
     * <p>This is the ONLY cost component available in Stage 2C.
     * Migration cost does NOT exist yet.
     */
    public double calculateHostOperationalCost(List<Host> hosts) {
        if (hosts == null) {
            return 0.0;
        }
        // Count only hosts that are not failed and have VMs or are active
        int activeHostCount = 0;
        for (Host host : hosts) {
            if (!host.isFailed()) {
                activeHostCount++;
            }
        }
        return activeHostCount * perHostCost;
    }

    /**
     * Calculate VM-related cost.
     *
     * <p>This is a placeholder for future VM-related costs.
     * In Stage 2C, this is based only on VM count, not on any
     * security actions (which don't exist yet).
     */
    public double calculateVmCost(List<Host> hosts) {
        if (hosts == null) {
            return 0.0;
        }
        int vmCount = 0;
        for (Host host : hosts) {
            vmCount += host.getGuestList().size();
        }
        return vmCount * perVmCost;
    }

    /**
     * Migration cost.
     *
     * <p>NOT AVAILABLE in Stage 2C. Migration is not implemented.
     * This method returns 0.0 and logs a warning if called.
     *
     * <p>TODO: Implement when VM migration is added (Stage 2D+).
     */
    public double calculateMigrationCost(int migrationCount) {
        // Migration is not implemented yet.
        // This MUST return 0.0 to avoid fabricating costs.
        return 0.0;
    }

    /**
     * Total defensive cost available in Stage 2C.
     *
     * <p>This is the sum of available cost components:
     * <ul>
     *   <li>Host operational cost</li>
     *   <li>VM cost (placeholder)</li>
     * </ul>
     *
     * <p>Migration cost is included as 0.0 (not available).
     */
    public double calculateTotalCost(List<Host> hosts) {
        return calculateHostOperationalCost(hosts)
                + calculateVmCost(hosts)
                + calculateMigrationCost(0); // No migration yet
    }

    /**
     * Get cost breakdown for reporting.
     */
    public CostBreakdown getCostBreakdown(List<Host> hosts, int hypotheticalMigrationCount) {
        double hostCost = calculateHostOperationalCost(hosts);
        double vmCost = calculateVmCost(hosts);
        double migrationCost = calculateMigrationCost(hypotheticalMigrationCount);

        return new CostBreakdown(hostCost, vmCost, migrationCost,
                hostCost + vmCost + migrationCost);
    }

    /**
     * Cost breakdown for reporting.
     */
    public static final class CostBreakdown {
        private final double hostOperationalCost;
        private final double vmCost;
        private final double migrationCost;
        private final double totalCost;

        CostBreakdown(double hostOperationalCost, double vmCost,
                      double migrationCost, double totalCost) {
            this.hostOperationalCost = hostOperationalCost;
            this.vmCost = vmCost;
            this.migrationCost = migrationCost;
            this.totalCost = totalCost;
        }

        public double getHostOperationalCost() {
            return hostOperationalCost;
        }

        public double getVmCost() {
            return vmCost;
        }

        public double getMigrationCost() {
            return migrationCost;
        }

        public double getTotalCost() {
            return totalCost;
        }

        /**
         * Check if migration cost is available.
         * In Stage 2C, it is NOT available.
         */
        public boolean isMigrationCostAvailable() {
            return false; // Migration not implemented in Stage 2C
        }

        @Override
        public String toString() {
            return "CostBreakdown{" +
                    "hostOperationalCost=" + hostOperationalCost +
                    ", vmCost=" + vmCost +
                    ", migrationCost=" + migrationCost +
                    "(UNAVAILABLE IN STAGE 2C)" +
                    ", totalCost=" + totalCost +
                    '}';
        }
    }

    public double getPerHostCost() {
        return perHostCost;
    }

    public double getPerVmCost() {
        return perVmCost;
    }
}
