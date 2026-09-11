# Stage 2H — CloudSim Integration

**Status: IMPLEMENTATION COMPLETE — compiled, validated (2×, deterministic), regressions green**

**Date: 2026-09-11**

## Overview

Stage 2H connects all components from Stages 2B–2G into a complete end-to-end CloudSim simulation workflow:

```
threat scores (CSV)
  → CloudSim VMs with threat metadata
  → deterministic initial placement
  → NSSAO optimization (hypothetical, read-only)
  → final Pareto front (multiple solutions)
  → deterministic single-solution selection
  → actual CloudSim VM migration
  → post-hoc co-residency duration measurement
  → final metrics report
```

**Critical architectural invariant:** NSSAO evaluation remains hypothetical and read-only. Actual VM migration happens ONLY ONCE after NSSAO finishes and one final candidate is selected.

---

## 1. Architecture

### 1.1 Component Integration

```
Stage 2B: ThreatScoreCsvReader, EntityVmMapping
    ↓
Stage 2C: VmThreatState (per-VM metadata)
    ↓
Stage 2D: VmMigrationService, CoResidencyDurationTracker
    ↓
Stage 2E: (adaptive threshold — classification only, NOT used as production threshold)
    ↓
Stage 2F: ObjectiveEvaluator, CandidatePlacement, ObjectiveValues
    ↓
Stage 2G: NssaoOptimizer, Pareto, PlacementDecoder
    ↓
Stage 2H: Stage2hCloudSimIntegration (THIS STAGE)
```

### 1.2 Key Classes

- **`Stage2hCloudSimIntegration`**: Main orchestrator
- **`InitialPlacementStrategy`**: Deterministic initial feasible placement
- **`ParetoSolutionSelector`**: Single-solution selection from final Pareto front
- **`SimulationMetrics`**: Final metrics collection and reporting
- **`Stage2hValidation`**: End-to-end validation harness

---

## 2. Initial Placement Strategy

**Requirement:** Deterministic, feasible initial placement for all VMs before NSSAO runs.

**Algorithm:** First-Fit Decreasing (FFD) by CPU demand

```
1. Sort VMs by decreasing MIPS demand (ties: ascending VM ID)
2. For each VM in sorted order:
   - Find first host with sufficient CPU AND RAM capacity
   - Assign VM to that host
   - Update host's available capacity
3. If any VM cannot be placed → FAIL CLEARLY with capacity report
```

**Rationale:**
- FFD is a well-known deterministic bin-packing heuristic
- CPU-first sorting aligns with CloudSim's typical bottleneck
- Guaranteed feasible if aggregate demand ≤ aggregate capacity
- No randomness → reproducible across runs
- Simple, auditable, no parameter tuning

**NOT a claim:** FFD is optimal, security-aware, or mimics production scheduling. It is only an initial feasible state for the simulation.

---

## 3. Pareto Solution Selection (Resolving I3)

**The Problem (from Stage 2G.3 audit I3):**

NSSAO returns the final Pareto front (front 1), which may contain multiple non-dominated solutions. The optimizer does NOT choose one — correctly deferred to the integration stage. A naive `getFirstFront().get(0)` would select an arbitrary (index-ordered) solution.

**Required:** ONE deterministic, defensible rule for selecting exactly one solution from the final feasible Pareto front.

### 3.1 Rejected Approaches

| Approach | Why Rejected |
|----------|-------------|
| Arbitrary weighted sum (e.g., `0.25×f1 + 0.25×f2 + 0.25×f3 + 0.25×f4`) | Not defensible — weights invented, paper has none, "balanced" is arbitrary |
| Crowding distance alone | CD measures diversity within the front, NOT solution quality. Largest CD ≠ "best" — it means "least crowded neighbor region" |
| Random selection | Non-deterministic, indefensible |
| First by index | Arbitrary, seed-dependent ordering accident |

### 3.2 Adopted Rule: Lexicographic Preference on Objectives

**Selection rule:** Among all front-1 members, choose the solution that is lexicographically minimal on `(f2, f3, f1, f4)`.

**Justification:**

