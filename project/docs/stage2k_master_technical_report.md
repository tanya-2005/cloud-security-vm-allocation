# Stage 2K — Master Technical Report

**Status: COMPLETE**  
**Date: 2026-09-11**  
**Purpose: Assemble the complete factual technical record of Stages 2B–2J**

This report consolidates all project documentation into a single master technical record. It does NOT modify source code, Python/ML, experiment results, or graphs. It does NOT rerun experiments or create new analyses.

---

## Table of Contents

1. Project Overview
2. Research Background
3. Research Gap and Project Novelty
4. System Architecture
5. Dataset and Entity Methodology
6. Threat Detection Pipeline
7. Adaptive Threat Threshold
8. CloudSim Security-State Model
9. Migration and Co-Residency Duration
10. Multi-Objective Formulation
11. NSSAO Implementation
12. CloudSim Integration
13. Experimental Design
14. Experimental Results
15. Results Interpretation
16. Threats to Validity and Limitations
17. Research Question Answers
18. Reproducibility
19. Consistency Notes
20. Master Evidence Inventory
21. Current Project Status

---

## 1. Project Overview

### 1.1 Project Objective

This project implements and evaluates a threat-driven multi-objective VM allocation framework for cloud security. The framework combines:
- Unsupervised anomaly detection (Isolation Forest) on real cloud workload traces
- Threat-aware VM placement optimization (NSSAO)
- Multi-objective trade-off analysis (security vs. cost vs. resource efficiency)

### 1.2 Problem Being Addressed

**Co-residency attacks**: In cloud environments, malicious VMs may be co-located with target VMs on the same physical host, enabling side-channel attacks (timing, cache, memory) to extract sensitive information.

**Threat-aware placement**: Traditional VM placement optimizes for resource efficiency and SLA compliance but ignores security risks from co-residency.

### 1.3 Overall System Idea

The system detects workload-behavior anomalies from public cloud traces, assigns threat scores to entities, and uses a multi-objective optimizer (NSSAO) to generate placement decisions that balance:
- Security risk reduction (lower co-residency risk for high-threat VMs)
- Operational cost (migration and active-host costs)
- Resource efficiency (host utilization)

### 1.4 High-Level Workflow

```
Real cloud workload traces (Azure V2, Alibaba V2018)
  ↓ Python ML pipeline
Feature extraction → Isolation Forest → Threat scores
  ↓ Python-to-Java contract (CSV)
CloudSim VM creation with threat metadata
  ↓ Stage 2H
Deterministic initial placement (FFD)
  ↓ Stage 2G
NSSAO multi-objective optimization (hypothetical placements)
  ↓ Stage 2H
Pareto front → Single-solution selection
  ↓ Stage 2D
Actual VM migration
  ↓
Post-hoc metrics: objectives, migrations, co-residency duration
```

---

## 2. Research Background

### 2.1 Co-residency Problem

Cloud multi-tenancy creates co-residency risk: multiple VMs from different tenants sharing the same physical host. Co-resident VMs can exploit shared hardware resources to launch side-channel attacks:

- **Timing attacks**: Extract cryptographic keys
- **Cache attacks**: Monitor memory access patterns
- **Memory deduplication attacks**: Trigger page faults to infer memory content

**Key constraint**: Public cloud traces (Azure, Alibaba) do NOT provide physical host placement information. Co-residency in this project is a **simulation construct**, not trace-derived.

### 2.2 Threat-Aware VM Placement

The paper (Yang et al., IEEE TIFS 2026) proposes threat-aware VM allocation that:
- Classifies VMs as high-threat or normal using clustering on workload features
- Minimizes co-residency risk by separating high-threat VMs from other entities' VMs
- Balances security with resource efficiency through multi-objective optimization

### 2.3 Adaptive Migration

Migration can reduce co-residency risk by moving VMs away from dangerous hosts. However:
- Migration has cost (downtime, network bandwidth, energy)
- Excessive migration degrades service quality
- Optimal migration decisions require balancing security gains vs. cost

### 2.4 Role of NSSAO

**N**on-dominated **S**orting **S**now **A**blation **O**ptimizer (NSSAO) is a metaheuristic optimizer that:
- Uses dual populations (exploration and exploitation)
- Performs multi-objective optimization via Pareto dominance
- Generates a set of non-dominated solutions (trade-off front)

NSSAO is adapted from the paper's Algorithm 1 with documented project-specific adaptations (population size, discretization, repair strategy, selection rule).

### 2.5 Paper's Framework vs. Project Adaptations

| Aspect | Paper | Project |
|--------|-------|---------|
| Anomaly detection | Clustering | Isolation Forest |
| Datasets | Private traces | Azure V2 + Alibaba V2018 (public) |
| Objectives | 5 (f_ORS, f_Srisk, f_Φ, f_Ctotal, f_−RU) | 4 (f1–f4, duration post-hoc) |
| Threshold | Fixed 0.6 | Adaptive percentile-based |
| Co-residency duration | Objective f_Φ | Post-hoc metric (not optimized) |

---

## 3. Research Gap and Project Novelty

### 3.1 Implemented Contributions

1. **Cross-dataset evaluation**: Azure V2 + Alibaba V2018 threat-score populations, each with different distributions and adaptive thresholds

2. **Isolation Forest replacing clustering**: Unsupervised anomaly detection on workload features without ground-truth labels

3. **Adaptive/dynamic threshold**: Percentile-based threshold (P75 + tailWeight × (P90 − P75)) that responds to each scenario's threat-score distribution

4. **CloudSim implementation**: Full discrete-event simulation pipeline from threat scores to placement to migration

5. **Threat-aware placement / security-state modeling**: VM threat state, host risk, co-residency detection, and risk metrics in CloudSim

6. **Multi-objective NSSAO optimization**: Four-objective formulation with Pareto fronts and deterministic solution selection

7. **Migration and co-residency-duration tracking**: MigrationEvent recording, episode-based duration tracker

8. **Experimental comparison with B1/B2/B3**: Three project-defined baselines (FFD static, Best-Fit+f4 local search, random relocation)

### 3.2 Explicit Non-Claims

