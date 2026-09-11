package org.cloudbus.cloudsim.examples.stage2d;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;

import java.util.*;

/**
 * Tracks co-residency duration for VM pairs across migration events.
 *
 * <p>This tracker measures how long a pair of VMs from different entities
 * remains co-resident on the same simulated physical host.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>A co-residency episode starts when two VMs from different entities
 *       are placed on the same host at the same simulation time.</li>
 *   <li>A co-residency episode ends when one of the VMs migrates away,
 *       or the simulation ends.</li>
 *   <li>Duration = episode end time - episode start time.</li>
 * </ul>
 *
 * <p>Algorithm (multi-snapshot comparison):
 * <ul>
 *   <li>For each snapshot transition t_{k-1} -> t_k:</li>
 *   <li>previously absent + currently present -> START episode at t_k</li>
 *   <li>previously present + currently absent -> END episode at t_k</li>
 *   <li>previously present + currently present -> CONTINUE existing episode</li>
 * </ul>
 *
 * <p>IMPORTANT: Co-residency duration is measured from simulated CloudSim
 * placement/migration events, NOT inferred from Azure or Alibaba trace
 * timestamps. The public datasets do not provide physical host placement.
 *
 * <p>Co-residency types tracked:
 * <ul>
 *   <li>Normal-Normal: both VMs have threat < threshold</li>
 *   <li>High-Threat-Normal: one VM is high-threat, other is normal</li>
 *   <li>High-Threat-High-Threat: both VMs are high-threat</li>
 * </ul>
 */
public final class CoResidencyDurationTracker {

    private final Map<Integer, VmThreatState> vmThreatStateMap;
    private final double highThreatThreshold;
    private final List<CoResidencySnapshot> snapshots;
    private final List<CoResidencyEpisode> episodes;

    /**
     * A snapshot of co-residency state at a point in simulation time.
     */
    public static final class CoResidencySnapshot {
        private final double simulationTime;
        private final List<CrossEntityCoResidencyPair> pairs;

        CoResidencySnapshot(double simulationTime,
                            List<CrossEntityCoResidencyPair> pairs) {
            this.simulationTime = simulationTime;
            this.pairs = pairs;
        }

        public double getSimulationTime() {
            return simulationTime;
        }

        public List<CrossEntityCoResidencyPair> getPairs() {
            return Collections.unmodifiableList(pairs);
        }

        public int getPairCount() {
            return pairs.size();
        }

        @Override
        public String toString() {
            return "CoResidencySnapshot{time=" + simulationTime
                    + ", pairs=" + pairs.size() + "}";
        }
    }

    /**
     * A pair of VMs that are co-resident across entities.
     */
    public static final class CrossEntityCoResidencyPair {
        private final int vmId1;
        private final int vmId2;
        private final int hostId;
        private final double threatScore1;
        private final double threatScore2;
        private final boolean highThreat1;
        private final boolean highThreat2;
        private final String entityId1;
        private final String entityId2;

        CrossEntityCoResidencyPair(int vmId1, int vmId2, int hostId,
                                   double threatScore1, double threatScore2,
                                   boolean highThreat1, boolean highThreat2,
                                   String entityId1, String entityId2) {
            this.vmId1 = vmId1;
            this.vmId2 = vmId2;
            this.hostId = hostId;
            this.threatScore1 = threatScore1;
            this.threatScore2 = threatScore2;
            this.highThreat1 = highThreat1;
            this.highThreat2 = highThreat2;
            this.entityId1 = entityId1;
            this.entityId2 = entityId2;
        }

        public int getVmId1() { return vmId1; }
        public int getVmId2() { return vmId2; }
        public int getHostId() { return hostId; }
        public double getThreatScore1() { return threatScore1; }
        public double getThreatScore2() { return threatScore2; }
        public boolean isHighThreat1() { return highThreat1; }
        public boolean isHighThreat2() { return highThreat2; }
        public String getEntityId1() { return entityId1; }
        public String getEntityId2() { return entityId2; }

