package org.cloudbus.cloudsim.examples.stage2f;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable candidate VM-to-host mapping for Stage 2F objective evaluation.
 *
 * <p>A candidate placement is a PURE DATA STRUCTURE: a proposed assignment of
 * each VM to a host. It is NOT a change to the live CloudSim simulation
 * state. Evaluating a candidate MUST NOT mutate hosts, VMs, or any CloudSim
 * entity. The evaluator only READS static resource descriptors (capacity and
 * demand) from the supplied Host and Vm objects.
 *
 * <p>This is the input contract for {@link ObjectiveEvaluator}. Placement
 * optimization (which would generate and rank candidates) is NOT part of
 * Stage 2F.
 *
 * <h3>Immutability and validation</h3>
 * <ul>
 *   <li>The mapping is copied at construction time; external mutation of the
 *       source map cannot affect a built candidate.</li>
 *   <li>Duplicate VM IDs are rejected at construction time (a VM cannot be
 *       assigned to two hosts).</li>
 *   <li>Hosts are indexed by host ID for O(1) lookup during evaluation.</li>
 * </ul>
 */
public final class CandidatePlacement {

    /** VM ID -> candidate host ID. Insertion order preserved for determinism. */
    private final Map<Integer, Integer> vmToHost;

    /** Host ID -> Host object. Used for capacity checks and host descriptors. */
    private final Map<Integer, Host> hostsById;

    /**
     * Build a candidate placement from a VM-to-host map and a host list.
     *
     * @param vmToHost map from CloudSim VM ID to candidate host ID;
     *                 defensively copied; must not contain duplicate VM keys
     *                 (impossible in a Map, but asserted for clarity)
     * @param hosts    all hosts that exist in the simulated datacenter,
     *                 including empty ones; used for capacity validation and
     *                 for f1/f3/f4 inputs
     * @throws IllegalArgumentException if any argument is null or a host is
     *                                  duplicated by ID
     */
    public CandidatePlacement(Map<Integer, Integer> vmToHost, List<Host> hosts) {
        if (vmToHost == null) {
            throw new IllegalArgumentException("vmToHost cannot be null");
        }
        if (hosts == null) {
            throw new IllegalArgumentException("hosts cannot be null");
        }

        this.vmToHost = new LinkedHashMap<>(vmToHost);

        this.hostsById = new LinkedHashMap<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Host host : hosts) {
            if (host == null) {
                throw new IllegalArgumentException("hosts cannot contain null entries");
            }
            if (!seen.add(host.getId())) {
                throw new IllegalArgumentException(
                        "Duplicate host ID in host list: " + host.getId());
            }
            this.hostsById.put(host.getId(), host);
        }
    }

    /**
     * Get the candidate host ID assigned to a VM.
     *
     * @param vmId the CloudSim VM ID
     * @return the candidate host ID, or null if the VM has no assignment
     */
    public Integer getHostIdForVm(int vmId) {
        return vmToHost.get(vmId);
    }

    /**
     * Check whether a VM has an assignment in this candidate.
     */
    public boolean hasAssignment(int vmId) {
        return vmToHost.containsKey(vmId);
    }

    /**
     * Resolve the candidate Host object for a VM.
     *
     * @return the Host, or null if the VM is unassigned or its host ID does
     *         not exist in the host list
     */
    public Host getHostForVm(int vmId) {
        Integer hostId = vmToHost.get(vmId);
        if (hostId == null) {
            return null;
        }
        return hostsById.get(hostId);
    }

    /**
     * Get the Host object for a host ID, or null if unknown.
     */
    public Host getHost(int hostId) {
        return hostsById.get(hostId);
    }

    /**
     * All host IDs in this candidate's datacenter, in insertion order.
     * Includes empty hosts.
     */
    public List<Integer> getAllHostIds() {
        return new ArrayList<>(hostsById.keySet());
    }

    /**
     * Number of distinct hosts in the candidate's datacenter.
     */
    public int getHostCount() {
        return hostsById.size();
    }

    /**
     * All VM IDs with assignments, in insertion order.
     */
    public List<Integer> getVmIds() {
        return new ArrayList<>(vmToHost.keySet());
    }

    /**
     * Number of assigned VMs.
     */
    public int getVmAssignmentCount() {
        return vmToHost.size();
    }

    /**
     * Unmodifiable view of the VM-to-host mapping (for diagnostics and
     * deterministic iteration by callers).
     */
    public Map<Integer, Integer> viewMapping() {
        return Collections.unmodifiableMap(vmToHost);
    }

    @Override
    public String toString() {
        return "CandidatePlacement{" + vmToHost + "}";
    }
}
