package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

import java.util.List;

/**
 * Calculates resource utilization metrics from CloudSim host state.
 *
 * <p>Formulas (explicit and testable):
 * <ul>
 *   <li>CPU utilization = sum(allocated VM MIPS) / host total MIPS</li>
 *   <li>Memory utilization = sum(allocated VM RAM) / host RAM capacity</li>
 *   <li>Overall host utilization = (CPU utilization + Memory utilization) / 2</li>
 * </ul>
 *
 * <p>All values are in [0,1] range (or capped at 1.0 if over-subscribed).
 * Division by zero is prevented: hosts with zero capacity return 0.0 utilization.
 *
 * <p>These calculations use ACTUAL CloudSim host/VM resource values, not
 * fabricated data.
 */
public final class ResourceUtilizationCalculator {

    private ResourceUtilizationCalculator() {
        // Utility class
    }

    /**
     * Calculate CPU utilization for a single host.
     *
     * @param host the CloudSim host
     * @return CPU utilization in [0,1], or 0.0 if host capacity is zero
     */
    public static double calculateCpuUtilization(Host host) {
        double hostMips = host.getTotalMips();
        if (hostMips <= 0.0) {
            return 0.0;
        }

        double allocatedMips = 0.0;
        for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm) {
                allocatedMips += vm.getTotalMips();
            }
        }

        double utilization = allocatedMips / hostMips;
        return capToUnitInterval(utilization);
    }

    /**
     * Calculate memory utilization for a single host.
     *
     * @param host the CloudSim host
     * @return memory utilization in [0,1], or 0.0 if host RAM is zero
     */
    public static double calculateMemoryUtilization(Host host) {
        int hostRam = host.getRam();
        if (hostRam <= 0) {
            return 0.0;
        }

        int allocatedRam = 0;
        for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm) {
                allocatedRam += vm.getRam();
            }
        }

        double utilization = (double) allocatedRam / (double) hostRam;
        return capToUnitInterval(utilization);
    }

    /**
     * Calculate overall resource utilization for a single host.
     * This is the arithmetic mean of CPU and memory utilization.
     *
     * @param host the CloudSim host
     * @return overall utilization in [0,1]
     */
    public static double calculateOverallUtilization(Host host) {
        double cpuUtil = calculateCpuUtilization(host);
        double memUtil = calculateMemoryUtilization(host);
        return (cpuUtil + memUtil) / 2.0;
    }

    /**
     * Calculate average CPU utilization across multiple hosts.
     *
     * @param hosts list of CloudSim hosts
     * @return average CPU utilization in [0,1], or 0.0 if no hosts
     */
    public static double calculateAverageCpuUtilization(List<Host> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Host host : hosts) {
            sum += calculateCpuUtilization(host);
        }
        return sum / hosts.size();
    }

    /**
     * Calculate average memory utilization across multiple hosts.
     *
     * @param hosts list of CloudSim hosts
     * @return average memory utilization in [0,1], or 0.0 if no hosts
     */
    public static double calculateAverageMemoryUtilization(List<Host> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Host host : hosts) {
            sum += calculateMemoryUtilization(host);
        }
        return sum / hosts.size();
    }

    /**
     * Calculate average overall utilization across multiple hosts.
     *
     * @param hosts list of CloudSim hosts
     * @return average overall utilization in [0,1], or 0.0 if no hosts
     */
    public static double calculateAverageOverallUtilization(List<Host> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Host host : hosts) {
            sum += calculateOverallUtilization(host);
        }
        return sum / hosts.size();
    }

    /**
     * Get total MIPS allocated across all hosts.
     */
    public static double getTotalAllocatedMips(List<Host> hosts) {
        double total = 0.0;
        for (Host host : hosts) {
            for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
                if (guest instanceof Vm vm) {
                    total += vm.getTotalMips();
                }
            }
        }
        return total;
    }

    /**
     * Get total host MIPS capacity across all hosts.
     */
    public static double getTotalHostMipsCapacity(List<Host> hosts) {
        double total = 0.0;
        for (Host host : hosts) {
            total += host.getTotalMips();
        }
        return total;
    }

    /**
     * Cap a value to [0,1] interval.
     * Used to handle over-subscription scenarios where allocated
     * resources exceed capacity (utilization capped at 1.0).
     */
    static double capToUnitInterval(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
