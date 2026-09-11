package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

import java.util.*;

/**
 * Detects co-residency relationships from CloudSim host state.
 *
 * <p>Two VMs are co-resident when they are simultaneously placed on
 * the same simulated physical host.
 *
 * <p>IMPORTANT: This is CloudSim SIMULATION STATE. It does NOT claim
 * that the public Azure dataset provided physical co-residency
 * information. Co-residency is a property of the simulated placement.
 *
 * <p>This detector provides the raw co-residency data needed by
 * the co-residency risk calculator. It does NOT compute risk itself.
 */
public final class CoResidencyDetector {

    private CoResidencyDetector() {
        // Utility class
    }

    /**
     * Co-residency data for a single host.
     */
    public static final class HostCoResidency {
        private final int hostId;
        private final List<VmThreatState> vmStates;
        private final int vmCount;
        private final int crossEntityPairCount;

        HostCoResidency(int hostId, List<VmThreatState> vmStates,
                        int crossEntityPairCount) {
            this.hostId = hostId;
            this.vmStates = vmStates;
            this.vmCount = vmStates.size();
            this.crossEntityPairCount = crossEntityPairCount;
        }

        public int getHostId() {
            return hostId;
        }

        public List<VmThreatState> getVmStates() {
            return vmStates;
        }

        public int getVmCount() {
            return vmCount;
        }

        public int getCrossEntityPairCount() {
            return crossEntityPairCount;
        }

