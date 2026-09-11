package org.cloudbus.cloudsim.examples.stage2d;

import org.cloudbus.cloudsim.Vm;

/**
 * Represents a VM migration event in the CloudSim simulation.
 *
 * <p>This is a project-specific simulation abstraction for Stage 2D.
 * It records the essential migration metadata needed for:
 * <ul>
 *   <li>Co-residency duration tracking (start/end of co-residency episodes)</li>
 *   <li>Migration cost accounting</li>
 *   <li>Security state recomputation after migration</li>
 * </ul>
 *
 * <p>Migration timing in this model:
 * <ul>
 *   <li>migrationStartTime: when the migration decision is executed in simulation time</li>
 *   <li>migrationTimestamp: the simulation time at which migration completes
 *       (in this Stage 2D model, we treat migration as effectively instantaneous
 *       at the simulation level, with a configurable cost abstraction)</li>
 * </ul>
 *
 * <p>IMPORTANT: This is a SIMULATION ABSTRACTION. It does NOT claim to
 * reproduce real-world migration durations or costs. Real VM migration
 * involves downtime, network transfer time, memory dirty-page iteration, etc.
 * Those are not modeled in this Stage 2D minimum implementation.
 */
public final class MigrationEvent {

    private final int vmId;
    private final int sourceHostId;
    private final int destinationHostId;
    private final double migrationStartTime;
    private final double migrationTimestamp;
    private final Vm migratedVm;

    /**
     * Create a migration event.
     *
     * @param vmId CloudSim VM ID
     * @param sourceHostId host ID from which VM is migrated
     * @param destinationHostId host ID to which VM is migrated
     * @param migrationStartTime simulation time when migration starts
     * @param migrationTimestamp simulation time when migration is recorded as complete
     * @param migratedVm the actual CloudSim VM object (for metadata preservation verification)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public MigrationEvent(int vmId, int sourceHostId, int destinationHostId,
                          double migrationStartTime, double migrationTimestamp,
                          Vm migratedVm) {
        if (vmId < 0) {
            throw new IllegalArgumentException("vmId must be >= 0, got: " + vmId);
        }
        if (sourceHostId < 0) {
            throw new IllegalArgumentException("sourceHostId must be >= 0, got: " + sourceHostId);
        }
        if (destinationHostId < 0) {
            throw new IllegalArgumentException("destinationHostId must be >= 0, got: " + destinationHostId);
        }
        if (Double.isNaN(migrationStartTime) || Double.isInfinite(migrationStartTime)) {
            throw new IllegalArgumentException("migrationStartTime must be finite");
        }
        if (migrationStartTime < 0) {
            throw new IllegalArgumentException("migrationStartTime must be >= 0");
        }
        if (Double.isNaN(migrationTimestamp) || Double.isInfinite(migrationTimestamp)) {
            throw new IllegalArgumentException("migrationTimestamp must be finite");
        }
        if (migrationTimestamp < migrationStartTime) {
            throw new IllegalArgumentException(
                    "migrationTimestamp (" + migrationTimestamp + ") must be >= "
                            + "migrationStartTime (" + migrationStartTime + ")");
        }
        if (migratedVm == null) {
            throw new IllegalArgumentException("migratedVm cannot be null");
        }
        if (migratedVm.getId() != vmId) {
            throw new IllegalArgumentException(
                    "migratedVm.getId() (" + migratedVm.getId() + ") must equal vmId (" + vmId + ")");
        }

        this.vmId = vmId;
        this.sourceHostId = sourceHostId;
        this.destinationHostId = destinationHostId;
        this.migrationStartTime = migrationStartTime;
        this.migrationTimestamp = migrationTimestamp;
        this.migratedVm = migratedVm;
    }

    public int getVmId() {
        return vmId;
    }

    public int getSourceHostId() {
        return sourceHostId;
    }

    public int getDestinationHostId() {
        return destinationHostId;
    }

    public double getMigrationStartTime() {
        return migrationStartTime;
    }

    public double getMigrationTimestamp() {
        return migrationTimestamp;
    }

    /**
     * Get the simulation-time duration of this migration event.
     * migrationDuration = migrationTimestamp - migrationStartTime
     */
    public double getMigrationDuration() {
        return migrationTimestamp - migrationStartTime;
    }

    /**
     * Get the VM that was migrated. Used for metadata preservation verification.
     */
    public Vm getMigratedVm() {
        return migratedVm;
    }

    /**
     * Check if source and destination are the same host.
     * A migration to the same host is typically a no-op or error.
     */
    public boolean isSameHostMigration() {
        return sourceHostId == destinationHostId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MigrationEvent)) return false;
        MigrationEvent that = (MigrationEvent) o;
        return vmId == that.vmId
                && sourceHostId == that.sourceHostId
                && destinationHostId == that.destinationHostId
                && Double.compare(that.migrationStartTime, migrationStartTime) == 0
                && Double.compare(that.migrationTimestamp, migrationTimestamp) == 0;
    }

    @Override
    public int hashCode() {
        int result = vmId;
        result = 31 * result + sourceHostId;
        result = 31 * result + destinationHostId;
        long temp = Double.doubleToLongBits(migrationStartTime);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(migrationTimestamp);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "MigrationEvent{" +
                "vmId=" + vmId +
                ", sourceHostId=" + sourceHostId +
                ", destinationHostId=" + destinationHostId +
                ", startTime=" + migrationStartTime +
                ", timestamp=" + migrationTimestamp +
                ", duration=" + getMigrationDuration() +
                '}';
    }

    /**
     * Create a migration event with the same start and end time
     * (effectively instantaneous migration at simulation level).
     *
     * <p>This is the default for Stage 2D where we model migration
     * as a state transition at a point in simulation time.
     */
    public static MigrationEvent instantaneous(int vmId, int sourceHostId,
                                               int destinationHostId, double simulationTime,
                                               Vm migratedVm) {
        return new MigrationEvent(vmId, sourceHostId, destinationHostId,
                simulationTime, simulationTime, migratedVm);
    }
}