- NOT a novel theoretical algorithm (NSSAO is adapted from the paper)
- NOT an exact reproduction of the paper
- NOT proven optimal or convergent
- NOT validated on real cloud security attacks
- NOT claiming universal superiority

---

## 4. System Architecture

### 4.1 Python ML Pipeline

```
Raw trace data (Azure V2, Alibaba V2018)
  ↓ Preprocessing
8 common features per entity:
  - observation_start_seconds
  - observation_end_seconds
  - member_count
  - start_count
  - mean_start_interval_seconds
  - mean_member_lifetime_seconds
  - mean_cpu_activity_percent
  - cpu_variability_percent_points
  - mean_memory_demand
  - cpu_resource_footprint_core_seconds
  ↓ RobustScaler + median imputation
Isolation Forest (200 estimators, contamination='auto', max_samples capped)
  ↓ Score transformation
threat_score = clip((raw_max - raw_score) / (raw_max - raw_min), 0, 1)
  ↓ CSV export
outputs/azure_to_azure_scores.csv (248 entities)
outputs/alibaba_to_alibaba_scores.csv (67 entities)
```

### 4.2 Python-to-Java Interface

**Contract (Stage 2A):**
- Minimum payload: `(dataset, entity_type, entity_id, threat_score)`
- CSV encoding: UTF-8 with header row
- Validation: reject empty fields, non-finite scores, scores outside [0,1], duplicate composite keys

**Entity semantics:**
- Azure V2: `entity_type = deployment` (deployment-level workload aggregation)
- Alibaba V2018: `entity_type = batch_job` (batch-job workload aggregation)

### 4.3 CloudSim Simulation Layer

```
Stage 2B: ThreatScoreCsvReader → ThreatRecord list
         EntityVmMapping → 1 entity → 1 VM (simulation abstraction)
         
Stage 2C: VmThreatState (VM ID → threat metadata)
         ResourceUtilizationCalculator (CPU/mem/overall utilization)
         CoResidencyDetector (cross-entity pairs per host)
         ServerRiskModel (host risk = base + weight × utilization)
         CoResidencyRiskCalculator (pair risk = max(threat_a, threat_b))
         
Stage 2D: VmMigrationService (VM migration between hosts)
         CoResidencyDurationTracker (episode-based duration tracking)
         MigrationEvent (source→dest, timing, VM reference)
         
Stage 2E: AdaptiveThreatThreshold (percentile-based threshold)
         
Stage 2F: ObjectiveEvaluator (f1–f4 computation on candidate placements)
         
Stage 2G: NssaoOptimizer (dual-population multi-objective search)
         PlacementDecoder (continuous position → discrete host index)
         
Stage 2H: InitialPlacementStrategy (FFD)
         ParetoSolutionSelector (lexicographic selection from front)
```

### 4.4 Risk/Objectives

| Objective | Formula | Direction |
|-----------|---------|-----------|
| f1 | mean over VMs of [threat(vm) × serverRisk(host(vm))] | minimize |
| f2 | Σ over cross-entity co-resident pairs of max(threat_a, threat_b) | minimize |
| f3 | activeHosts × perHostCost + migrationCount × perMigrationCost | minimize |
| f4 | 1 − average host resource utilization (CPU+RAM)/2 | minimize |

### 4.5 Integration Flow

```
Stage 2H integration:
1. Initial FFD placement → snapshot t=0
2. NSSAO candidate generation → hypothetical placements
3. Objective evaluation on each candidate (Stage 2F)
4. Pareto front construction (Stage 2G)
5. Deterministic final selection: lexicographic min (f2, f3, f1, f4), index tie-break
6. Actual VM migration (Stage 2D service)
7. Migration events recorded
8. Snapshot t=1 → co-residency episodes finalized
9. Final metrics extracted
```

---

## 5. Dataset and Entity Methodology

### 5.1 Azure V2 Dataset

**Source:** Azure public VM traces (V2)

**Entity definition:** `deploymentid` representing deployment-level workload aggregation

**Entity type:** `deployment`

**Feature construction:** 8 features extracted from VM telemetry (timestamps, counts, CPU, memory)

**Record count:** 248 entities

**Important limitations:**
- Public trace does NOT provide physical host IDs
- Cannot reconstruct real co-residency from Azure data
- Deployment ≠ individual VM (aggregation unit)

### 5.2 Alibaba V2018 Dataset

**Source:** Alibaba Cloud public traces (V2018)

**Entity definition:** `job_name` representing batch-job workload aggregation

**Entity type:** `batch_job`

**Feature construction:** Same 8 features (aligned across datasets)

**Record count:** 67 entities

**Important limitations:**
- Public trace does NOT provide physical host IDs
- Cannot reconstruct real co-residency from Alibaba data
- `app_du` excluded due to unusable `container_id` values in public artifact

### 5.3 Explicit Entity Statements

- **Azure entity_type = deployment**
- **Alibaba entity_type = batch_job**
- These are **workload-behavior entities**, not individual VMs or physical hosts
- One entity → one simulated VM (Stage 2B simulation abstraction)
- Public traces do NOT provide physical host IDs sufficient to reconstruct real co-residency

### 5.4 Cross-Dataset Transfer

The project evaluates within-dataset scenarios:
- Azure scores → Azure VMs
- Alibaba scores → Alibaba VMs

Cross-scenario scores exist (azure_to_alibaba, alibaba_to_azure) but were not used in the main experiment suite.

---

## 6. Threat Detection Pipeline

### 6.1 Features (8 common features)

```
observation_start_seconds
observation_end_seconds
member_count
start_count
mean_start_interval_seconds
mean_member_lifetime_seconds
mean_cpu_activity_percent
cpu_variability_percent_points
mean_memory_demand
cpu_resource_footprint_core_seconds
```

### 6.2 Preprocessing

- **RobustScaler**: Median-based scaling, robust to outliers
- **Median imputation**: Missing feature values filled with median
- **No feature selection**: All 8 features used

### 6.3 Isolation Forest

**Algorithm:** Unsupervised anomaly detection

**Parameters:**
- `n_estimators = 200`
- `contamination = 'auto'`
- `max_samples = min(256, n_samples)` (source-size-capped)
- `random_state = 20260909`

**Output:** Anomaly scores (raw Isolation Forest scores)

### 6.4 Threat-Score Transformation