1. **f2 (co-residency risk) FIRST:**
   - Security threat is the paper's PRIMARY motivation
   - f2 directly measures cross-entity co-residency risk
   - Minimizing f2 is the most security-aligned choice
   - Paper: "...minimizes security risk of cross-entity co-residency..."

2. **f3 (cost) SECOND:**
   - Among solutions with equal f2, prefer lower operational cost
   - Cost is directly observable and operationally relevant
   - Tie-break on a quantifiable economic dimension

3. **f1 (threat×server-risk) THIRD:**
   - Among solutions equal on f2 and f3, prefer lower aggregate threat exposure
   - f1 is the other security-related objective
   - Secondary security dimension after direct co-residency

4. **f4 (unused capacity) LAST:**
   - Resource efficiency as final tie-break
   - Less critical than security and cost in the paper's framing
   - Still deterministic and measurable

5. **Index (if all else equal):**
   - Ultimate deterministic tie-break
   - Ensures exactly one solution selected

**Properties:**
- **Deterministic:** Same front → same selection, always
- **Defensible:** Priority order aligns with paper's security focus
- **Transparent:** No hidden weights, no invented parameters
- **Auditable:** Lexicographic order is trivial to verify manually
- **NOT a claim:** This is "optimal" or "best" in any absolute sense. It is a *documented preference rule* that respects the paper's security emphasis and provides reproducible selection.

### 3.3 Duplicate Placement Handling

**Issue (from Stage 2G.3 audit I2):** Multiple distinct positions may decode to the same placement (due to repair or rounding), producing identical objective vectors in front 1.

**Handling:** Deduplicate front 1 by unique placement BEFORE applying the selection rule.

```
1. Extract front 1 from NSSAO result
2. Group by placement (VM→host mapping)
3. Keep first representative of each unique placement
4. Apply lexicographic selection to deduplicated set
5. Report: total front-1 size, unique placements, duplicates removed
```

**Rationale:** Selecting among truly distinct trade-offs is more meaningful than picking among decoding accidents. If the entire front collapses to ONE unique placement, report that clearly (it means all front-1 members converged to the same solution).

---

## 4. Actual Migration Application

**After selection:**

1. Selected `CandidatePlacement` specifies target VM→host mapping
2. Compare against current live CloudSim state (host guest lists)
3. For each VM whose target host ≠ current host:
   - Call `VmMigrationService.migrateVm(vm, sourceHost, destHost, simulationTime)`
   - Record migration event
4. Verify final live state matches selected placement exactly
5. Reject the run if any migration fails (capacity should be guaranteed by Stage 2F validation)

**Migration counting:** Counted during NSSAO evaluation (hypothetical, Stage 2F), verified against actual migration events (Stage 2H).

**Migration cost:** `migrationCount × perMigrationCost` (from Stage 2F f3 formula).

---

## 5. Co-Residency Duration Measurement

**Timeline:**

1. Initial placement (t=0): snapshot taken, co-residency episodes START
2. NSSAO runs (t=0 to t=t_nssao): no physical state change, no duration accumulation
3. Selected candidate applied (t=t_migrate): migrations execute, snapshot taken
4. Episodes from (1) END at t_migrate if VMs no longer co-resident
5. New episodes START at t_migrate for newly co-resident pairs
6. Simulation END (t=t_end): final snapshot, all active episodes END

**What is measured:**

- **Co-residency duration:** wall-clock simulation time VMs spend co-resident
- **High-threat co-residency duration:** subset where at least one VM is high-threat (threat ≥ adaptive threshold from Stage 2E, used ONLY as a classification mechanism)
- **Episode count:** number of distinct co-residency episodes
- **NOT measured:** real-world clock time, Azure trace timestamps (datasets lack host placement)

**Data source:** CloudSim simulation events (placement, migration), NOT inferred from dataset timestamps.

---

## 6. Final Metrics

### 6.1 Objective Values (from selected candidate)

- **f1 (threat × server risk):** Aggregate security exposure considering host utilization
- **f2 (co-residency risk):** Sum of max threats over cross-entity co-resident pairs
- **f3 (cost):** Active hosts × perHostCost + migrations × perMigrationCost
- **f4 (unused capacity):** 1 − average utilization (CPU + RAM) across all hosts

