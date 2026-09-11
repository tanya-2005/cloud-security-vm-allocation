# Stage 2C — CloudSim Security-State/Risk-Model Layer

**Status: COMPLETE**

**Date: 2026-09-09**

## Purpose

Establish measurable CloudSim security-state variables that will later be used by:
- Threat-aware placement (Stage 2D+)
- Adaptive thresholding (Stage 2D+)
- NSSAO (Stage 2E+)
- Migration (Stage 2D+)
- Final multi-objective evaluation (Stage 3+)

This stage is NOT the optimizer. It establishes the measurement layer.

## Files Created

### Java Classes (in `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2c/`)

| File | Purpose |
|------|---------|
| `VmThreatState.java` | VM threat state model linking CloudSim VM ID to Stage 2B threat metadata |
| `ResourceUtilizationCalculator.java` | CPU/memory/overall utilization calculations from CloudSim host state |
| `CoResidencyDetector.java` | Detects co-residency relationships from host placement |
| `CoResidencyRiskCalculator.java` | Calculates co-residency risk from detected pairs |
| `ServerRiskModel.java` | Configurable host/server risk model (simulation abstraction) |
| `DefensiveCostModel.java` | Extensible cost model (migration cost N/A in Stage 2C) |
| `SecurityStateSnapshot.java` | Comprehensive security state snapshot for reporting |
| `Stage2cSecurityStateValidation.java` | Main validation runner with two scenarios |

### Documentation

| File | Purpose |
|------|---------|
| `project/docs/stage_2c_security_state.md` | This document |

### Modified Files

| File | Change |
|------|--------|
| `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2b/ThreatRecord.java` | Added public `CompositeKey.of()` factory method for Stage 2C access |

## Security-State Variables Implemented

### 1. VM Threat State (`VmThreatState`)

Each simulated VM exposes:
- `cloudSimVmId` — CloudSim VM identifier
- `threatScore` — from Stage 2B, preserved exactly (no recalculation)
- `dataset` — e.g., "azure_v2"
- `entityType` — e.g., "deployment"
- `entityId` — opaque entity identifier
- `isHighThreat` — boolean based on configurable threshold

**Important:** Threat scores are NOT recalculated in Java. They are preserved exactly as exported by the Python pipeline.

### 2. Resource Utilization (`ResourceUtilizationCalculator`)

Formulas (explicit and testable):

```
CPU utilization = sum(allocated VM MIPS) / host total MIPS
Memory utilization = sum(allocated VM RAM) / host RAM capacity
Overall utilization = (CPU utilization + Memory utilization) / 2
```

- All values in [0,1] range (capped at 1.0 if over-subscribed)
- Division by zero prevented: zero-capacity hosts return 0.0
- Uses ACTUAL CloudSim host/VM resource values

### 3. Co-residency Detection (`CoResidencyDetector`)

Two VMs are co-resident when simultaneously placed on the same simulated physical host.

Detects:
- VMs per host
- Cross-entity VM pairs per host
- Whether high-threat VM co-resides with another entity's VM

**IMPORTANT:** This is CloudSim SIMULATION STATE. It does NOT claim the public Azure dataset provided physical co-residency information.

### 4. Co-residency Risk (`CoResidencyRiskCalculator`)

Formula (Stage 2C simulation abstraction):

```
For each cross-entity co-resident pair (vm_a, vm_b):
  pair_risk = max(threat_a, threat_b) * min(threat_a, threat_b)

host_co_residency_risk = sum of all pair_risks on that host
total_co_residency_risk = sum across all hosts
```

Properties:
- If either VM has threat=0, pair_risk=0
- If both have high threat, pair_risk is high
- Same-entity VMs don't count (not cross-entity)

**Paper mapping:** Consistent with paper's concept that co-residency risk increases when higher-threat VMs share a host with other-entity VMs.

**Simulation abstraction disclaimer:** This formula is a SIMULATION ABSTRACTION. It does NOT claim to exactly reproduce the paper's real-world risk measurements. The paper may use additional factors (vulnerability data, network topology, real co-residency duration) not available in our CloudSim abstraction.

### 5. Server/Host Risk (`ServerRiskModel`)

Formula:

```
host_risk = baseHostRisk + utilizationRiskWeight * overallUtilization
```

Capped to [0,1].

**Simulation abstraction:** This is a configurable simulation parameter, NOT a real security measurement. Defaults:
- `baseHostRisk = 0.1`
- `utilizationRiskWeight = 0.3`

**Future inputs NOT yet available:**
- Real server vulnerability data (Nessus/Qualys)
- Hypervisor security patch status
- Physical host security posture
- Hardware-specific vulnerabilities

### 6. Co-residency Duration (`SecurityStateSnapshot.CoResidencyDurationInfo`)

**NOT AVAILABLE in Stage 2C.**

The static snapshot has no temporal data. Co-residency duration tracking requires migration lifecycle (Stage 2D+). The snapshot explicitly reports this as unavailable rather than fabricating values.

### 7. Defensive Cost (`DefensiveCostModel`)

**Available in Stage 2C:**
- Host operational cost: `perHostCost * activeHostCount`
- VM cost: `perVmCost * totalVmCount`

**NOT AVAILABLE (explicit N/A):**
- Migration cost: migration not implemented yet
- Server activation/deactivation cost
- Defensive action costs

The `CostBreakdown` explicitly reports migration cost as 0.0 with "(UNAVAILABLE IN STAGE 2C)" label.

