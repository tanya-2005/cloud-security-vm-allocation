package org.cloudbus.cloudsim.examples.stage2b;

/**
 * ThreatRecord represents a single workload-entity threat score record
 * imported from the Python ML pipeline.
 *
 * <p>This is a pure data carrier. It does NOT imply that the entity is
 * a CloudSim VM, a physical host, or a real cloud user. It is a
 * workload-behaviour entity with an associated continuous threat score
 * in [0,1] produced by the source-fitted Isolation Forest calibration.
 *
 * <p>The composite key is (dataset, entityType, entityId). This triple
 * MUST be preserved exactly as exported by the Python pipeline.
 *
 * <p>Stage 2B usage: one ThreatRecord maps to one simulated CloudSim VM
 * purely as a simulation abstraction for integration validation. This is
 * NOT a claim that an Azure deployment equals one real VM.
 */
public final class ThreatRecord {

    private final String dataset;
    private final String entityType;
    private final String entityId;
    private final double threatScore;

    public ThreatRecord(String dataset, String entityType, String entityId,
                        double threatScore) {
        this.dataset = dataset;
        this.entityType = entityType;
        this.entityId = entityId;
        this.threatScore = threatScore;
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

    /**
     * Composite key for deduplication and mapping lookup.
     */
    public CompositeKey toCompositeKey() {
        return new CompositeKey(dataset, entityType, entityId);
    }

    @Override
    public String toString() {
        return "ThreatRecord{" +
                "dataset='" + dataset + '\'' +
                ", entityType='" + entityType + '\'' +
                ", entityId='" + entityId + '\'' +
                ", threatScore=" + threatScore +
                '}';
    }

    /**
     * Composite key = (dataset, entityType, entityId).
     * Used for deduplication validation and VM mapping lookup.
     */
    public static final class CompositeKey {
        private final String dataset;
        private final String entityType;
        private final String entityId;

        public CompositeKey(String dataset, String entityType, String entityId) {
            this.dataset = dataset;
            this.entityType = entityType;
            this.entityId = entityId;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CompositeKey)) return false;
            CompositeKey that = (CompositeKey) o;
            return dataset.equals(that.dataset) &&
                    entityType.equals(that.entityType) &&
                    entityId.equals(that.entityId);
        }

        @Override
        public int hashCode() {
            int result = dataset.hashCode();
            result = 31 * result + entityType.hashCode();
            result = 31 * result + entityId.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "(" + dataset + ", " + entityType + ", " + entityId + ")";
        }

        /**
         * Public factory method for creating composite keys.
         * Used by Stage 2C and later stages.
         */
        public static CompositeKey of(String dataset, String entityType, String entityId) {
            return new CompositeKey(dataset, entityType, entityId);
        }
    }
}