### 6.2 Placement Statistics

- **Active hosts:** Number of hosts with at least one VM
- **CPU utilization (aggregate):** Total allocated MIPS / total capacity
- **Memory utilization (aggregate):** Total allocated RAM / total capacity
- **Overall utilization:** (CPU util + RAM util) / 2
- **Migration count:** Number of VMs moved from initial to final placement
- **Migration cost:** migrationCount × perMigrationCost

### 6.3 Co-Residency Duration (Stage 2D)

- **Total co-residency duration:** Sum of all episode durations
- **High-threat co-residency duration:** Subset involving high-threat VMs
- **Co-residency episode count:** Number of distinct episodes

### 6.4 NSSAO Statistics

- **Iterations executed:** How many NSSAO iterations ran before termination
- **Stopped by stall:** Boolean — did the run hit the no-improvement stall limit?
- **Rejected decodes:** Number of infeasible candidates rejected during search (should be 0 with repair)
- **Front-1 size (raw):** How many individuals were in front 1 before deduplication
- **Unique placements in front 1:** After deduplication
- **Duplicate placements removed:** raw − unique

### 6.5 Repair Rate (Stage 2G.3 audit I1)

- **Repair count:** Number of times `PlacementDecoder` invoked capacity repair
- **Repair rate:** repairCount / totalDecodes

**Interpretation:** High repair rates (>50%) indicate the optimizer is frequently generating infeasible raw positions, and the effective search distribution is shaped by the repair heuristic rather than NSSAO positions alone. This is a documented limitation (Stage 2G.3 audit I1), not a bug.

---

## 7. Validation Scenario

**Goal:** Prove end-to-end simulation completes and all integration contracts hold.

### 7.1 Scenario Setup

```
Hosts (heterogeneous):
  H0: 2000 MIPS, 4096 RAM
  H1: 1000 MIPS, 2048 RAM
  H2: 1000 MIPS, 2048 RAM

VMs (all 500 MIPS, 512 RAM):
  A (id=10): threat=0.9, entity e1
  B (id=11): threat=0.8, entity e2
  C (id=12): threat=0.2, entity e3
  D (id=13): threat=0.2, entity e3 (SAME entity as C)

NSSAO config: N=12, t_max=6, seed=42 (deterministic, small scale for validation)
ServerRiskModel: base=0.1, utilWeight=0.3
Costs: perHost=1.0, perMigration=1.0
```

### 7.2 Validation Tests

**T1: End-to-end simulation completes without exceptions**

**T2: Initial placement is feasible**
- All VMs assigned to hosts
- No capacity violations
- Deterministic (same input → same placement)

**T3: NSSAO produces non-empty front 1**

**T4: Selected candidate is feasible**
- Stage 2F validation passes
- No capacity violations

**T5: Selected candidate belongs to final Pareto front**
- Selected individual is in `getFirstFront()`
- Not dominated by any other front-1 member

**T6: Exactly one candidate selected**
- Selection rule returns exactly one `CandidatePlacement`
- Deterministic (same front → same selection)

**T7: Actual final placement matches selected candidate**
- For every VM: live host ID == selected candidate's host ID
- Verified by reading CloudSim guest lists after migration

**T8: Migration count matches**
- Hypothetical count (Stage 2F evaluation of selected candidate) == actual migration events recorded
- Migration cost = count × perMigrationCost

**T9: Co-residency duration measured from actual events**
- Duration > 0 for co-resident pairs
- Duration based on simulation time, not dataset timestamps
- Episodes start/end correctly around migration events

**T10: NSSAO does not mutate live CloudSim state**
- Fingerprint before NSSAO == fingerprint after NSSAO
- Only the explicit migration step changes state

**T11: Deterministic under seed**
- Same seed → same initial placement → same NSSAO trajectory → same selected candidate → same final state → same metrics

**T12: Stage 2B–2G regressions pass**
- Run existing validation harnesses
- No component broken by integration

**T13: CloudSimExample1 and Example2 still pass**
- Core CloudSim functionality unchanged

**T14: Run end-to-end twice in same process**
- Both runs complete
- Metrics identical (determinism)

