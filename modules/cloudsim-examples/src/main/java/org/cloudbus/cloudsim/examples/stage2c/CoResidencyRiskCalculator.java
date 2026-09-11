package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.examples.stage2c.CoResidencyDetector.CrossEntityPair;

import java.util.List;

/**
 * Calculates co-residency risk from detected co-residency state.
 *
 * <p>This implements a first explicit co-residency-risk calculation
 * consistent with the paper's concept that co-residency risk increases
 * when higher-threat VMs share a host with normal/other-entity VMs.
 *
 * <p>=== FORMULA (Stage 2C, simulation abstraction) ===
 *
 * <pre>
 * For each cross-entity co-resident pair (vm_a, vm_b) on a host:
 *   pair_risk = max(threat_a, threat_b) * min(threat_a, threat_b)
 *
 * host_co_residency_risk = sum of all pair_risks on that host
 *
 * total_co_residency_risk = sum of host_co_residency_risk across all hosts
 * </pre>
 *
 * <p>This formula has the following properties:
 * <ul>
 *   <li>If either VM has threat=0, pair_risk=0 (no risk from that pair)</li>
 *   <li>If both VMs have high threat, pair_risk is high (both are risky)</li>
 *   <li>If one VM has high threat and other has low threat, pair_risk is moderate</li>
 *   <li>If VMs are from the SAME entity, they don't count (not cross-entity)</li>
 * </ul>
 *
 * <p>=== PAPER MAPPING ===
 * <ul>
 *   <li>Paper: co-residency risk increases when high-threat VMs co-reside</li>
 *   <li>Our implementation: pair_risk uses max(threat) * min(threat), which
 *       increases as either threat increases</li>
 *   <li>Paper: considers co-residency duration (we report this as NOT YET
 *       AVAILABLE in static snapshot)</li>
 * </ul>
 *
 * <p>=== SIMULATION ABSTRACTION DISCLAIMER ===
 * This formula is a SIMULATION ABSTRACTION for Stage 2C. It is NOT
 * claimed to exactly reproduce the paper's real-world risk measurements.
 * The paper may use additional factors (vulnerability data, network topology,
 * real co-residency duration) that are NOT available in our CloudSim
 * abstraction.
 */
public final class CoResidencyRiskCalculator {

    private CoResidencyRiskCalculator() {
        // Utility class
    }

    /**
     * Calculate co-residency risk for a single host.
     *
     * @param crossEntityPairs the cross-entity VM pairs on this host
     * @return co-residency risk for this host (>= 0)
     */
    public static double calculateHostRisk(List<CrossEntityPair> crossEntityPairs) {
        double risk = 0.0;
        for (CrossEntityPair pair : crossEntityPairs) {
            risk += calculatePairRisk(pair);
        }
        return risk;
    }

    /**
     * Calculate risk contribution of a single cross-entity pair.
     *
     * <p>pair_risk = max(threat_a, threat_b) * min(threat_a, threat_b)
     *
     * <p>This gives:
     * <ul>
     *   <li>0.0 if either threat is 0</li>
     *   <li>0.25 if both threats are 0.5</li>
     *   <li>1.0 if both threats are 1.0</li>
     * </ul>
     */
    public static double calculatePairRisk(CrossEntityPair pair) {
        double maxThreat = pair.getMaxThreatScore();
        double minThreat = pair.getMinThreatScore();
        return maxThreat * minThreat;
    }

    /**
     * Calculate total co-residency risk across all hosts.
     */
    public static double calculateTotalRisk(List<CrossEntityPair> allPairs) {
        double total = 0.0;
        for (CrossEntityPair pair : allPairs) {
            total += calculatePairRisk(pair);
        }
        return total;
    }

    /**
     * Calculate average co-residency risk per cross-entity pair.
     *
     * @return average pair risk, or 0.0 if no pairs exist
     */
    public static double calculateAveragePairRisk(List<CrossEntityPair> allPairs) {
        if (allPairs == null || allPairs.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (CrossEntityPair pair : allPairs) {
            total += calculatePairRisk(pair);
        }
        return total / allPairs.size();
    }

    /**
     * Calculate co-residency risk metrics for reporting.
     */
    public static CoResidencyRiskMetrics calculateMetrics(
            List<CrossEntityPair> allPairs) {
        double totalRisk = calculateTotalRisk(allPairs);
        double avgPairRisk = calculateAveragePairRisk(allPairs);
        int pairCount = allPairs.size();
        int highThreatPairCount = 0;
        for (CrossEntityPair pair : allPairs) {
            if (pair.hasHighThreatVm()) {
                highThreatPairCount++;
            }
        }

        return new CoResidencyRiskMetrics(totalRisk, avgPairRisk,
                pairCount, highThreatPairCount);
    }

    /**
     * Co-residency risk summary metrics.
     */
    public static final class CoResidencyRiskMetrics {
        private final double totalRisk;
        private final double averagePairRisk;
        private final int crossEntityPairCount;
        private final int highThreatPairCount;

        CoResidencyRiskMetrics(double totalRisk, double averagePairRisk,
                               int crossEntityPairCount, int highThreatPairCount) {
            this.totalRisk = totalRisk;
            this.averagePairRisk = averagePairRisk;
            this.crossEntityPairCount = crossEntityPairCount;
            this.highThreatPairCount = highThreatPairCount;
        }

        public double getTotalRisk() {
            return totalRisk;
        }

        public double getAveragePairRisk() {
            return averagePairRisk;
        }

        public int getCrossEntityPairCount() {
            return crossEntityPairCount;
        }

        public int getHighThreatPairCount() {
            return highThreatPairCount;
        }

        @Override
        public String toString() {
            return "CoResidencyRiskMetrics{" +
                    "totalRisk=" + totalRisk +
                    ", averagePairRisk=" + averagePairRisk +
                    ", crossEntityPairCount=" + crossEntityPairCount +
                    ", highThreatPairCount=" + highThreatPairCount +
                    '}';
        }
    }
}
