# Stage 2B — Python Threat Score → CloudSim Integration Validation

**Status: COMPLETE**

**Date: 2026-09-09**

## Purpose

Prove that existing threat scores from the Python ML pipeline can be:
1. Safely ingested by Java
2. Mapped to simulated CloudSim VMs
3. Preserved without value change through simulation setup

This is the FIRST integration checkpoint. It does NOT implement:
- NSSAO
- Migration
- Adaptive threshold controller
- Co-residency/server-risk models
- Multi-objective optimization
- Alibaba CloudSim integration
- Large-scale simulation

## Files Created

### Java Classes (in `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2b/`)

| File | Purpose |
|------|---------|
| `ThreatRecord.java` | Data model for a single threat-score record with composite key (dataset, entityType, entityId) |
| `ThreatScoreCsvReader.java` | CSV ingestion with full validation per Stage 2A contract |
| `EntityVmMapping.java` | Deterministic 1:1 entity→VM mapping with threat metadata preservation |
| `Stage2bValidation.java` | Main simulation and validation runner |
| `PathUtil.java` | Cross-platform path resolution utility |

### Documentation

| File | Purpose |
|------|---------|
| `project/docs/stage_2b_validation.md` | This document |

## Implementation Summary

### 1. ThreatRecord Model

```java
public final class ThreatRecord {
    private final String dataset;
    private final String entityType;
    private final String entityId;
    private final double threatScore;
    
    // CompositeKey = (dataset, entityType, entityId) for deduplication
}
```

- Immutable data carrier
- Composite key for deduplication validation
- Does NOT imply the entity is a VM or physical host

### 2. CSV Ingestion (ThreatScoreCsvReader)

Validates against Stage 2A contract requirements:
- Required headers exist (`dataset`, `entity_type`, `entity_id`, `threat_score`)
- `dataset` is non-empty
- `entity_type` is non-empty
- `entity_id` is non-empty
- `threat_score` parses as numeric
- `threat_score` is finite
- `threat_score` is in [0,1]
- No duplicate composite keys

**Failure behavior:** FAILS CLEARLY with descriptive error. No silent repair, clamping, or discarding.

**CSV parser:** Simple dependency-free state-machine parser handling quoted fields with embedded commas and escaped quotes.

### 3. Entity→VM Mapping (EntityVmMapping)

**Stage 2B policy:** ONE WORKLOAD ENTITY → ONE SIMULATED VM

This is ONLY a simulation abstraction. It does NOT mean an Azure deployment equals one real VM.

**Key design decisions:**
- VM IDs assigned deterministically (sequential from base ID)
- Same input always produces same mapping
- Threat metadata stored in project-side mapping table (NOT in CloudSim's Vm class)
- CloudSim library remains unmodified

```java
public class EntityVmMapping {
    // entityToMetadata: CompositeKey → VmThreatMetadata
    // vmIdToEntity: vmId → CompositeKey
    
    public Optional<VmThreatMetadata> getMetadata(int vmId)
    public int getMappingCount()
    public int getVmCount()
}
```

### 4. Small CloudSim Simulation

**Configuration:**
- 248 total records loaded from `azure_to_azure_scores.csv`
- 12 entities selected (deterministic spread)
- 12 VMs created
- 4 hosts (2000 MIPS each, fits 12 VMs at 500 MIPS)
- 12 cloudlets (one per VM)
- All 12 cloudlets completed successfully

**Validation performed:**
- Score preservation: input score == stored VM metadata score (EXACT match)
- Mapping integrity: entity key matches across CSV→VM→metadata
- Post-simulation: metadata still accessible after CloudSim shutdown

### 5. Focused Validation Checks

| Test | Description | Result |
|------|-------------|--------|
| [A] Duplicate composite key | CSV with duplicate keys rejected | PASS |
| [B] Invalid threat score | Non-numeric score rejected | PASS |
| [C] Score outside [0,1] | Negative and >1 scores rejected | PASS |
| [D] Deterministic mapping | Same input → same VM mapping | PASS |
| [E] Score preservation | Input score equals stored score | PASS |

## Validation Results

```
STAGE 2B VALIDATION REPORT
========================================
Records loaded: 248
Records selected: 12
VMs created: 12
Mappings created: 12
Min threat score: 0.008527954801120558
Max threat score: 0.6923168256469344
Score mismatches: 0
Mapping mismatches: 0
Simulation completed: true

STAGE 2B VALIDATION PASSED
```

### Selected Entities (Sample)

The 12 selected entities span the threat score range:
- Min: 0.00853 (low-anomaly deployment)
- Max: 0.69232 (high-anomaly deployment)
- Scores preserved exactly through the integration pipeline

## How to Run

From `C:\cloudsim file\cloudsim\`:

```bash
# Compile
mvn compile -pl modules/cloudsim-examples -am -DskipTests

# Run Stage 2B validation
java -cp "modules/cloudsim/target/cloudsim-7.0.1.jar;modules/cloudsim-examples/target/classes" \
  org.cloudbus.cloudsim.examples.stage2b.Stage2bValidation
```

## Existing CloudSim Examples

Verified intact:
- CloudSimExample1: OK
- CloudSimExample2: OK
- CloudSimExample3: OK

## What This Stage Proves

1. **CSV ingestion works:** The Python pipeline's output can be read and validated by Java
2. **Validation is strict:** Invalid inputs are rejected clearly, not silently repaired
3. **Mapping is deterministic:** Same CSV → same VM mapping every time
4. **Scores are preserved:** Threat scores survive the Java→CloudSim mapping unchanged
5. **CloudSim integration works:** VMs with threat metadata can be created, simulated, and destroyed
6. **Existing code is safe:** No existing CloudSim examples were broken

## What Comes Next (Stage 2C and beyond)

This stage deliberately did NOT implement:
- NSSAO multi-objective VM allocation
- VM migration execution
- Adaptive threshold controller
- Co-residency risk modeling
- Server/host risk equations
- Alibaba CloudSim integration
- Large-scale simulation
- Final security experiments

These belong to later stages and require the foundation built here.

## Methodology Adherence

- ✅ Used existing `azure_to_azure_scores.csv` (did NOT regenerate)
- ✅ Used existing 8-feature vector (did NOT change)
- ✅ Used existing Isolation Forest scores (did NOT modify pipeline)
- ✅ ONE entity → ONE VM as simulation abstraction (clearly documented)
- ✅ No physical placement inferred from public datasets
- ✅ No fabricated labels or mappings
- ✅ No NSSAO/migration/adaptive threshold implemented

## Reproducibility

- Java: 24.0.1 (Maven target: 21)
- CloudSim: 7.0.1
- Maven: 3.9.16
- Score file: `project/outputs/azure_to_azure_scores.csv`
- Random seed in Python pipeline: 20260909
- Entity selection: deterministic (every Nth record)
- VM ID assignment: deterministic (sequential from base 0)