---

## 8. Limitations and Adaptations

### 8.1 Pareto Selection Rule

**Adaptation:** Lexicographic preference on `(f2, f3, f1, f4)` with index tie-break.

**Rationale:** Paper provides no single-solution selection rule. This order respects the paper's security focus (f2 first, f1 third) while remaining deterministic and parameter-free.

**NOT claimed:** This is optimal, proven best, or the only defensible rule. It is ONE transparent rule documented for reproducibility.

### 8.2 Initial Placement

**Adaptation:** First-Fit Decreasing by CPU demand.

**Rationale:** Deterministic, feasible, well-known heuristic. Not security-aware, not optimal, not mimicking production — just a deterministic starting state.

### 8.3 Repair Rate

**Consequence:** Per Stage 2G.3 audit I1, repair shapes the effective search distribution. Validation reports repair rate. High rates (>50%) indicate optimizer positions are heavily corrected by the feasibility heuristic.

**NOT claimed:** This is ideal or that repair-free search is achievable without penalties.

### 8.4 Small-Scale Validation

**Scope:** Validation uses N=12, t_max=6, 4 VMs, 3 hosts — deliberately small for deterministic auditing.

**NOT claimed:** Convergence, optimality, or that behavior scales. Large-scale experiments are deferred to a future stage.

### 8.5 Co-Residency Duration

**Measurement:** From CloudSim simulation events (placement snapshots), NOT from Azure/Alibaba trace timestamps (datasets lack host placement).

**NOT claimed:** Real-world duration, actual Azure behavior, or that simulated duration matches any production system.

### 8.6 Adaptive Threshold

**Role:** Used ONLY as a classification mechanism (high-threat vs normal) for co-residency reporting.

**NOT used as:** A production security threshold, a migration trigger, or an objective function parameter.

**Default:** Stage 2E dataset-specific threshold (Alibaba/Azure). No 0.5 or 0.6 fabricated.

---

## 9. Paper vs Project Adaptations Summary

| Paper Component | Project Implementation | Status |
|----------------|----------------------|--------|
| NSSAO Algorithm 1 | Stage 2G (NssaoOptimizer) | FAITHFUL (Stage 2G.3 audit PASS) |
| Four objectives (f1–f4) | Stage 2F (ObjectiveEvaluator) | FAITHFUL (Stage 2F validation PASS) |
| Non-dominated sorting | Stage 2G (Pareto) | FAITHFUL (hand-computed examples pass) |
| Discretization | Stage 2G (PlacementDecoder) | ADAPTATION (documented Stage 2G.1) |
| Repair-first feasibility | Stage 2G (PlacementDecoder) | ADAPTATION (documented Stage 2G.1) |
| Melting factor schedule | Stage 2G (NssaoConfig) | ADAPTATION (paper formula unrecoverable) |
| Initial placement | Stage 2H (InitialPlacementStrategy) | NOT IN PAPER (validation requirement) |
| Single-solution selection | Stage 2H (ParetoSolutionSelector) | NOT IN PAPER (integration requirement) |
| Co-residency duration | Stage 2H + Stage 2D | NOT IN PAPER (simulation measurement) |

**Correctness claims:**
- Implementation matches approved Stage 2G.1 design
- All validation tests pass
- Deterministic under seed
- Zero live-state mutation during optimization

**NOT claimed:**
- Exact paper reproduction (adaptations documented)
- Convergence or optimality
- Security improvement (no baseline comparison)
- Real-world applicability

---

## 10. Experiment Readiness

**Stage 2H deliverables:**
- End-to-end simulation pipeline
- Deterministic initial placement
- Pareto solution selection (I3 resolved)
- Actual migration application
- Full metrics collection
- Validation harness (14 tests)

**NOT delivered (explicitly deferred):**
- Baselines (random, greedy, etc.)
- Large-scale experiments (N=2000, paper parameters)
- Convergence analysis
- Graph generation
- Statistical comparison

**Ready for experiments?** Yes — the pipeline is complete and validated. Experiments can now vary scale, seeds, workloads, and NSSAO parameters.

---

## 11. Thesis Claim Hygiene

