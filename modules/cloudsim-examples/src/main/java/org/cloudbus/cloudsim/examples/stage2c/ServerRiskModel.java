package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.Host;

import java.util.List;
import java.util.Map;

/**
 * Server/host risk model for Stage 2C.
 *
 * <p>This is a SIMULATION PARAMETER/ABSTRACTION. It does NOT represent
 * actual Azure or Alibaba server risk measurements. It is a configurable
 * model that can be adjusted for later experiments.
 *
 * <p>=== CURRENT IMPLEMENTATION ===
 * Host risk is computed from:
 * <ul>
 *   <li>Host resource utilization (higher utilization → higher risk,
 *       reflecting reduced headroom for security interventions)</li>
 *   <li>Configurable base host risk (simulation parameter)</li>
 * </ul>
 *
 * <p>=== FUTURE INPUTS (NOT YET AVAILABLE) ===
 * <ul>
 *   <li>Real server vulnerability data (Nessus/Qualys scans) — NOT available</li>
 *   <li>Actual hypervisor security patch status — NOT available</li>
 *   <li>Physical host security posture from cloud provider — NOT available</li>
 *   <li>Hardware-specific vulnerabilities (Spectre, Meltdown, etc.) — NOT available</li>
 * </ul>
 *
 * <p>These future inputs can be added by extending this class without
 * rewriting the entire system.
 */
public final class ServerRiskModel {

    private final double baseHostRisk;
    private final double utilizationRiskWeight;

    /**
     * Create a server risk model with explicit parameters.
     *
     * @param baseHostRisk base risk for each host (0 to 1), regardless of utilization
     * @param utilizationRiskWeight how much utilization contributes to risk (0 to 1)
     * @throws IllegalArgumentException if parameters are outside [0,1]
     */
    public ServerRiskModel(double baseHostRisk, double utilizationRiskWeight) {
        if (baseHostRisk < 0.0 || baseHostRisk > 1.0) {
            throw new IllegalArgumentException(
                    "baseHostRisk must be in [0,1], got: " + baseHostRisk);
        }
        if (utilizationRiskWeight < 0.0 || utilizationRiskWeight > 1.0) {
            throw new IllegalArgumentException(
                    "utilizationRiskWeight must be in [0,1], got: " + utilizationRiskWeight);
        }
        this.baseHostRisk = baseHostRisk;
        this.utilizationRiskWeight = utilizationRiskWeight;
    }

    /**
     * Create a server risk model with default simulation parameters.
     *
     * <p>Defaults:
     * <ul>
     *   <li>baseHostRisk = 0.1 (simulation abstraction: baseline host risk)</li>
     *   <li>utilizationRiskWeight = 0.3 (utilization contributes moderately to risk)</li>
     * </ul>
     *
     * <p>These defaults are NOT claims about real cloud server risk.
     * They are simulation parameters for Stage 2C testing.
     */
    public ServerRiskModel() {
        this(0.1, 0.3);
    }

    /**
     * Calculate risk for a single host.
     *
     * <p>host_risk = baseHostRisk + utilizationRiskWeight * overallUtilization
     *
     * <p>Capped to [0,1].
     */
    public double calculateHostRisk(Host host) {
        double utilization = ResourceUtilizationCalculator.calculateOverallUtilization(host);
        double risk = baseHostRisk + utilizationRiskWeight * utilization;
        return clampToUnitInterval(risk);
    }

    /**
     * Calculate average host risk across all hosts.
     */
    public double calculateAverageHostRisk(List<Host> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Host host : hosts) {
            sum += calculateHostRisk(host);
        }
        return sum / hosts.size();
    }

    /**
     * Calculate total host risk across all hosts.
     */
    public double calculateTotalHostRisk(List<Host> hosts) {
        double total = 0.0;
        for (Host host : hosts) {
            total += calculateHostRisk(host);
        }
        return total;
    }

    /**
     * Get host risk for specific hosts, keyed by host ID.
     */
    public Map<Integer, Double> calculateHostRisks(List<Host> hosts) {
        // Simple implementation - could be caching in future
        java.util.HashMap<Integer, Double> result = new java.util.HashMap<>();
        for (Host host : hosts) {
            result.put(host.getId(), calculateHostRisk(host));
        }
        return result;
    }

    public double getBaseHostRisk() {
        return baseHostRisk;
    }

    public double getUtilizationRiskWeight() {
        return utilizationRiskWeight;
    }

    /**
     * Clamp a value to [0,1].
     */
    private static double clampToUnitInterval(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