```
threat_score = clip(
    (source_raw_max - isolation_forest_score) / (source_raw_max - source_raw_min),
    0, 1
)
```

Higher threat_score = more anomalous workload behavior

### 6.5 Cross-Dataset Transfer

Source-calibrated scores are suitable for within-dataset ranking. They are NOT directly comparable across datasets during transfer because feature distributions differ and target scores can be clipped at source calibration bounds.

### 6.6 Lack of Ground-Truth Labels

- Azure V2 and Alibaba V2018 do NOT provide reliable attack labels
- Isolation Forest is unsupervised (no training labels)
- Threat scores are **behavioral anomaly indicators**, not proof of malicious intent

### 6.7 Why Accuracy/Precision/Recall/F1/ROC-AUC Were Not Reported

- No ground-truth labels exist for attack/non-attack classification
- Isolation Forest produces anomaly scores, not classifications
- Performance metrics require labeled test data (unavailable)
- Threshold is adaptive and scenario-specific (not a fixed classifier)

---

## 7. Adaptive Threat Threshold

### 7.1 Formula (Exact Implementation)

```
raw = P75 + tailWeight × (P90 − P75)

threshold = clamp(raw, lowerBound, upperBound)
```

**Current defaults:**
- `tailWeight = 0.5`
- `lowerBound = 0.5`
- `upperBound = 0.9`

**Where:**
- P75 = 75th percentile of threat-score population
- P90 = 90th percentile of threat-score population
- Percentiles computed via linear interpolation on sorted scores

### 7.2 Why Not Fixed Thresholds

- **Paper's 0.6** is a fixed constant calibrated for a specific dataset and clustering method
- **Temporary 0.5** (Stage 2C/2D testing) was chosen for validation, not as production threshold
- A universal fixed threshold ignores score-distribution differences across datasets

### 7.3 Real-Data Behavior (Stage 2E.1)

| Scenario | N | P75 | P90 | raw | threshold | high-threat % |
|----------|---|-----|-----|-----|-----------|---------------|
| Azure within | 248 | 0.2915 | 0.5436 | 0.4176 | **0.5000** (floor) | 12.50% |
| Alibaba within | 67 | 0.3182 | 0.5852 | 0.4517 | **0.5000** (floor) | 16.42% |
| Azure→Alibaba | 67 | 0.9965 | 1.0000 | 0.9983 | **0.9000** (ceiling) | 32.84% |
| Alibaba→Azure | 248 | 1.0000 | 1.0000 | 1.0000 | **0.9000** (ceiling) | 93.55% |

**Observation:** The bounded percentile rule is currently **boundary-dominated** for these distributions. Within-dataset scores have low P75/P90 → threshold clamps to floor (0.5). Cross-scenario scores saturate → threshold clamps to ceiling (0.9).

### 7.4 Classification Rule

```
VM is high-threat ⟺ threat_score ≥ adaptive_threshold
```

### 7.5 Why 0.6 Was NOT Adopted

- Calibrated for paper's specific clustering method
- Paper's dataset differs from Azure/Alibaba
- Project uses Isolation Forest (different anomaly scoring)
- Adaptive threshold responds to actual score distribution

---

## 8. CloudSim Security-State Model

### 8.1 VM Threat State (`VmThreatState`)

Each simulated VM carries:
- `cloudSimVmId` — CloudSim VM identifier
- `threatScore` — from Python pipeline (preserved exactly)
- `dataset` — e.g., "azure_v2"
- `entityType` — e.g., "deployment"
- `entityId` — opaque entity identifier
- `isHighThreat` — boolean based on configured threshold

**Important:** Threat scores are NOT recalculated in Java. They are preserved exactly as exported by Python.

### 8.2 CPU/Memory Utilization

**Formulas:**
```
CPU utilization = Σ allocated VM MIPS / host total MIPS
Memory utilization = Σ allocated VM RAM / host RAM capacity
Overall utilization = (CPU utilization + Memory utilization) / 2
```

- All values in [0,1] range (capped at 1.0 if over-subscribed)
- Division by zero prevented (zero-capacity hosts return 0.0)
- Uses ACTUAL CloudSim host/VM resource values

### 8.3 Co-Residency Detection

Two VMs are co-resident when simultaneously placed on the same simulated physical host.

**Detected:**
- VMs per host
- Cross-entity VM pairs per host (different dataset/entityType/entityId)
- Whether high-threat VM co-resides with another entity's VM

**IMPORTANT:** This is CloudSim SIMULATION STATE. It does NOT claim the public datasets provided physical co-residency information.

### 8.4 Server/Host Risk

**Formula:**
```
host_risk = baseHostRisk + utilizationRiskWeight × overallUtilization
```

Capped to [0,1].

**Defaults:** `baseHostRisk = 0.1`, `utilizationRiskWeight = 0.3`

**Simulation abstraction:** This is a configurable simulation parameter, NOT a real security measurement.

**Unavailable components:**
- Real server vulnerability data (Nessus/Qualys)
- Hypervisor security patch status
- Physical host security posture
- Hardware-specific vulnerabilities
- Network topology risk

### 8.5 Co-Residency Risk

**Stage 2C diagnostic formula:**
```
pair_risk = max(threat_a, threat_b) × min(threat_a, threat_b)
```

**Stage 2F optimization f2 formula:**
```
f2 = Σ over cross-entity co-resident pairs of max(threat_a, threat_b)
```

**Distinction:** Stage 2C uses `max × min` for diagnostic purposes. Stage 2F f2 uses `max` only for optimization.

### 8.6 Project-Specific Abstractions vs. Paper-Faithful Concepts

| Component | Paper | Project |
|-----------|-------|---------|
| Server risk | May include vulnerability data | `base + weight × utilization` (simulation abstraction) |
| Co-residency | Real cloud telemetry | CloudSim simulated placement only |
| Vulnerability | Part of risk model | Unavailable (no scanner data) |
| Network | Part of risk model | Unavailable (no topology data) |

---

## 9. Migration and Co-Residency Duration

### 9.1 MigrationEvent

Represents a single VM migration:
```
MigrationEvent {
    int vmId
    int sourceHostId
    int destinationHostId
    double migrationStartTime
    double migrationTimestamp
    Vm migratedVm
}
```

