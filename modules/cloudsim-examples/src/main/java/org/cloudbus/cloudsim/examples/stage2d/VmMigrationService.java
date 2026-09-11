package org.cloudbus.cloudsim.examples.stage2d;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;

import java.util.*;

/**
 * Service for executing VM migration in the CloudSim simulation.
 *
 * <p>This service handles the mechanics of moving a VM from one host
 * to another, recording migration events, and maintaining the integrity
 * of the VM threat state mapping.
 *
 * <p>IMPORTANT: This is a SIMULATION ABSTRACTION for Stage 2D. It does
 * NOT model real-world migration mechanics (downtime, network transfer,
 * memory dirty-page iteration, etc.). It models the state transition:
 * VM moves from source host to destination host at a point in simulation time.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate migration request (VM exists, source host has VM, destination exists)</li>
 *   <li>Move VM between hosts</li>
 *   <li>Preserve VM threat metadata (the VmThreatState is NOT affected by migration)</li>
 *   <li>Record migration events</li>
 *   <li>Reject invalid migrations (same host, invalid VM, invalid hosts)</li>
 * </ul>
 *
 * <p>What this does NOT do (deferred to later stages):
 * <ul>
 *   <li>Optimize migration decisions (NSSAO, Stage 2E+)</li>
 *   <li>Model migration downtime or network transfer time</li>
 *   <li>Implement adaptive threshold-based migration triggers</li>
 *   <li>Implement threat-aware migration policies</li>
 * </ul>
 */
public final class VmMigrationService {

    private final List<MigrationEvent> migrationEvents;
    private final Map<Integer, VmThreatState> vmThreatStateMap;

    /**
     * Create a migration service with the given VM threat state mapping.
     *
     * @param vmThreatStateMap map from CloudSim VM ID to VmThreatState.
     *                        Must contain entries for all VMs that may be migrated.
     *                        The service does NOT modify this map — threat metadata
     *                        is preserved across migration.
     * @throws IllegalArgumentException if map is null
     */
    public VmMigrationService(Map<Integer, VmThreatState> vmThreatStateMap) {
        if (vmThreatStateMap == null) {
            throw new IllegalArgumentException("vmThreatStateMap cannot be null");
        }
        this.vmThreatStateMap = vmThreatStateMap;
        this.migrationEvents = new ArrayList<>();
    }

    /**
     * Execute a VM migration.
     *
     * @param vm the CloudSim VM to migrate
     * @param sourceHost the current host containing the VM
     * @param destinationHost the target host for the VM
     * @param simulationTime current simulation time
     * @return the recorded MigrationEvent
     * @throws IllegalArgumentException if:
     *   <li>vm is null</li>
     *   <li>sourceHost is null</li>
     *   <li>destinationHost is null</li>
     *   <li>vm is not on sourceHost</li>
     *   <li>sourceHost equals destinationHost (same-host migration rejected)</li>
     *   <li>vm is not in the vmThreatStateMap</li>
     */
    public MigrationEvent migrateVm(Vm vm, Host sourceHost, Host destinationHost,
                                     double simulationTime) {
        if (vm == null) {
            throw new IllegalArgumentException("vm cannot be null");
        }
        if (sourceHost == null) {
            throw new IllegalArgumentException("sourceHost cannot be null");
        }
        if (destinationHost == null) {
            throw new IllegalArgumentException("destinationHost cannot be null");
        }
        if (vm.getId() < 0) {
            throw new IllegalArgumentException("vm.getId() must be >= 0, got: " + vm.getId());
        }

        int vmId = vm.getId();
        int sourceHostId = sourceHost.getId();
        int destinationHostId = destinationHost.getId();

        // Verify VM is actually on the source host
        if (!hostContainsVm(sourceHost, vmId)) {
            throw new IllegalArgumentException(
                    "VM " + vmId + " is not on source host " + sourceHostId);
        }

        // Reject same-host migration
        if (sourceHostId == destinationHostId) {
            throw new IllegalArgumentException(
                    "Same-host migration rejected: VM " + vmId
                            + " is already on host " + sourceHostId);
        }

        // Verify VM has threat state metadata
        VmThreatState threatState = vmThreatStateMap.get(vmId);
        if (threatState == null) {
            throw new IllegalArgumentException(
                    "No VmThreatState found for VM " + vmId
                            + " — cannot migrate without threat metadata");
        }

        // Execute the migration: remove from source, add to destination
        removeVmFromHost(sourceHost, vmId);
        addVmToHost(destinationHost, vm);

        // Record the migration event
        MigrationEvent event = MigrationEvent.instantaneous(
                vmId, sourceHostId, destinationHostId, simulationTime, vm);
        migrationEvents.add(event);

        return event;
    }