**Defensible claims for Stage 2H:**
- Complete end-to-end CloudSim integration with documented components
- Deterministic selection from Pareto front via transparent lexicographic rule
- Zero live-state mutation during optimization (validated)
- Co-residency duration measurement from simulation events
- Reproducible under seed (validation T11, T14)

**NOT defensible until experiments exist:**
- NSSAO improves security vs baselines
- Solutions converge or are optimal
- Results generalize to real-world systems
- Repair rate is acceptable at scale
- Front-1 deduplication is sufficient

---

## 12. Stage 2H Completion Report

**Date: 2026-09-11. Stage 2H continued from the existing (uncompilable) implementation; nothing restarted, nothing redesigned.**

### 12.1 State Found on Arrival

All five Stage 2H files existed (`Stage2hCloudSimIntegration`, `ParetoSolutionSelector`,
`InitialPlacementStrategy`, `SimulationMetrics`, `Stage2hValidation`). The architecture
matched the approved design and was preserved. The module **did not compile**: 16
distinct compile errors, ALL inside `stage2h` (Stages 2B–2G compiled clean).

### 12.2 What Was Fixed (compilation/integration only)

| # | Problem | Fix |
|---|---------|-----|
| 1 | `VmMigrationService.MigrationEvent` referenced as nested class; it is top-level `org.cloudbus.cloudsim.examples.stage2d.MigrationEvent` | Imports + signatures corrected (5 sites) |
| 2 | `CandidatePlacement.getVmToHost()` does not exist | `viewMapping()` used (3 sites) |
| 3 | `Vm#getMips()` returns `double` in this CloudSim version; stage2h treated it as `int` (lambda comparator, capacity arithmetic) | `comparingDouble` sort; capacities summed in `double`, ceil for per-VM demand in FFD |
| 4 | `CoResidencyDurationTracker` has no `computeEpisodes()`; episodes are rebuilt on every `recordSnapshot` | `getEpisodes()`, `getTotalCoResidencyDuration()`, `getTotalHighThreatCoResidencyDuration()` used |
| 5 | Dead `liveCoResidencySnapshot()` helper built `CrossEntityCoResidencyPair`/`CoResidencySnapshot` whose constructors are package-private (by design — Stage 2D owns pair detection) and would have double-counted pairs | Helper deleted; tracker snapshots live state itself |
| 6 | Initial placement applied VMs via `migrateVm(...)` from a non-existent source host (would throw: "VM not on source host"), wrongly counting initial placement as migrations | Dedicated `applyInitialPlacement()` direct guest-list insertion; produces **no** MigrationEvent and no migration cost. Only post-NSSAO moves of the selected candidate are migrations |
| 7 | Constructor recorded a co-residency snapshot before any VM was placed (empty, wrong episode baseline) | First snapshot moved to the initial-placement event (t=0); second at the migration event (t=1) |
| 8 | `verifyCloudSimPurity` computed an unused `liveFingerprint()` | Removed |
| 9 | Validation T5/T8/T9 were placeholders (`true`); T11/T14 not strengthened | `Stage2hValidation` rewritten: T8 verifies count/cost **from actual migration events** vs an independent expectation (selected mapping vs initial mapping); T9 verifies episode durations equal the event timestamps span (episodes × t_migrate); T5 verifies selected individual is in `getFirstFront()`; T11a/T11b compare selected placement and migration events across runs; `metricsIdentical` extended to cost/duration/NSSAO stats |

No changes were made to any Stage 2B–2G file, the Python pipeline, NSSAO, or f1–f4.

### 12.3 Final-Solution Selection Rule (explicit, deterministic, documented)

Already implemented in `ParetoSolutionSelector` and preserved verbatim:

> Among all front-1 members (deduplicated by unique VM→host placement), select the
> solution **lexicographically minimal on (f2, f3, f1, f4)**, final tie-break: lower
> index in the raw front.

Priority order and justification (security-first: f2 co-residency risk primary;
f3 cost; f1 aggregate threat exposure; f4 efficiency) are documented in §3 above.
No weighted sum is used anywhere.

### 12.4 Validation Results (Stage2hValidation run twice, separate processes)