### 9.2 VmMigrationService

**Responsibilities:**
- Validate migration requests (VM exists, source has VM, destination exists)
- Move VM between host guest lists
- Preserve VM threat metadata (VmThreatState NOT affected)
- Record MigrationEvent for each migration

**Rejection conditions:**
- Same-host migration (source == destination)
- VM not on source host
- VM not in VmThreatState map
- Null VM/host

### 9.3 Migration Cost Model

```
Migration cost = migrationCount × perMigrationCost
```

**Default:** `perMigrationCost = 1.0`

**Simulation abstraction:** This is a configurable simulation parameter. Real migration cost factors NOT modeled:
- VM downtime during migration
- Network bandwidth consumption
- Memory dirty-page iteration overhead
- Storage migration cost
- Energy consumption

### 9.4 Duration Tracker

**CoResidencyDurationTracker:**
- `recordSnapshot(hosts, simulationTime)` — snapshot current co-residency state
- Compares consecutive snapshot pairs to detect:
  - **START**: pair present in current but not in previous
  - **END**: pair in previous but not in current
  - **CONTINUE**: pair in both

**Pair identity:** `(vmId1, vmId2, hostId)` — VM pair moving to different host ends the old episode and starts a new one.

**Multiple episodes:** Same VM pair can have multiple separate episodes (not merged).

### 9.5 Instantaneous Migration Abstraction

**Stage 2D treats migration as effectively instantaneous:**
- `migrationStartTime == migrationTimestamp`
- This is a SIMULATION ABSTRACTION
- Real migrations involve downtime, network transfer, memory dirty-page iteration

### 9.6 Two-Snapshot Timeline Limitation

**Timeline:**
1. Initial placement (t=0): snapshot taken
2. NSSAO runs (no physical state change)
3. Selected candidate applied (t=t_migrate): migrations execute, snapshot taken
4. Simulation END (t=t_end): final snapshot

**Limitation:** Duration is measured from only TWO snapshots (t=0 and t_migrate). Under this design, co-residency duration is **structurally invariant across methods** because all VMs occupy the fleet for the same interval.

---

## 10. Multi-Objective Formulation

### 10.1 Four Objectives (ALL MINIMIZE)

#### f1 — Threat-Weighted Server Risk

```
f1 = mean over VMs of [ threat(vm) × serverRisk(host(vm)) ]

serverRisk(host) = clamp(baseHostRisk + utilizationRiskWeight × overallUtil(host), 0, 1)
overallUtil(host) = (cpuUtil + memUtil) / 2
```

- Threat scores preserved from Python pipeline
- Server risk computed from candidate placement utilization (not live state)

#### f2 — Cross-Entity Co-Residency Risk

```
f2 = Σ over all cross-entity VM pairs sharing the same host of max(threat_a, threat_b)
```

- Cross-entity: two VMs differ in `(dataset, entityType, entityId)`
- Same-entity pairs contribute NOTHING
- **Distinction:** Stage 2C uses `max × min`; Stage 2F f2 uses `max` only

#### f3 — Defensive Cost

```
f3 = activeHosts × perHostCost + migrationCount × perMigrationCost
```

- `activeHosts`: hosts with ≥ 1 assigned VM
- `migrationCount`: VMs whose candidate host ≠ current host
- `perHostCost = 1.0`, `perMigrationCost = 1.0`

#### f4 — Unused Capacity

```
f4 = 1 − (avgCPUUtil + avgMemUtil) / 2
```

- Averages include ALL hosts (including empty)
- Lower f4 = tighter packing = higher utilization

### 10.2 Why Duration Is Not an Objective

The paper's `f_Φ` (co-residency duration) objective is deliberately excluded from Stage 2F optimization. Duration remains a post-hoc metric measured by Stage 2D tracker after the placement decision.

**Rationale:** Duration measurement requires a migration timeline; optimizing it would require a different experimental design.

### 10.3 Paper vs. Project Objective Mapping

| Paper | Project | Notes |
|-------|---------|-------|
| f_ORS (overall VM risk) | f1 (threat × server-risk) | Approximate mapping |
| f_Srisk (server co-residency risk) | f2 (cross-entity max-threat) | Formula differs |
| f_Φ (co-residency duration) | Post-hoc metric | Not optimized |
| f_Ctotal (defensive cost) | f3 (active-host + migration) | Consistent |
| f_−RU (negative utilization) | f4 (unused capacity) | Inverted to minimization |

---

## 11. NSSAO Implementation

### 11.1 Paper-Faithful Elements

**From paper (Yang et al., IEEE TIFS 2026):**
- Real-valued per-VM positions bounded `[0, P−1]`
- Dual-population structure (exploration P_a, exploitation P_b)
- Position-update equations (Eq. 21–24):
  - Exploration: `X_i(t+1) = X_best + A·dir` (Eq. 22)
  - Exploitation: `X_i(t+1) = M·X_best + A·dir` (Eq. 24)
- Gaussian perturbation A(t) ~ N(0,1)
- Non-dominated sorting + crowding distance selection
- Termination by t_max or no-improvement stall

### 11.2 Project Adaptations

