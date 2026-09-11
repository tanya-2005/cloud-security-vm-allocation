package org.cloudbus.cloudsim.examples.stage2c;

import org.cloudbus.cloudsim.examples.stage2b.EntityVmMapping;
import org.cloudbus.cloudsim.examples.stage2b.ThreatRecord;

import java.util.Objects;
import java.util.Optional;

/**
 * VM threat state for Stage 2C security modeling.
 *
 * <p>This wraps a CloudSim VM plus its associated threat metadata
 * from Stage 2B. It is the primary linkage between:
 * <ul>
 *   <li>Python ML threat scores (Stage 1C/1D)</li>
 *   <li>Stage 2B entity→VM mapping</li>
 *   <li>Stage 2C security-state/risk calculations</li>
 * </ul>
 *
 * <p>Every simulated VM in Stage 2C must expose its threat state
 * through this class. Threat scores are NOT recalculated in Java.
 * They are preserved exactly as exported by the Python pipeline.
 */
public final class VmThreatState {

    private final int cloudSimVmId;
    private final double threatScore;
    private final String dataset;
    private final String entityType;
    private final String entityId;
    private final boolean isHighThreat;

    /**
     * Create a VM threat state from Stage 2B metadata.
     *
     * @param metadata the Stage 2B VmThreatMetadata
     * @param highThreatThreshold configurable threshold for high-threat classification
     * @throws IllegalArgumentException if metadata is null
     */
    public VmThreatState(EntityVmMapping.VmThreatMetadata metadata,
                         double highThreatThreshold) {
        if (metadata == null) {
            throw new IllegalArgumentException("VmThreatMetadata cannot be null");
        }
        if (Double.isNaN(highThreatThreshold) || highThreatThreshold < 0.0
                || highThreatThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "highThreatThreshold must be in [0,1], got: " + highThreatThreshold);
        }

        this.cloudSimVmId = metadata.getCloudSimVmId();
        this.threatScore = metadata.getThreatScore();
        this.dataset = metadata.getDataset();
        this.entityType = metadata.getEntityType();
        this.entityId = metadata.getEntityId();
        this.isHighThreat = this.threatScore >= highThreatThreshold;
    }

    /**
     * Create a VM threat state with explicit values (for testing/scenario construction).
     */
    public VmThreatState(int cloudSimVmId, double threatScore,
                         String dataset, String entityType, String entityId,
                         double highThreatThreshold) {
        this(cloudSimVmId, threatScore, dataset, entityType, entityId,
                highThreatThreshold, threatScore >= highThreatThreshold);
    }

    private VmThreatState(int cloudSimVmId, double threatScore,
                          String dataset, String entityType, String entityId,
                          double highThreatThreshold, boolean isHighThreat) {
        if (Double.isNaN(threatScore) || Double.isInfinite(threatScore)) {
            throw new IllegalArgumentException("threatScore must be finite");
        }
        if (threatScore < 0.0 || threatScore > 1.0) {
            throw new IllegalArgumentException("threatScore must be in [0,1]");
        }
        if (dataset == null || dataset.isEmpty()) {
            throw new IllegalArgumentException("dataset cannot be empty");
        }
        if (entityType == null || entityType.isEmpty()) {
            throw new IllegalArgumentException("entityType cannot be empty");
        }
        if (entityId == null || entityId.isEmpty()) {
            throw new IllegalArgumentException("entityId cannot be empty");
        }

        this.cloudSimVmId = cloudSimVmId;
        this.threatScore = threatScore;
        this.dataset = dataset;
        this.entityType = entityType;
        this.entityId = entityId;
        this.isHighThreat = isHighThreat;
    }

    public int getCloudSimVmId() {
        return cloudSimVmId;
    }

    public double getThreatScore() {
        return threatScore;
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

    public boolean isHighThreat() {
        return isHighThreat;
    }

    /**
     * Get the composite entity key for this VM.
     */
    public ThreatRecord.CompositeKey getCompositeKey() {
        return new ThreatRecord.CompositeKey(dataset, entityType, entityId);
    }

    /**
     * Check if two VMs are from different workload entities.
     * Cross-entity co-residency is when VMs from different entities
     * share the same physical host.
     */
    public boolean isDifferentEntityFrom(VmThreatState other) {
        if (other == null) {
            return false;
        }
        return !this.dataset.equals(other.dataset)
                || !this.entityType.equals(other.entityType)
                || !this.entityId.equals(other.entityId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VmThreatState)) return false;
        VmThreatState that = (VmThreatState) o;
        return cloudSimVmId == that.cloudSimVmId
                && Double.compare(that.threatScore, threatScore) == 0
                && dataset.equals(that.dataset)
                && entityType.equals(that.entityType)
                && entityId.equals(that.entityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cloudSimVmId, threatScore, dataset, entityType, entityId);
    }

    @Override
    public String toString() {
        return "VmThreatState{" +
                "vmId=" + cloudSimVmId +
                ", threatScore=" + threatScore +
                ", isHighThreat=" + isHighThreat +
                ", dataset='" + dataset + '\'' +
                ", entityType='" + entityType + '\'' +
                ", entityId='" + entityId + '\'' +
                '}';
    }

    /**
     * Create a VmThreatState from Stage 2B metadata with a default
     * high-threat threshold. This is a CONVENIENCE constructor only.
     *
     * <p>IMPORTANT: The default threshold used here is NOT our final
     * adaptive threshold. It is only for testing the risk-state machinery.
     * See Stage 2C documentation for details.
     */
    public static VmThreatState fromMetadata(EntityVmMapping.VmThreatMetadata metadata) {
        // TEMPORARY threshold for Stage 2C testing only.
        // NOT our final adaptive threshold.
        // Documented in stage_2c_security_state.md.
        return new VmThreatState(metadata, 0.5);
    }
}
