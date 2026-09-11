package org.cloudbus.cloudsim.examples.stage2h;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic initial placement strategy using First-Fit Decreasing (FFD)
 * by CPU demand.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Sort VMs by decreasing MIPS (ties: ascending VM ID)</li>
 *   <li>For each VM: find first host with sufficient CPU and RAM capacity</li>
 *   <li>If any VM cannot be placed: FAIL CLEARLY with capacity report</li>
 * </ol>
 *
 * <p>Rationale:
 * <ul>
 *   <li>FFD is a well-known deterministic bin-packing heuristic</li>
 *   <li>CPU-first sorting aligns with CloudSim's typical bottleneck</li>
 *   <li>Guaranteed feasible if aggregate demand ≤ aggregate capacity</li>
 *   <li>No randomness → reproducible across runs</li>
 *   <li>Simple, auditable, no parameter tuning</li>
 * </ul>
 *
 * <p>NOT claimed: FFD is optimal, security-aware, or mimics production
 * scheduling. It is only an initial feasible state for the simulation.
 *
 * <p>Per Stage 2H design: this is a Stage 2H addition (not in the paper).
 */
public final class InitialPlacementStrategy {

    private final List<Host> hosts;
    private final List<Vm> vms;

    /**
     * @param hosts all hosts (immutable descriptors; not modified here)
     * @param vms   all VMs (immutable descriptors; not modified here)
     * @throws IllegalArgumentException if hosts or vms is null/empty
     */
    public InitialPlacementStrategy(List<Host> hosts, List<Vm> vms) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("hosts cannot be null or empty");
        }
        if (vms == null || vms.isEmpty()) {
            throw new IllegalArgumentException("vms cannot be null or empty");
        }
        this.hosts = hosts;
        this.vms = vms;
    }

    /**
     * Compute deterministic initial placement using First-Fit Decreasing.
     *
     * @return VM ID → Host ID mapping
     * @throws IllegalArgumentException if placement is infeasible
     */
    public Map<Integer, Integer> computePlacement() {
        // Available capacity per host (will be decremented as VMs are placed)
        Map<Integer, Integer> availableCpu = new LinkedHashMap<>();
        Map<Integer, Integer> availableRam = new LinkedHashMap<>();
        for (Host h : hosts) {
            // Vm#getMips() is double in this CloudSim version; total CPU
            // capacity is summed into an int (all scenario values are whole
            // MIPS).
            int totalCpu = (int) Math.round(totalCpuCapacity(h));
            availableCpu.put(h.getId(), totalCpu);
            availableRam.put(h.getId(), h.getRam());
        }

        Map<Integer, Integer> placement = new LinkedHashMap<>();

        // Sort VMs by decreasing MIPS (ties: ascending ID)
        List<Vm> sorted = new ArrayList<>(vms);
        sorted.sort(Comparator.comparingDouble((Vm v) -> v.getMips()).reversed()
                .thenComparingInt(Vm::getId));

        for (Vm vm : sorted) {
            int vmId = vm.getId();
            int vmCpu = (int) Math.ceil(vm.getMips());
            int vmRam = vm.getRam();

            // Find first host with sufficient capacity
            int assignedHostId = -1;
            for (Host h : hosts) {
                int hostId = h.getId();
                if (availableCpu.get(hostId) >= vmCpu && availableRam.get(hostId) >= vmRam) {
                    assignedHostId = hostId;
                    break;
                }
            }

            if (assignedHostId == -1) {
                // Placement failed
                throw new IllegalArgumentException(
                        String.format(
                                "Infeasible initial placement: VM %d (cpu=%d, ram=%d) cannot fit on any host. "
                                        + "Available: %s",
                                vmId, vmCpu, vmRam, capacityReport(availableCpu, availableRam))
                );
            }

            // Place VM and update available capacity
            placement.put(vmId, assignedHostId);
            availableCpu.put(assignedHostId, availableCpu.get(assignedHostId) - vmCpu);
            availableRam.put(assignedHostId, availableRam.get(assignedHostId) - vmRam);
        }

        return placement;
    }

    private String capacityReport(Map<Integer, Integer> availableCpu, Map<Integer, Integer> availableRam) {
        StringBuilder sb = new StringBuilder();
        for (Host h : hosts) {
            int hostId = h.getId();
            sb.append(String.format("H%d(cpu=%d/%d, ram=%d/%d) ",
                    hostId,
                    availableCpu.get(hostId), hostCpuCapacity(h),
                    availableRam.get(hostId), h.getRam()));
        }
        return sb.toString();
    }

    private int hostCpuCapacity(Host h) {
        return (int) Math.round(totalCpuCapacity(h));
    }

    private double totalCpuCapacity(Host h) {
        double total = 0;
        for (org.cloudbus.cloudsim.Pe pe : h.getPeList()) {
            total += pe.getMips();
        }
        return total;
    }
}