| Item | Paper | Project Adaptation |
|------|-------|-------------------|
| Population size N | 2000 | 40 (configurable, validation scale) |
| Max iterations t_max | 10 | 10 (paper-faithful) |
| Discretization | NOT SPECIFIED | `Math.rint + clamp` (Option A) |
| Infeasible-solution handling | NOT SPECIFIED | Repair-first deterministic greedy |
| Melting factor M(t) | "Eq. (10)" (unrecoverable) | Linear anneal 1.0 → 0.1 |
| Sub-population schedule | "P_b decreases" | Linear: `|P_b|(t) = round(N/2·(1−t/t_max))` |
| Stall limit | "preset number" | 3 iterations |
| Best solution X_best | NOT DEFINED | Lexicographic on front 1 (larger CD, lower f1, lower index) |
| Rounding rule | NOT SPECIFIED | `Math.rint` (banker's rounding) |

### 11.3 Discretization Strategy

**Option A (implemented):**
```
hostIndex = clamp(round(x_k), 0, H−1)
```

- `Math.rint` = banker's rounding (deterministic, no positive bias)
- Multiple VMs can share a host (legitimate co-residency)
- Every VM gets exactly one host by construction

### 11.4 Repair Strategy

**Repair-first deterministic greedy:**
1. Hosts processed in ascending ID
2. On each overloaded host, move the movable VM with smallest CPU demand (ties: lower VM ID)
3. Destination = feasible host minimizing resulting CPU utilization (ties: lower host ID)
4. If no feasible destination exists → candidate rejected, slot falls back to current placement's objectives
5. Never silent, never penalty

### 11.5 Pareto Selection

**Dominance:** a dominates b iff ∀k f_k(a) ≤ f_k(b) and ∃k f_k(a) < f_k(b) (all objectives minimized)

**Non-dominated sorting:** Fast NDS on raw values

**Crowding distance:** Deb NSGA-II form on per-objective min-max normalized values

**Environmental selection:** Fill fronts to N; overflow front split by descending CD

### 11.6 Final-Solution Selection (Stage 2H)

NSSAO returns the final Pareto front (front 1), which may contain multiple non-dominated solutions.

**Selection rule (lexicographic on front 1):**
```
min (f2, f3, f1, f4), index tie-break
```

**Priority order justification:**
1. f2 (co-residency risk) FIRST — security is primary motivation
2. f3 (cost) SECOND — quantifiable operational cost
3. f1 (threat exposure) THIRD — secondary security dimension
4. f4 (unused capacity) LAST — resource efficiency as final tie-break

**Deduplication:** Front 1 is deduplicated by unique placement BEFORE selection (multiple positions may decode to the same placement).

### 11.7 Stage 2G.3 Audit Limitations

| Issue | Severity | Notes |
|-------|----------|-------|
| Repair shapes search distribution | Medium | 5–8 of 12 candidates repaired in validation; repair prefers low-utilization, low-ID hosts |
| Front-1 inflation from duplicates | Medium | Distinct positions decode to same placement; CD treats as distinct |
| No single-solution selection in NSSAO | Low | Deferred to Stage 2H (now resolved) |
| Rejected-decode fallback | Low | Never triggered in validation (rejectedDecodeCount = 0) |
| Paper parameters vs. project scale | Low | N=2000 vs N=40; behavior at scale untested |
| Melting factor, stall limit assumptions | Low | Documented adaptations; paper unspecified |

---

## 12. CloudSim Integration

### 12.1 Initial Placement (FFD)

**Algorithm:** First-Fit Decreasing by CPU demand

```
1. Sort VMs by decreasing MIPS demand (ties: ascending VM ID)
2. For each VM in sorted order:
   - Find first host with sufficient CPU AND RAM capacity
   - Assign VM to that host
3. If any VM cannot be placed → FAIL CLEARLY
```

**Properties:**
- Deterministic (same input → same placement)
- Guaranteed feasible if aggregate demand ≤ aggregate capacity
- NOT security-aware, NOT optimal

### 12.2 NSSAO Candidate Generation

**Inputs:**
- Current placement (from FFD)
- Movable VM list (all VMs movable in validation)
- Host capacities
- Threat scores
- Seeded RNG

**Process:**
- Generate N population members (positions in [0, H−1]^M)
- Decode each position → candidate placement
- Evaluate each candidate (Stage 2F ObjectiveEvaluator)
- Apply NSSAO updates for t_max iterations or stall

### 12.3 Objective Evaluation

**Stage 2F ObjectiveEvaluator:**
- Validates candidate (no capacity violations, no missing/duplicate assignments)
- Computes f1–f4 on candidate placement
- Returns raw objective values

**Purity:** Evaluation is read-only; no live CloudSim state mutation

### 12.4 Pareto Front

**Output:** Set of non-dominated solutions (front 1)

**Properties:**
- No member dominates another
- Each member represents a trade-off point

### 12.5 Deterministic Final Selection

**Rule:** Lexicographic min (f2, f3, f1, f4) on deduplicated front 1

**Result:** Exactly one placement selected from front

### 12.6 Actual Migrations

**Process:**
1. Selected `CandidatePlacement` specifies target VM→host mapping
2. Compare against current live CloudSim state
3. For each VM with target host ≠ current host:
   - Call `VmMigrationService.migrateVm(vm, sourceHost, destHost, simulationTime)`
   - Record MigrationEvent
4. Verify final live state matches selected placement

### 12.7 Migration Events

**Recorded:**
- VM ID
- Source host ID
- Destination host ID
- Migration timestamp

**Counting:** Migration count = number of VMs with changed host assignment

### 12.8 Final Metrics

**Objective values:** f1–f4 of selected placement

**Placement statistics:**
- Active hosts
- CPU/memory utilization
- Migration count/cost

**Co-residency duration:**
- Total duration
- High-threat duration
- Episode count

**NSSAO statistics:**
- Iterations executed
- Stopped by stall
- Rejected decodes
- Front-1 size (raw and unique)

---

## 13. Experimental Design

### 13.1 Datasets

**Two within-dataset threat-score populations:**
- Azure-derived: 248 entities
- Alibaba-derived: 67 entities

**NOT pooled:** Each dataset evaluated separately

### 13.2 Profiles

| Profile | Definition | Purpose |
|---------|-----------|---------|
| A — Low threat | Lowest-score entities | Methods differentiate least |
| B — Moderate | Middle-score entities | Realistic mixed population |
| C — High threat | Highest-score entities | Threat-awareness matters most |
| D — Co-residency pressure | High VM:host ratio, many cross-entity pairs | f2 separation |
| E — Resource pressure | 85–95% aggregate utilization | Security vs. feasibility tension |

### 13.3 Scales

| Scale | VMs | Hosts |
|-------|-----|-------|
| S | 8 | 3 |
| M | 24 | 8 |
| L | 48 | 16 |

### 13.4 Scale-F Cells

**Approved scale-F cells:** B-azure-L and B-alibaba-L (profile B at scale L)

**All other cells at scale M**

**Total cells:** 12 (profiles A–E × both datasets, minus missing, plus scale-F)

### 13.5 Methods

| Method | Description | Provenance |
|--------|-------------|-----------|
| Proposed (NSSAO) | Threat-aware multi-objective optimization | Adapted from paper |
| B1 FFD static | First-Fit Decreasing initial placement only | Project-defined |
| B2 Best-Fit+f4 LS | Best-Fit + utilization-oriented local search | Project-defined |
| B3 Random reloc. | Seeded random feasible relocation | Project-defined |

**B1/B2/B3 are project-defined baselines, NOT from the paper.**

### 13.6 Seeds

**5 seeds per (cell × method):** 1, 2, 3, 4, 5

**Total runs:** 12 cells × 4 methods × 5 seeds = **240 runs**

### 13.7 Fairness Controls

**Identical across all methods per cell:**
- VM set, host set, per-VM resource requirements
- Threat scores
- Adaptive threshold
- Initial placement (FFD)
- Cost parameters
- Server-risk parameters
- Evaluation code path (single ObjectiveEvaluator instance)

**Live-state invariant:** Baseline searches run on view models only; runner applies final placement once.

### 13.8 Metrics

**Primary:** f1, f2, f3, f4 (all minimized)

**Secondary:** migration count/cost, active hosts, CPU/memory utilization, co-residency duration, episode count

### 13.9 Descriptive Statistical Treatment

- Report: mean, median, standard deviation, min, max per cell per method
- **No p-values, no hypothesis tests** (n=5 seeds, deterministic competitors)
- Paired differences: proposed − baseline per seed
- Dominance verdicts: method X dominates Y iff X ≤ Y on all objectives and < on at least one

---

## 14. Experimental Results

### 14.1 Run Accounting

| Item | Count |
|------|-------|
| Expected runs | 240 |
| Completed runs | 240 |
| Failed runs | 0 |
| Missing keys | 0 |
| Duplicate keys | 0 |

### 14.2 f2 (Cross-Entity Co-Residency Risk) — Primary Finding

**NSSAO has the lowest median f2 in all 12 cells.**

| Cell | NSSAO | B1 FFD | B2 Best-Fit | B3 Random |
|------|-------|--------|-------------|-----------|
| A-azure-M | 0.0000 | 0.0116 | 0.0051 | 0.0043 |
| C-azure-M | 1.6219 | 24.2575 | 10.8336 | 5.2756 |
| D-azure-M | 3.8529 | 4.8099 | 4.2729 | 4.6525 |

**Largest gaps:** C cells (f2 reduced ~10–15× vs B1)

### 14.3 f3 (Defensive Cost) — Trade-off

**NSSAO has the highest median f3 in 11 of 12 cells.**

**Tie:** E-azure-M median tie (17 vs 17 with B3); B3 lower in 3/5 individual seeds

**Cause:** More migrations (NSSAO migrates most in 8/12 cells; B2 migrates more in A/C cells: 10 vs 8)

### 14.4 f4 (Resource Inefficiency) — Efficiency Trade-off

**B2 has better (lower) f4 in 10 of 12 cells; tied in both E cells (never worse).**

**Interpretation:** NSSAO packs less tightly than pure efficiency search.

### 14.5 f1 (Threat × Server-Risk) — Mixed Results

**NSSAO ties or beats B1/B2 in all cells.**

**B3 beats NSSAO on f1 in 4 cells:** B-azure-L, D-azure-M, D-alibaba-M, E-alibaba-M

**Unfavorable finding:** A seeded random search can match or beat NSSAO on f1 under D/E pressure.

### 14.6 Migration Count/Cost

**Migration count ↔ migration cost is 1:1** (perMigrationCost = 1)

**NSSAO migration counts:**
- A/C cells: 8 (fewer than B2's 10)
- B-M cells: 20–21
- B-L cells: 44–45
- D cells: 24–26
- E cells: 13–16

**B1:** 0 migrations in all runs

### 14.7 Co-Residency Duration

**Total co-residency duration is IDENTICAL across all four methods within each cell.**

**Reason:** Two-snapshot timeline design (t=0 placement, t=1 migration). All VMs occupy the fleet for the same interval.

**High-threat duration:** 0 in most cells; C-azure=8, C-alibaba=1, B-alibaba-L=5

**Episode counts differ by method, but duration is invariant.**

### 14.8 Dataset Comparison

**Azure vs. Alibaba:** Direction-consistent results (NSSAO lowest f2 in all cells for both datasets)

**Magnitudes differ:** C-azure B1 f2 = 24.26 vs C-alibaba = 19.82

**Thresholds differ:** C-azure threshold = 0.900; C-alibaba threshold = 0.775

### 14.9 Profile-Wise Behavior

- **A (low):** f1 nearly identical; f2 small but ordered
- **B (moderate):** Clear f2 separation; scales to L
- **C (high):** Strongest differentiation (B1 f2 ~15× NSSAO's)
- **D (co-residency pressure):** f2 gaps compress under tight packing
- **E (resource pressure):** Capacity ceiling flattens most differences

### 14.10 Seed Variability

**B3 (random relocation) shows widest seed variability.**

**NSSAO, B1, B2 are deterministic per seed.**

**Same-seed reproducibility:** 32/32 re-runs match (f1–f4, migrations, fingerprints)

### 14.11 Corrected Claims (from Stage 2J Audit)

| Original Claim | Correction |
|----------------|------------|
| NSSAO highest f3 in all 12 cells | **Strict in 11/12; median tie with B3 in E-azure-M** |
| B2 better f4 in all 12 cells | **Strict in 10/12; tied in both E cells; never worse** |
| NSSAO migrates most in every cell | **Most in 8/12; B2 migrates more in A/C (10 vs 8)** |

---

## 15. Results Interpretation

### 15.1 OBSERVED Findings

1. NSSAO produces lower f2 than B1, B2, and B3 in all 12 cells (5/5 seeds vs B1/B2; 4–5/5 vs B3)

2. f2 reduction is largest in high-threat cells (C: ~10–15× vs B1)

3. NSSAO's f3 is higher in 11/12 cells (median tie in E-azure-M)

4. NSSAO performs more migrations in 8/12 cells (B/D/E)

5. B2 has better f4 in 10/12 cells (tied in E cells, never worse)

6. B3 beats NSSAO on f1 in 4/12 cells (D/E pressure + B-azure-L)

7. All 36 proposed-vs-baseline comparisons are TRADE-OFFs (no dominance in either direction)

8. Co-residency duration is invariant across methods under two-snapshot design

9. Azure and Alibaba produce direction-consistent results with different magnitudes

10. B1 performs zero migrations in all 240 runs (validated)

### 15.2 INTERPRETATION

- The proposed method **trades additional defensive cost and migrations for lower modeled co-residency risk**.

- The security gain is captured by **f2** (risk-weighted co-residency), NOT by duration reduction.

- **NSSAO is neither universally dominant nor dominated** — it offers a specific trade-off profile.

- Under capacity pressure (D/E), the security advantage narrows and cost rises.

- A seeded random search can match NSSAO on f1 under pressure — NSSAO's f1 advantage is not universal.

### 15.3 LIMITATION

- Results are from a **simulated environment** with modeled risk abstractions.

- **No real-world security improvement claim** is supported.

- **No optimality claim** is supported.

- **No generalization beyond tested cells** is claimed.

---

## 16. Threats to Validity and Limitations

### 16.1 Documented Limitations (from Stage 2J)

1. **Public traces provide no physical host / co-residency ground truth.** Scores are inputs, not placement facts.

2. **One workload entity → one simulated VM** abstraction; multi-VM entities unmodeled.

3. **Project-specific risk abstractions** (threat score, server-risk model, f2 aggregation) — other formulations could rank methods differently.

4. **Vulnerability / network components unavailable** in the risk model.

5. **Instantaneous migration abstraction** — no downtime, no live-migration cost beyond unit cost.

6. **Two-snapshot duration limitation** — co-residency duration cannot distinguish methods.

7. **Small simulated scale** (≤ 48 VMs / 16 hosts).

8. **Stage 2G repair instrumentation unavailable** — repair counts/rates recorded as NA; NSSAO's repair/search-distribution behavior unmeasured.

9. **Dataset feature/unit differences** between Azure V2 and Alibaba V2018 complicate magnitude comparisons.

10. **Adaptive threshold boundary behavior** — several cells land exactly on [0.5, 0.9] bounds; threshold sensitivity untested.

11. **NSSAO search-distribution limitations** documented in Stage 2G audit (melting schedule, discretization, stall limit) apply unchanged.

### 16.2 No Additional Limitations Invented

This report does NOT add limitations beyond those documented in Stage 2J.

---

## 17. Research Question Answers

### RQ1 (Threat Exposure, f1)

**Answer: PARTIALLY SUPPORTED.**

**Evidence:** NSSAO achieved lower f1 than B1/B2 in 12/12 cells.

**Qualification:** NSSAO lost or tied vs B3 in 5/12 cells (D/E pressure + B-azure-L). The f1 advantage is not robust against random search under pressure.

### RQ2 (Co-Residency Risk, f2)

**Answer: SUPPORTED.**

**Evidence:** NSSAO lowest f2 in all 12 cells (5/5 seeds vs B1/B2; medians vs B3 in 12/12).

**Qualification:** Modeled risk in a simulator; gap compresses under D/E pressure; no significance testing.

### RQ3 (Security/Resource/Cost Trade-off)

**Answer: SUPPORTED.**

**Evidence:** f3/f4 paired differences; 36/36 TRADE-OFF verdicts.

**Interpretation:** Security gain is paid for in cost (11/12 cells) and packing efficiency (B2 better or equal in 12/12). The "acceptable" exchange rate is a judgment, not a measurement.

### RQ4 (Migration Cost of Security)

**Answer: SUPPORTED.**

**Evidence:** Migration counts (0 for B1; 8–45 for NSSAO; 4–35 for B2/B3); 1:1 cost mapping.

**Interpretation:** NSSAO's f2 reduction required 8–45 migrations per cell.

**Qualification:** Instantaneous-migration abstraction; unit cost is a design constant.

### RQ5 (Consistency Across Azure/Alibaba)

**Answer: SUPPORTED (DESCRIPTIVELY).**

**Evidence:** Per-dataset direction agreement on every objective in every profile cell.

**Qualification:** Two datasets only; different magnitudes and thresholds; no generalization claim.

---

## 18. Reproducibility

### 18.1 Python Environment

- Python version: 3.x (project virtual environment)
- Key packages: scikit-learn, pandas, numpy, joblib
- Isolation Forest seed: 20260909
- Output: `project/outputs/*.csv`

### 18.2 Java/CloudSim Environment

- Java runtime: 24.0.1
- Maven compiler target: Java 21
- CloudSim version: 7.0.1
- Maven version: 3.9.16
- Repository: `cloudsim/modules/cloudsim` and `cloudsim/modules/cloudsim-examples`

### 18.3 Seeds

- Python ML pipeline: 20260909
- Java experiments: 1, 2, 3, 4, 5 per cell × method
- NSSAO: seed passed from experiment runner
- B3: seeded RNG

### 18.4 Deterministic Behavior

- Same seed → identical results (validated 32/32 re-runs)
- Deterministic initial placement (FFD)
- Deterministic NSSAO decode/repair/evaluation
- Deterministic selection rule

### 18.5 Experiment Artifacts

- Raw results: `project/results/stage2i3/suite_runs.csv` (240 rows)
- Manifest: `project/results/stage2i3/suite_manifest.csv`
- Reproducibility check: `project/results/stage2i3/repro_runs.csv` (32 rows)
- Analysis: `project/results/stage2i4/` (validation_report.txt, analysis_summary.csv, cell_method_summary.csv, paired_method_differences.csv)
- Figures: `project/results/stage2i5/` (8 figures × PNG+SVG + source CSVs)

### 18.6 Visualization Generator

**Script:** `project/scripts/stage2i5_make_figures.py`

**Usage:** `python project/scripts/stage2i5_make_figures.py`

**Output:** Regenerates all 8 figures from stored CSVs only (no manual values)

### 18.7 Regression Validation

All validation harnesses pass:
- Stage2bValidation: PASSED
- Stage2cSecurityStateValidation: PASSED
- Stage2dMigrationValidation: PASSED
- Stage2eAdaptiveThresholdValidation: PASSED
- Stage2eRealDataThresholdValidation: PASSED
- Stage2fObjectiveValidation: PASSED
- Stage2gNssaoValidation: PASSED
- Stage2hValidation: PASSED
- CloudSimExample1/2: OK

---

## 19. Consistency Notes

### 19.1 Terminology Consistency

**No inconsistencies discovered.** All stages use consistent terminology for:
- Entity types (deployment, batch_job)
- Objectives (f1–f4)
- Threat scores
- Co-residency definitions

### 19.2 Formula Consistency

**Stage 2C vs. Stage 2F co-residency formula:**
- Stage 2C diagnostic: `pair_risk = max × min`
- Stage 2F optimization f2: `Σ max(threat_a, threat_b)`

**Explicitly different by design.** Stage 2C is diagnostic; Stage 2F is optimization objective.

### 19.3 Threshold Consistency

- Stage 2C/2D used 0.5 for testing (documented as TEMPORARY)
- Stage 2E replaced with adaptive threshold
- Paper's 0.6 was NEVER adopted as project threshold
- All experiment runs use adaptive threshold per scenario

### 19.4 No Silent Fixes

No documentation/source discrepancies were silently fixed. All adaptations are explicitly documented in their respective stage documents.

---

## 20. Master Evidence Inventory

### 20.1 Python Pipeline

- `project/ml_pipeline/*.py` — ML pipeline code
- `project/outputs/azure_to_azure_scores.csv` — Azure within-dataset scores (248 entities)
- `project/outputs/alibaba_to_alibaba_scores.csv` — Alibaba within-dataset scores (67 entities)
- `project/outputs/azure_to_alibaba_scores.csv` — Cross-scenario scores (not used in main experiment)
- `project/outputs/alibaba_to_azure_scores.csv` — Cross-scenario scores (not used in main experiment)
- `project/models/*.joblib` — Trained Isolation Forest models
- `project/models/*.json` — Calibration metadata

### 20.2 CloudSim Stage Packages

- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2b/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2c/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2d/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2e/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2f/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2g/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2h/`
- `cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2i/`

### 20.3 Validation Documents

- `project/docs/python_cloudsim_contract.md` — Stage 2A interface contract
- `project/docs/stage_2b_validation.md`
- `project/docs/stage_2c_security_state.md`
- `project/docs/stage_2d_migration_duration.md`
- `project/docs/stage_2e_adaptive_threshold.md`
- `project/docs/stage_2f_objective_evaluation.md`
- `project/docs/stage_2g1_nssao_design.md`
- `project/docs/stage_2g2_nssao_implementation.md`
- `project/docs/stage_2g3_nssao_audit.md`
- `project/docs/stage_2h_cloudsim_integration.md`
- `project/docs/stage_2i1_experiment_design.md`
- `project/docs/stage_2i2_experiment_framework.md`
- `project/docs/stage_2i3_experiment_run.md`
- `project/docs/stage_2i4_results_analysis.md`
- `project/docs/stage2i5_visualization.md`
- `project/docs/stage2j_results_interpretation.md`

### 20.4 Experiment Results

- `project/results/stage2i3/suite_runs.csv` — 240 raw experiment rows
- `project/results/stage2i3/suite_manifest.csv` — Scenario configurations
- `project/results/stage2i3/repro_runs.csv` — Reproducibility validation
- `project/results/stage2i4/validation_report.txt` — Analysis validation
- `project/results/stage2i4/analysis_summary.csv` — Descriptive statistics
- `project/results/stage2i4/cell_method_summary.csv` — Per-cell per-method summaries
- `project/results/stage2i4/paired_method_differences.csv` — Proposed − baseline differences

### 20.5 Figures

- `project/results/stage2i5/fig1_f2_coresidency_risk.png/.svg` — f2 by cell
- `project/results/stage2i5/fig2_f3_defensive_cost.png/.svg` — f3 by cell
- `project/results/stage2i5/fig3_f4_resource_inefficiency.png/.svg` — f4 by cell
- `project/results/stage2i5/fig4_f1_threat_server_risk.png/.svg` — f1 by cell
- `project/results/stage2i5/fig5_migration_count.png/.svg` — Migrations by cell
- `project/results/stage2i5/fig6_tradeoff.png/.svg` — Multi-objective trade-off (f3 vs f2, f4 vs f2)
- `project/results/stage2i5/fig7_azure_vs_alibaba.png/.svg` — Dataset comparison
- `project/results/stage2i5/fig8_relative_tradeoff.png/.svg` — f2 reduction vs f3 increase

- `project/results/stage2i5/*_source.csv` — Per-figure data tables (8 files)

### 20.6 Visualization Script

- `project/scripts/stage2i5_make_figures.py` — Figure generator

---

## 21. Current Project Status

### 21.1 COMPLETE

- Stage 2A: Python-to-CloudSim interface contract
- Stage 2B: Python threat-score ingestion and CloudSim VM mapping
- Stage 2C: Security-state / risk-model layer
- Stage 2D: Migration and co-residency duration tracking
- Stage 2E: Adaptive threat threshold
- Stage 2E.1: Real-data adaptive threshold integration
- Stage 2F: Multi-objective evaluation (f1–f4)
- Stage 2G.1: NSSAO design
- Stage 2G.2: NSSAO implementation
- Stage 2G.3: NSSAO audit
- Stage 2H: CloudSim integration
- Stage 2I.1: Experiment + baseline design
- Stage 2I.2: Experiment framework implementation
- Stage 2I.3: 240-run experiment suite execution
- Stage 2I.4: Results analysis and audit
- Stage 2I.5: Visualization (8 figures + source tables)
- Stage 2J: Results interpretation and claims audit
- Stage 2K: Master technical report (this document)

### 21.2 REMAINING for Final University Submission

- Final academic writing/polishing
- Thesis formatting
- References and citations
- Final architecture diagrams (if required by institution)
- Presentation (PPT)
- Viva/oral defense preparation

### 21.3 NOT TO BE DONE

- NO additional experiments
- NO source code modifications
- NO Python/ML pipeline changes
- NO result modifications
- NO new graphs

---

**END OF MASTER TECHNICAL REPORT**

**Report created:** 2026-09-11  
**Source code modified:** NO  
**Python/ML modified:** NO  
**Experiment results modified:** NO  
**New experiments run:** NO  
**New graphs created:** NO  
**Consistency issues found:** NONE

---

**HARD STOP.**