Scenario: 3 hosts (2000/1000/1000 MIPS), 4 VMs (500 MIPS, 512 RAM each, entities
e1/e2/e3/e3), NSSAO N=12, t_max=6, seed=42, costs 1.0/1.0.

- Run 1 (process 1): **exit 0, 32/32 checks passed**
- Run 2 (process 2): **exit 0, 32/32 checks passed**
- `diff run1.log run2.log` → **byte-identical** (cross-process determinism)

Observed pipeline: initial FFD placement (4 VMs) → snapshot t=0 → NSSAO
6 iterations, front-1 = 12 raw members, 7 unique placements, 5 duplicates,
0 rejected decodes → lexicographic selection → **2 actual migrations** →
snapshot t=1 → metrics.

Verified per constraint:

| Requirement | Evidence |
|---|---|
| Selected solution feasible | T4 (orchestrator capacity check before migration) PASS |
| Selected ∈ final Pareto front | T5 PASS |
| Actual final VM→host placement == selected solution | T7 PASS (live guest lists vs selected mapping) |
| Migration count correct | T8a PASS (2 events == 2 expected moves) |
| Migration cost correct | T8b PASS (2 × 1.0) |
| Co-residency duration from actual simulation/migration events | T9 PASS (5 episodes × [t=0, t=1] = 5.0; snapshots taken ONLY at placement and migration events) |
| NSSAO does not mutate live CloudSim state | T10 PASS (purity check between placement, NSSAO, migration) |
| Determinism (same seed) | T11/T11a/T11b/T14 PASS + byte-identical process outputs |

### 12.5 Regression Results (all exit 0)

| Harness | Result |
|---|---|
| Stage2bValidation | PASSED |
| Stage2cSecurityStateValidation | PASSED |
| Stage2dMigrationValidation | PASSED |
| Stage2eAdaptiveThresholdValidation | PASSED |
| Stage2eRealDataThresholdValidation (2E.1) | PASSED |
| Stage2fObjectiveValidation | PASSED |
| Stage2gNssaoValidation (2G.2) | PASSED |
| CloudSimExample1 | finished, SUCCESS, no exceptions |
| CloudSimExample2 | finished, SUCCESS, no exceptions |

### 12.6 Constraint Compliance

- Stage 2F/2G implementations: **untouched** (all compile errors were in stage2h only)
- Python pipeline: untouched
- NSSAO: not redesigned; optimizer consumed read-only via its existing API
- f1–f4: unchanged (evaluated via Stage 2F `ObjectiveEvaluator` as-is)
- No fixed 0.5/0.6 threat classification introduced: the 0.5 constant in
  `Stage2hValidation` is the pre-existing Stage 2C/2F **testing-only scenario
  convention** (identical to `Stage2fObjectiveValidation`); it is a scenario
  construction parameter, NOT a production threshold, NOT baked into any
  algorithm (same as 2F/2G validation)
- NSSAO candidate evaluation is hypothetical/read-only: no VM is physically
  migrated during evaluation; physical migration happens exactly once, after
  selection

### 12.7 Known Limitations (unchanged from design)

1. **Repair rate placeholder:** `SimulationMetrics.repairCount/repairRate` report 0;
   per-decode repair statistics require `PlacementDecoder`/`NssaoOptimizer`
   instrumentation, which was out of scope (would mean modifying Stage 2G).
2. **Single migration event time:** the selected candidate is applied at simulation
   time t=1.0 as a discrete event; co-residency episodes therefore span [0, 1].
   Time-varying workloads/cloudlet execution are not modeled in this scenario.
3. **Small-scale validation only:** 4 VMs / 3 hosts / N=12 / t_max=6, chosen for
   deterministic auditability. No claims about scale or convergence.
4. **Movable set = all VMs:** Stage 2H marks every VM movable; pinning policies
   are a future concern.
5. **Migration duration is instantaneous** (Stage 2D `MigrationEvent.instantaneous`),
   consistent with the Stage 2D design; pre-copy duration is not modeled.

---

**HARD STOP AFTER STAGE 2H. Baselines, experiments, graphs, and Python integration NOT implemented.**