    /**
     * Execute a migration using host IDs rather than host objects.
     * Looks up hosts from the provided list.
     *
     * @param vm the VM to migrate
     * @param hosts list of all hosts in the simulation
     * @param sourceHostId ID of the source host
     * @param destinationHostId ID of the destination host
     * @param simulationTime current simulation time
     * @return the recorded MigrationEvent
     * @throws IllegalArgumentException if hosts list is null, hosts not found,
     *    or any validation fails
     */
    public MigrationEvent migrateVmById(Vm vm, List<Host> hosts,
                                         int sourceHostId, int destinationHostId,
                                         double simulationTime) {
        if (hosts == null) {
            throw new IllegalArgumentException("hosts list cannot be null");
        }

        Host sourceHost = findHostById(hosts, sourceHostId);
        if (sourceHost == null) {
            throw new IllegalArgumentException(
                    "Source host not found: host ID " + sourceHostId);
        }

        Host destinationHost = findHostById(hosts, destinationHostId);
        if (destinationHost == null) {
            throw new IllegalArgumentException(
                    "Destination host not found: host ID " + destinationHostId);
        }

        return migrateVm(vm, sourceHost, destinationHost, simulationTime);
    }

    /**
     * Get all migration events recorded by this service.
     */
    public List<MigrationEvent> getMigrationEvents() {
        return Collections.unmodifiableList(migrationEvents);
    }

    /**
     * Get the total number of migration events.
     */
    public int getMigrationCount() {
        return migrationEvents.size();
    }

    /**
     * Get migration events for a specific VM.
     */
    public List<MigrationEvent> getMigrationEventsForVm(int vmId) {
        List<MigrationEvent> result = new ArrayList<>();
        for (MigrationEvent event : migrationEvents) {
            if (event.getVmId() == vmId) {
                result.add(event);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Check if a host contains a VM with the given ID.
     */
    public static boolean hostContainsVm(Host host, int vmId) {
        if (host == null) return false;
        for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm && vm.getId() == vmId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a VM from a host by ID.
     */
    private static void removeVmFromHost(Host host, int vmId) {
        if (host == null) return;
        java.util.List<?> list = host.getGuestList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof Vm vm && vm.getId() == vmId) {
                list.remove(i);
                return;
            }
        }
    }

    /**
     * Add a VM to a host's guest list.
     */
    private static void addVmToHost(Host host, Vm vm) {
        if (host == null || vm == null) return;
        host.getGuestList().add(vm);
    }

    /**
     * Find a host by ID in the list.
     */
    private static Host findHostById(List<Host> hosts, int hostId) {
        if (hosts == null) return null;
        for (Host host : hosts) {
            if (host.getId() == hostId) {
                return host;
            }
        }
        return null;
    }

    /**
     * Create a new VmMigrationService with an empty event list.
     * Convenience constructor for scenario setup.
     */
    public static VmMigrationService create(Map<Integer, VmThreatState> vmThreatStateMap) {
        return new VmMigrationService(vmThreatStateMap);
    }
}