        /**
         * Classify the type of co-residency.
         */
        public CoResidencyType getCoResidencyType() {
            if (highThreat1 && highThreat2) {
                return CoResidencyType.HIGH_THREAT_HIGH_THREAT;
            } else if (highThreat1 || highThreat2) {
                return CoResidencyType.HIGH_THREAT_NORMAL;
            } else {
                return CoResidencyType.NORMAL_NORMAL;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CrossEntityCoResidencyPair)) return false;
            CrossEntityCoResidencyPair that = (CrossEntityCoResidencyPair) o;
            return vmId1 == that.vmId1 && vmId2 == that.vmId2 && hostId == that.hostId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(vmId1, vmId2, hostId);
        }

        @Override
        public String toString() {
            return "CrossEntityCoResidencyPair{"
                    + "vm1=" + vmId1 + "(threat=" + threatScore1 + "), "
                    + "vm2=" + vmId2 + "(threat=" + threatScore2 + "), "
                    + "host=" + hostId + ", type=" + getCoResidencyType()
                    + "}";
        }
    }

    /**
     * Type of co-residency for a pair of VMs.
     */
    public enum CoResidencyType {
        NORMAL_NORMAL,
        HIGH_THREAT_NORMAL,
        HIGH_THREAT_HIGH_THREAT
    }

    /**
     * A complete co-residency episode with start/end times and duration.
     */
    public static final class CoResidencyEpisode {
        private final int vmId1;
        private final int vmId2;
        private final int hostId;
        private final CoResidencyType type;
        private final double startTime;
        private final double endTime;
        private final double duration;

        CoResidencyEpisode(int vmId1, int vmId2, int hostId, CoResidencyType type,
                           double startTime, double endTime) {
            this.vmId1 = vmId1;
            this.vmId2 = vmId2;
            this.hostId = hostId;
            this.type = type;
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = endTime - startTime;
        }

        public int getVmId1() { return vmId1; }
        public int getVmId2() { return vmId2; }
        public int getHostId() { return hostId; }
        public CoResidencyType getType() { return type; }
        public double getStartTime() { return startTime; }
        public double getEndTime() { return endTime; }
        public double getDuration() { return duration; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CoResidencyEpisode)) return false;
            CoResidencyEpisode that = (CoResidencyEpisode) o;
            return vmId1 == that.vmId1 && vmId2 == that.vmId2
                    && hostId == that.hostId
                    && Double.compare(that.startTime, startTime) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(vmId1, vmId2, hostId, startTime);
        }

        @Override
        public String toString() {
            return "CoResidencyEpisode{"
                    + "vm1=" + vmId1 + ", vm2=" + vmId2
                    + ", host=" + hostId + ", type=" + type
                    + ", start=" + startTime + ", end=" + endTime
                    + ", duration=" + duration + "}";
        }
    }

    /**
     * Create a co-residency duration tracker.
     *
     * @param vmThreatStateMap map from CloudSim VM ID to VmThreatState
     * @param highThreatThreshold threshold for classifying high-threat VMs.
     *                           This should be the project's current threshold
     *                           (testing parameter for Stage 2D, NOT the final
     *                           adaptive threshold, NOT 0.6).
     * @throws IllegalArgumentException if parameters are null or invalid
     */
    public CoResidencyDurationTracker(Map<Integer, VmThreatState> vmThreatStateMap,
                                       double highThreatThreshold) {
        if (vmThreatStateMap == null) {
            throw new IllegalArgumentException("vmThreatStateMap cannot be null");
        }
        if (Double.isNaN(highThreatThreshold) || highThreatThreshold < 0.0
                || highThreatThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "highThreatThreshold must be in [0,1], got: " + highThreatThreshold);
        }
        this.vmThreatStateMap = vmThreatStateMap;
        this.highThreatThreshold = highThreatThreshold;
        this.snapshots = new ArrayList<>();
        this.episodes = new ArrayList<>();
    }

    /**
     * Record a snapshot of current co-residency state.
     * Call this after initial placement and after each migration.
     *
     * @param hosts list of all hosts
     * @param simulationTime current simulation time
     */
    public void recordSnapshot(List<Host> hosts, double simulationTime) {
        if (hosts == null) {
            throw new IllegalArgumentException("hosts cannot be null");
        }
        List<CrossEntityCoResidencyPair> pairs = detectCoResidencyPairs(hosts);
        CoResidencySnapshot snapshot = new CoResidencySnapshot(simulationTime, pairs);
        snapshots.add(snapshot);

        // Compute episode durations by comparing with previous snapshot
        computeEpisodesFromSnapshots();
    }

    /**
     * Detect all cross-entity co-residency pairs from current host state.
     */
    private List<CrossEntityCoResidencyPair> detectCoResidencyPairs(List<Host> hosts) {
        List<CrossEntityCoResidencyPair> pairs = new ArrayList<>();
        for (Host host : hosts) {
            List<Vm> vmsOnHost = getVmsOnHost(host);
            for (int i = 0; i < vmsOnHost.size(); i++) {
                Vm vm1 = vmsOnHost.get(i);
                VmThreatState state1 = vmThreatStateMap.get(vm1.getId());
                if (state1 == null) continue;

                for (int j = i + 1; j < vmsOnHost.size(); j++) {
                    Vm vm2 = vmsOnHost.get(j);
                    VmThreatState state2 = vmThreatStateMap.get(vm2.getId());
                    if (state2 == null) continue;

                    // Check cross-entity
                    if (state1.isDifferentEntityFrom(state2)) {
                        pairs.add(new CrossEntityCoResidencyPair(
                                vm1.getId(), vm2.getId(), host.getId(),
                                state1.getThreatScore(), state2.getThreatScore(),
                                state1.isHighThreat(), state2.isHighThreat(),
                                state1.getEntityId(), state2.getEntityId()));
                    }
                }
            }
        }
        return pairs;
    }

    private List<Vm> getVmsOnHost(Host host) {
        List<Vm> result = new ArrayList<>();
        for (GuestEntity guest : host.getGuestList()) {
            if (guest instanceof Vm vm) {
                result.add(vm);
            }
        }
        return result;
    }

    /**
     * Compute episode durations from complete snapshot history.
     *
     * CLEAN FULL RECONSTRUCTION ALGORITHM:
     * 1. Clear all previous episodes (avoid duplicates)
     * 2. Build complete pair presence map across all snapshots
     * 3. For each unique pair, extract continuous presence intervals
     * 4. Each interval becomes one episode
     *
     * Correctness properties:
     * - Pair present at t=0 → episode starts at t=0
     * - Pair continuing → same episode, not multiple
     * - Pair disappearing → episode closes at disappearance time (next snapshot time where absent)
     * - Pair reappearing → NEW episode starts at reappearance time
     * - Multiple migrations → handled correctly by interval extraction
     * - Open episodes → closed at final snapshot time
     */
    private void computeEpisodesFromSnapshots() {
        // Clear previous episodes to avoid duplicates from re-computation
        episodes.clear();
        
        if (snapshots.isEmpty()) {
            return;
        }

        // Build map: PairKey → sorted list of snapshot indices where pair is present
        Map<PairKey, List<Integer>> pairPresence = new LinkedHashMap<>();
        
        for (int i = 0; i < snapshots.size(); i++) {
            CoResidencySnapshot snapshot = snapshots.get(i);
            for (CrossEntityCoResidencyPair pair : snapshot.getPairs()) {
                PairKey key = new PairKey(pair.getVmId1(), pair.getVmId2(), pair.getHostId());
                pairPresence.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }

        // For each unique pair, extract continuous intervals and create episodes
        for (Map.Entry<PairKey, List<Integer>> entry : pairPresence.entrySet()) {
            PairKey key = entry.getKey();
            List<Integer> indices = entry.getValue();
            
            // Process continuous segments within the sorted index list
            int segStart = indices.get(0);
            for (int i = 0; i < indices.size(); i++) {
                boolean isLastInSegment = (i == indices.size() - 1)
                        || (indices.get(i + 1) != indices.get(i) + 1);
                
                if (isLastInSegment) {
                    int segEnd = indices.get(i);
                    double startTime = snapshots.get(segStart).getSimulationTime();
                    double endTime;
                    
                    if (segEnd + 1 < snapshots.size()) {
                        // Pair disappears before the next snapshot: end at gap time
                        endTime = snapshots.get(segEnd + 1).getSimulationTime();
                    } else {
                        // Pair persists to end of simulation: end at final snapshot time
                        endTime = snapshots.get(segEnd).getSimulationTime();
                    }
                    
                    // Get the co-residency type from the snapshot
                    CrossEntityCoResidencyPair example = findPairInSnapshot(
                            snapshots.get(segStart), key);
                    
                    if (example != null) {
                        episodes.add(new CoResidencyEpisode(
                                key.vmId1, key.vmId2, key.hostId,
                                example.getCoResidencyType(),
                                startTime, endTime));
                    }
                    
                    // Advance to next segment start
                    if (i + 1 < indices.size()) {
                        segStart = indices.get(i + 1);
                    }
                }
            }
        }
    }
    
    private CrossEntityCoResidencyPair findPairInSnapshot(CoResidencySnapshot snapshot, PairKey key) {
        for (CrossEntityCoResidencyPair pair : snapshot.getPairs()) {
            // Check both orderings since PairKey canonicalizes vmId1 < vmId2
            // but pair objects store VMs in detection order
            PairKey pairKey = new PairKey(pair.getVmId1(), pair.getVmId2(), pair.getHostId());
            if (pairKey.equals(key)) {
                return pair;
            }
        }
        return null;
    }

    /**
     * Get all recorded snapshots.
     */
    public List<CoResidencySnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /**
     * Get all completed co-residency episodes.
     */
    public List<CoResidencyEpisode> getEpisodes() {
        return Collections.unmodifiableList(episodes);
    }

    /**
     * Get episodes of a specific type.
     */
    public List<CoResidencyEpisode> getEpisodesByType(CoResidencyType type) {
        List<CoResidencyEpisode> result = new ArrayList<>();
        for (CoResidencyEpisode episode : episodes) {
            if (episode.getType() == type) {
                result.add(episode);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get total co-residency duration for a specific type.
     */
    public double getTotalDurationByType(CoResidencyType type) {
        double total = 0.0;
        for (CoResidencyEpisode episode : episodes) {
            if (episode.getType() == type) {
                total += episode.getDuration();
            }
        }
        return total;
    }

    /**
     * Get total co-residency duration across all types.
     */
    public double getTotalCoResidencyDuration() {
        double total = 0.0;
        for (CoResidencyEpisode episode : episodes) {
            total += episode.getDuration();
        }
        return total;
    }

    /**
     * Get total high-threat co-residency duration (both HT-NT and HT-HT).
     */
    public double getTotalHighThreatCoResidencyDuration() {
        return getTotalDurationByType(CoResidencyType.HIGH_THREAT_NORMAL)
                + getTotalDurationByType(CoResidencyType.HIGH_THREAT_HIGH_THREAT);
    }

    /**
     * Get total normal-normal co-residency duration.
     */
    public double getTotalNormalCoResidencyDuration() {
        return getTotalDurationByType(CoResidencyType.NORMAL_NORMAL);
    }

    /**
     * Reset the tracker (clear all snapshots and episodes).
     * Useful for re-running scenarios.
     */
    public void reset() {
        snapshots.clear();
        episodes.clear();
    }

    /**
     * Internal key for tracking pair identity across snapshots.
     */
    private static final class PairKey {
        private final int vmId1;
        private final int vmId2;
        private final int hostId;

        PairKey(int vmId1, int vmId2, int hostId) {
            // Canonicalize: vmId1 < vmId2 for consistent comparison
            if (vmId1 < vmId2) {
                this.vmId1 = vmId1;
                this.vmId2 = vmId2;
            } else {
                this.vmId1 = vmId2;
                this.vmId2 = vmId1;
            }
            this.hostId = hostId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PairKey)) return false;
            PairKey that = (PairKey) o;
            return vmId1 == that.vmId1 && vmId2 == that.vmId2 && hostId == that.hostId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(vmId1, vmId2, hostId);
        }
    }
}