        /**
         * Check if a high-threat VM co-resides with another entity's VM.
         */
        public boolean hasHighThreatWithOtherEntity() {
            VmThreatState highThreatVm = null;
            for (VmThreatState vs : vmStates) {
                if (vs.isHighThreat()) {
                    highThreatVm = vs;
                    break;
                }
            }
            if (highThreatVm == null) {
                return false;
            }
            for (VmThreatState vs : vmStates) {
                if (vs.isDifferentEntityFrom(highThreatVm)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Get all cross-entity VM pairs on this host.
         */
        public List<CrossEntityPair> getCrossEntityPairs() {
            List<CrossEntityPair> pairs = new ArrayList<>();
            for (int i = 0; i < vmStates.size(); i++) {
                for (int j = i + 1; j < vmStates.size(); j++) {
                    if (vmStates.get(i).isDifferentEntityFrom(vmStates.get(j))) {
                        pairs.add(new CrossEntityPair(
                                vmStates.get(i), vmStates.get(j), hostId));
                    }
                }
            }
            return pairs;
        }

        /**
         * Get count of high-threat VMs on this host.
         */
        public int getHighThreatVmCount() {
            int count = 0;
            for (VmThreatState vs : vmStates) {
                if (vs.isHighThreat()) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public String toString() {
            return "HostCoResidency{" +
                    "hostId=" + hostId +
                    ", vmCount=" + vmCount +
                    ", crossEntityPairCount=" + crossEntityPairCount +
                    '}';
        }
    }

    /**
     * A pair of VMs from different entities that are co-resident.
     */
    public static final class CrossEntityPair {
        private final VmThreatState vm1;
        private final VmThreatState vm2;
        private final int hostId;

        CrossEntityPair(VmThreatState vm1, VmThreatState vm2, int hostId) {
            this.vm1 = vm1;
            this.vm2 = vm2;
            this.hostId = hostId;
        }

        public VmThreatState getVm1() {
            return vm1;
        }

        public VmThreatState getVm2() {
            return vm2;
        }

        public int getHostId() {
            return hostId;
        }

        public double getMaxThreatScore() {
            return Math.max(vm1.getThreatScore(), vm2.getThreatScore());
        }

        public double getMinThreatScore() {
            return Math.min(vm1.getThreatScore(), vm2.getThreatScore());
        }

        public boolean hasHighThreatVm() {
            return vm1.isHighThreat() || vm2.isHighThreat();
        }

        @Override
        public String toString() {
            return "CrossEntityPair{" +
                    "vm1=" + vm1.getCloudSimVmId() +
                    "(threat=" + vm1.getThreatScore() + "), " +
                    "vm2=" + vm2.getCloudSimVmId() +
                    "(threat=" + vm2.getThreatScore() + "), " +
                    "hostId=" + hostId +
                    '}';
        }
    }

    /**
     * Detect co-residency across all hosts.
     *
     * @param hosts list of CloudSim hosts
     * @param vmThreatStateMap map from CloudSim VM ID to VmThreatState
     * @return list of host co-residency data, one per host (including empty hosts)
     */
    public static List<HostCoResidency> detectAll(List<Host> hosts,
                                                   Map<Integer, VmThreatState> vmThreatStateMap) {
        List<HostCoResidency> result = new ArrayList<>();
        for (Host host : hosts) {
            result.add(detectHost(host, vmThreatStateMap));
        }
        return result;
    }

    /**
     * Detect co-residency for a single host.
     */
    public static HostCoResidency detectHost(Host host,
                                              Map<Integer, VmThreatState> vmThreatStateMap) {
        List<VmThreatState> vmStates = new ArrayList<>();
        for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm) {
                VmThreatState state = vmThreatStateMap.get(vm.getId());
                if (state != null) {
                    vmStates.add(state);
                }
            }
        }

        int crossEntityPairCount = countCrossEntityPairs(vmStates);
        return new HostCoResidency(host.getId(), vmStates, crossEntityPairCount);
    }

    /**
     * Count cross-entity VM pairs on a host.
     * A pair is cross-entity when the two VMs belong to different
     * (dataset, entityType, entityId) combinations.
     */
    public static int countCrossEntityPairs(List<VmThreatState> vmStates) {
        if (vmStates == null) return 0;
        int count = 0;
        for (int i = 0; i < vmStates.size(); i++) {
            for (int j = i + 1; j < vmStates.size(); j++) {
                if (vmStates.get(i).isDifferentEntityFrom(vmStates.get(j))) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Count total cross-entity pairs across all hosts.
     */
    public static int countTotalCrossEntityPairs(List<Host> hosts,
                                                  Map<Integer, VmThreatState> vmThreatStateMap) {
        int total = 0;
        for (Host host : hosts) {
            total += countCrossEntityPairs(
                    getVmStatesOnHost(host, vmThreatStateMap));
        }
        return total;
    }

    /**
     * Check if any host has a high-threat VM co-resident with another entity's VM.
     */
    public static boolean hasHighThreatWithOtherEntity(List<Host> hosts,
                                                       Map<Integer, VmThreatState> vmThreatStateMap) {
        for (Host host : hosts) {
            HostCoResidency cr = detectHost(host, vmThreatStateMap);
            if (cr.hasHighThreatWithOtherEntity()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all cross-entity pairs across all hosts.
     */
    public static List<CrossEntityPair> getAllCrossEntityPairs(List<Host> hosts,
                                                               Map<Integer, VmThreatState> vmThreatStateMap) {
        List<CrossEntityPair> allPairs = new ArrayList<>();
        for (Host host : hosts) {
            HostCoResidency cr = detectHost(host, vmThreatStateMap);
            allPairs.addAll(cr.getCrossEntityPairs());
        }
        return allPairs;
    }

    /**
     * Get VMs on a host as Vm objects (not GuestEntity).
     */
    public static List<Vm> getVmsOnHost(Host host) {
        List<Vm> result = new ArrayList<>();
        for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm) {
                result.add(vm);
            }
        }
        return result;
    }

    /**
     * Get VM states for VMs on a host.
     */
    public static List<VmThreatState> getVmStatesOnHost(Host host,
                                                          Map<Integer, VmThreatState> vmThreatStateMap) {
        List<VmThreatState> result = new ArrayList<>();
        for (org.cloudbus.cloudsim.core.GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm) {
                VmThreatState state = vmThreatStateMap.get(vm.getId());
                if (state != null) {
                    result.add(state);
                }
            }
        }
        return result;
    }
}