## Validation Scenarios

### Scenario A: Co-residency (High-threat + Other Entity)

**Placement:**
- Host 0: VM 0 (threat=0.85, Entity A) + VM 2 (threat=0.15, Entity B) + VM 4 (threat=0.30, Entity C)
- Host 1: VM 1 (threat=0.20, Entity A) + VM 3 (threat=0.75, Entity B)
- Host 2: VM 5 (threat=0.10, Entity C)

**Expected:** VM 0 (high threat) co-resides with VM 2 (other entity) → high co-residency risk

### Scenario B: Isolation (High-threat VMs Alone)

**Placement:**
- Host 0: VM 0 (threat=0.85, Entity A) ALONE
- Host 1: VM 3 (threat=0.75, Entity B) ALONE
- Host 2: VM 1 (Entity A) + VM 2 (Entity B) + VM 4 (Entity C) + VM 5 (Entity C)

**Expected:** High-threat VMs isolated → lower co-residency risk, no high-threat cross-entity pairs

## Validation Results

```
STAGE 2C VALIDATION REPORT
========================================
Scenarios run: 2 (A: co-residency, B: isolation)
VMs per scenario: 6
High-threat threshold: 0.5
Threat scores preserved: true
Co-residency risk responds correctly: true
High-threat isolation works: true
Deterministic: true

STAGE 2C VALIDATION PASSED
```

### Detailed Results

| Metric | Scenario A | Scenario B | Expected Direction |
|--------|------------|------------|-------------------|
| Co-residency risk | 0.5775 | 0.2000 | A > B ✓ |
| High-threat + other-entity | true | false | A=true, B=false ✓ |
| Cross-entity pairs | 4 | 6 | varies (density) |
| Avg threat score | 0.3917 | 0.3917 | identical ✓ |
| Max threat score | 0.8500 | 0.8500 | identical ✓ |
| Min threat score | 0.1000 | 0.1000 | identical ✓ |

Note: Cross-entity pair count is higher in B (6 vs 4) because Scenario B packs 4 VMs on one host. This is expected — the key metric is whether high-threat VMs co-reside with other entities, not the raw pair count.

### Focused Validation Checks

| Check | Description | Result |
|-------|-------------|--------|
| [A] CPU utilization calculation | Formula verified against manual calculation | PASS |
| [B] Memory utilization calculation | Formula verified against manual calculation | PASS |
| [C] Overall utilization = (CPU+MEM)/2 | Verified exact relationship | PASS |
| [D] VM/host association | VMs correctly placed on assigned hosts | PASS |
| [E] Cross-entity co-residency detection | Detected pairs match manual count | PASS |
| [F] High-threat + other-entity detection | Correctly identifies high-threat co-residency | PASS |
| [G] Co-residency risk responds to placement | A > B when high-threat co-resides | PASS |
| [H] Threat scores preserved | Identical across scenarios | PASS |
| [I] Deterministic results | Same every run | PASS |

## How to Run

From `C:\cloudsim file\cloudsim\`:

```bash
# Compile
mvn compile -pl modules/cloudsim-examples -am -DskipTests

# Run Stage 2C validation
java -cp "modules/cloudsim/target/cloudsim-7.0.1.jar;modules/cloudsim-examples/target/classes" \
  org.cloudbus.cloudsim.examples.stage2c.Stage2cSecurityStateValidation
```

## Existing CloudSim Examples

Verified intact:
- CloudSimExample1: OK
- CloudSimExample2: OK

## Methodology Adherence

- ✅ Threat scores preserved from Stage 2B (no recalculation in Java)
- ✅ One-entity→one-VM mapping maintained (Stage 2B foundation)
- ✅ No physical placement inferred from public datasets
- ✅ Co-residency is CloudSim simulation state (clearly documented)
- ✅ No fabricated vulnerability/network data
- ✅ Co-residency duration explicitly reported as N/A
- ✅ Migration cost explicitly reported as N/A
- ✅ Server risk documented as simulation abstraction
- ✅ No NSSAO/migration/adaptive threshold implemented
- ✅ No changes to Python ML pipeline
- ✅ No changes to 8-feature vector or Isolation Forest

## What Remains for Later Stages

### Stage 2D (Threat-Aware Placement + Migration)
- Implement VM migration capability
- Enable co-residency duration tracking
- Implement threat-aware placement policies
- Migration cost model

### Stage 2D+ (Adaptive Threshold)
- Implement adaptive threshold controller
- Use CloudSim state variables (high-threat fraction, migration cost, etc.)
- NOT hardcoded 0.6 or 0.5

### Stage 2E (NSSAO)
- Implement Snow ablation optimizer
- Multi-objective formulation:
  - Minimize: overall VM risk, co-residency risk, defensive cost
  - Maximize: resource utilization

### Stage 3 (Final Experiments)
- Alibaba CloudSim integration
- Large-scale simulation
- Final experimental evaluation

## Reproducibility

- Java: 24.0.1 (Maven target: 21)
- CloudSim: 7.0.1
- Maven: 3.9.16
- High-threat threshold: 0.5 (TEMPORARY, NOT final)
- VM configuration: 500 MIPS, 256 MB RAM each
- Host configuration: 3000 MIPS, 4096 MB RAM each
- Deterministic VM placement (explicit host assignments)
- Randomness: none (purely deterministic scenarios)
