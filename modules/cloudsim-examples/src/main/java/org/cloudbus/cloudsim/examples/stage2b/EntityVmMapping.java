package org.cloudbus.cloudsim.examples.stage2b;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps workload entities (ThreatRecord) to simulated CloudSim VMs.
 *
 * <p>Stage 2B mapping policy: ONE WORKLOAD ENTITY → ONE SIMULATED VM.
 *
 * <p>This is ONLY a simulation abstraction for integration validation.
 * It does NOT mean an Azure deployment is one real VM. The public
 * datasets do not provide physical host placement; CloudSim simulates
 * placement, co-residency, and migration.
 *
 * <p>VM ID assignment is deterministic: VMs are numbered sequentially
 * starting from a configurable base ID. The same input list always
 * produces the same mapping.
 *
 * <p>Each simulated VM retains its source entity metadata via a
 * {@link VmThreatMetadata} object stored in the project-side mapping
 * table. This avoids modifying CloudSim's core Vm class.
 */
public class EntityVmMapping {

    private final Map<ThreatRecord.CompositeKey, VmThreatMetadata> entityToMetadata =
            new LinkedHashMap<>();
    private final Map<Integer, ThreatRecord.CompositeKey> vmIdToEntity =
            new LinkedHashMap<>();
    private final int vmIdBase;

    /**
     * Create an empty mapping with the given base VM ID.
     *
     * @param vmIdBase the first VM ID to assign
     */
    public EntityVmMapping(int vmIdBase) {
        this.vmIdBase = vmIdBase;
    }

    /**
     * Build the mapping from a list of threat records.
     * One VM per entity, deterministic ID assignment.
     *
     * @param records the validated threat records (in desired order)
     * @return the constructed mapping with metadata
     */
    public void buildFrom(List<ThreatRecord> records) {
        int vmId = vmIdBase;
        for (ThreatRecord record : records) {
            VmThreatMetadata metadata = new VmThreatMetadata(
                    record.getDataset(),
                    record.getEntityType(),
                    record.getEntityId(),
                    record.getThreatScore(),
                    vmId
            );
            ThreatRecord.CompositeKey key = record.toCompositeKey();
            entityToMetadata.put(key, metadata);
            vmIdToEntity.put(vmId, key);
            vmId++;
        }
    }

    /**
     * Look up the threat score for a given VM ID.
     *
     * @param vmId the CloudSim VM ID
     * @return optional threat score
     */
    public Optional<Double> getVmThreatScore(int vmId) {
        ThreatRecord.CompositeKey key = vmIdToEntity.get(vmId);
        if (key == null) {
            return Optional.empty();
        }
        VmThreatMetadata metadata = entityToMetadata.get(key);
        if (metadata == null) {
            return Optional.empty();
        }
        return Optional.of(metadata.getThreatScore());
    }

    /**
     * Get threat metadata by VM ID.
     */
    public Optional<VmThreatMetadata> getMetadata(int vmId) {
        ThreatRecord.CompositeKey key = vmIdToEntity.get(vmId);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityToMetadata.get(key));
    }

    /**
     * Get threat metadata by composite key.
     */
    public Optional<VmThreatMetadata> getMetadata(ThreatRecord.CompositeKey key) {
        return Optional.ofNullable(entityToMetadata.get(key));
    }

    /**
     * Number of mappings.
     */
    public int getMappingCount() {
        return entityToMetadata.size();
    }

    /**
     * Number of VMs.
     */
    public int getVmCount() {
        return vmIdToEntity.size();
    }

    /**
     * All VM IDs in order.
     */
    public int[] getVmIds() {
        return vmIdToEntity.keySet().stream()
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * All records in order.
     */
    public ThreatRecord.CompositeKey[] getEntityKeys() {
        return entityToMetadata.keySet().toArray(new ThreatRecord.CompositeKey[0]);
    }

    /**
     * Threat metadata attached to a simulated VM.
     *
     * <p>This is stored in the project-side mapping, not in CloudSim's
     * Vm class. This keeps the CloudSim library untouched.
     */
    public static final class VmThreatMetadata {
        private final String dataset;
        private final String entityType;
        private final String entityId;
        private final double threatScore;
        private final int cloudSimVmId;

        VmThreatMetadata(String dataset, String entityType, String entityId,
                         double threatScore, int cloudSimVmId) {
            this.dataset = dataset;
            this.entityType = entityType;
            this.entityId = entityId;
            this.threatScore = threatScore;
            this.cloudSimVmId = cloudSimVmId;
        }

        public String getDataset() {
            return dataset;
        }

        public String getEntityType() {
            return entityType;
        }

        public String getEntityId() {
            return entityId;
        }

        public double getThreatScore() {
            return threatScore;
        }

        public int getCloudSimVmId() {
            return cloudSimVmId;
        }

        @Override
        public String toString() {
            return "VmThreatMetadata{" +
                    "dataset='" + dataset + '\'' +
                    ", entityType='" + entityType + '\'' +
                    ", entityId='" + entityId + '\'' +
                    ", threatScore=" + threatScore +
                    ", cloudSimVmId=" + cloudSimVmId +
                    '}';
        }
    }
}
