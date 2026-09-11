# Stage 2F — Objective Evaluation

**Status: COMPLETE**

**Date: 2026-09-11**

## Purpose

Implement ONLY the objective-evaluation layer: given a candidate VM→host
mapping, compute four raw objective values (f1..f4, all minimized) WITHOUT
mutating live CloudSim state, plus a strict min-max normalizer kept separate
from raw values.

This is NOT NSSAO, NOT optimization, NOT non-dominated sorting, NOT Sparrow
Search, NOT mutation/crossover, NOT new migration logic. Stage 2F only
*scores* placements that are handed to it; nothing in this stage generates,
ranks, or applies candidates. HARD STOP after this stage.

## New Classes (`org.cloudbus.cloudsim.examples.stage2f`)

| Class | Role |
|---|---|
| `CandidatePlacement` | Immutable VM ID → host ID proposal plus host lookup. Pure data; no CloudSim mutation. |
| `ObjectiveValues` | Immutable raw f1..f4 carrier. No normalization inside. |
| `ObjectiveEvaluator` | Validates a candidate and computes f1..f4 from it (read-only). |
| `ObjectiveNormalizer` | Static min-max normalization with the 0.5 equal-range rule. |
| `Stage2fObjectiveValidation` | Validation harness for the 11 required checks. |

## Objective Formulas (ALL MINIMIZE)

### f1 — threat-weighted server risk

```
f1 = mean over VMs of [ threat(vm) × serverRisk(candidateHost(vm)) ]

serverRisk(host) = clamp(baseHostRisk + utilizationRiskWeight × candidateOverallUtil(host), 0, 1)
candidateOverallUtil(host) = (cpuUtil + memUtil) / 2      ← computed FROM THE CANDIDATE mapping
cpuUtil = clamp(Σ vm.totalMips / host.totalMips, 0, 1)
memUtil = clamp(Σ vm.ram / host.ram, 0, 1)
```

- `baseHostRisk`, `utilizationRiskWeight` come from the existing Stage 2C
  `ServerRiskModel` (defaults 0.1 / 0.3). The model is reused; Stage 2F does
  not redefine risk.
- Crucially, `serverRisk` uses the candidate host's utilization *under the
  candidate mapping*, NOT the live host state. Moving a VM onto a host raises
  that host's candidate utilization and therefore its risk.

### f2 — cross-entity co-residency risk

```
f2 = Σ over all cross-entity VM pairs sharing the same candidate host of
     max(threatA, threatB)
```

- A pair is cross-entity when the two VMs differ in (dataset, entityType,
  entityId) — decided by the existing Stage 2C `VmThreatState.isDifferentEntityFrom`.
- Same-entity pairs contribute NOTHING (verified in validation).
- This deliberately differs from Stage 2C's `CoResidencyRiskCalculator`
  (`max × min`); the Stage 2F contract specifies `max` only. See
  "Paper-vs-project distinctions".

### f3 — active-host + migration cost

```
f3 = activeHosts × perHostCost + migrationCount × perMigrationCost
activeHosts   = candidate hosts with ≥ 1 assigned VM (empty hosts are free)
migrationCount = number of VMs whose candidate host ≠ current host
```

- `perHostCost = 1.0`, `perMigrationCost = 1.0` — simulation parameters,
  consistent with Stage 2C `DefensiveCostModel` and Stage 2D
  `MigrationCostModel` defaults.
- The current host of each VM is determined by scanning host guest lists
  (read-only), with `Vm.getHost()` as fallback. A VM not currently placed
  anywhere counts as migrating.

### f4 — unused capacity

```
f4 = 1 − ((avgCPUUtil + avgMemUtil) / 2)

avgCPUUtil = mean of per-host cpuUtil  across ALL hosts (including empty)
avgMemUtil = mean of per-host memUtil  across ALL hosts (including empty)
```

- Utilization MUST come from the candidate VM→host mapping (it does).
- Averages include empty hosts (0% utilization), so consolidating VMs onto
  fewer hosts lowers f4. This is intentional: minimizing f4 rewards packing.
- Each host utilization is clamped to [0,1] before averaging.

## Validation Rules (candidate rejection)

A candidate is rejected — no objectives computed, `getObjectives()` returns
null — if ANY of:

1. **Missing VM assignment** — a VM in the placement list has no host entry.
2. **Duplicate VM assignment** — the same VM ID appears more than once in the
   placement list.
3. **Invalid host** — a VM is assigned to a host ID that does not exist in
   the supplied host list.
4. **CPU capacity exceeded** — candidate Σ vm.totalMips on a host exceeds
   that host's total MIPS.
5. **RAM capacity exceeded** — candidate Σ vm.ram on a host exceeds that
   host's RAM.

Violations are reported with human-readable reasons via
`EvaluationResult.getViolations()`.

## Normalization (strictly separate from raw values)

```
normalized = (value − min) / (max − min)     when max > min
normalized = 0.5                              when max == min
```

- min/max are per-objective ranges computed over a candidate set.
- The `max == min` case returns **0.5**: when every candidate scores the same
  on an objective, that objective is uninformative and should neither reward
  nor penalize anyone. 0.5 is the neutral midpoint.
- Values outside [min, max] are clamped to [0,1] (defensive; a late candidate
  marginally outside a stale range must not produce out-of-scale scores).
- Raw values live ONLY in `ObjectiveValues`; normalized values are produced
  ONLY by `ObjectiveNormalizer`. The two are never mixed.

## Determinism and Purity

- The evaluator reads only static descriptors and guest lists. It never
  calls allocation policies, never adds/removes guests, never touches the
  CloudSim clock or event queue.
- All accumulation loops iterate in deterministic (insertion/sorted) order;
  floating-point summation order is fixed, so repeated evaluations of the
  same candidate are bit-identical.
- Validation check T8 fingerprints live host→VM assignments before/after
  evaluating multiple candidates and requires equality.
- Check T9 verifies A→B→A evaluation (evaluate candidate X, then Y, then X
  again) returns identical values for both X evaluations.

## Validation Results

Scenario (hand-computed expected values; all matched exactly):
3 hosts — H0 (2000 MIPS / 4096 RAM), H1 (1000/2048), H2 (1000/2048);
VMs A(t=0.9,e1)@H0, B(t=0.8,e2)@H0, C(t=0.2,e3)@H1, D(t=0.2,e3)@H1.

| Candidate | f1 | f2 | f3 | f4 |
|---|---|---|---|---|
| X: A,B→H0; C,D→H1 | 0.1228125 | 0.9 | 2.0 (2 hosts, 0 migrations) | 0.625 |
| Y: A→H0; B→H1; C,D→H2 | 0.11015625 | 0.0 | 6.0 (3 hosts, 3 migrations) | 0.5625 |
| ALL0: all→H0 | 0.170625 | 4.3 | 3.0 (1 host, 2 migrations) | 0.75 |
| V: A,C→H0; B,D→H1 | 0.1396875 | 1.7 | 4.0 | 0.625 |

Notes on the checks:
- T1: f1 differs X vs Y because server risk tracks candidate utilization.
- T3: Y has C+D co-resident (same entity) yet f2=0; V sums two cross-entity
  pairs: max(0.9,0.2)+max(0.8,0.2)=1.7.
- T6: f4 is placement-sensitive because host capacities are heterogeneous;
  with uniform hosts, mean utilization would be invariant under placement and
  only consolidation onto fewer hosts would change f4.
- T7: all five rejection rules fire, and rejected candidates expose no
  objective values.
- T8/T9: purity and determinism as described above.

**Result: 11/11 checks passed. Run twice (two consecutive executions), both
passed.**

Regression runs after Stage 2F (all PASSED, no behavior change):

| Run | Outcome |
|---|---|
| Stage2bValidation | PASSED (248 records, 12 VMs, 0 mismatches) |
| Stage2cSecurityStateValidation | PASSED |
| Stage2dMigrationValidation | PASSED |
| Stage2eAdaptiveThresholdValidation | PASSED |
| Stage2eRealDataThresholdValidation | PASSED |
| CloudSimExample1 | SUCCESS (cloudlet 0) |
| CloudSimExample2 | SUCCESS (cloudlets 0, 1) |

## Assumptions

1. One workload entity → one simulated VM (Stage 2B policy, unchanged).
2. `perHostCost` and `perMigrationCost` are flat constants (simulation
   parameters), matching Stage 2C/2D defaults.
3. Host CPU capacity = `Host.getTotalMips()`; VM demand = `Vm.getTotalMips()`.
   RAM analogously via `getRam()`. No over-subscription is allowed in a valid
   candidate.
4. "Active host" = candidate host with at least one assigned VM. Failed hosts
   are not modeled in this stage's scenario.
5. A VM with no current placement (not in any guest list, no live host
   reference) counts as migrating when assigned.
6. Threat scores are consumed exactly as produced by the Python pipeline
   (Stage 1C/1D) and preserved by Stage 2B; they are NOT recomputed in Java.

## Limitations

- **No search/optimization.** The evaluator scores only the candidate it is
  given. Nothing selects, generates, mutates, or ranks candidates.
- **Flat cost model.** Real migration cost (downtime, network transfer,
  dirty-page copying, energy) is not modeled — consistent with Stage 2D.
- **No VM demand dynamics.** Demand is the VM's static descriptor, not
  time-varying cloudlet utilization.
- **f2 is unbounded.** Summing max-threat over pairs grows O(n²) on a host;
  raw magnitudes are only comparable after normalization.
- **Equal-range normalization hides scale.** 0.5 masks the fact that all
  candidates are (degenerate-)equal on an objective; diagnostics should also
  report raw ranges.
- **Single-datacenter scope.** The candidate covers one host list; no
  cross-datacenter placement.
- **Co-residency duration is NOT an objective.** It remains post-hoc
  (Stage 2D tracker), per the Stage 2F contract.

## Paper-vs-Project Distinctions

| Aspect | Paper | This project |
|---|---|---|
| Co-residency pair weight | threat interaction terms (as specified) | `max(threatA, threatB)` per Stage 2F contract; differs from Stage 2C's `max×min` diagnostic |
| Server risk | may include real vulnerability/posture data | `base + weight×utilization` clamp — simulation abstraction (no scanner data) |
| Threshold | fixed 0.6 | adaptive percentile (Stage 2E) — NOT used as an objective here |
| Migration cost | measured/proportional to VM size | flat per-migration constant (Stage 2D abstraction) |
| Objective synthesis | (paper's multi-objective method) | NONE — Stage 2F stops at raw + normalized values; no aggregation, no dominance |
| Datasets | paper's private traces | Azure V2 + Alibaba V2018 public VM traces (Python pipeline) |
| Placement truth | real cloud telemetry | CloudSim simulated placement only |

## File Inventory (new)

```
cloudsim/modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/stage2f/
├── CandidatePlacement.java
├── ObjectiveValues.java
├── ObjectiveEvaluator.java
├── ObjectiveNormalizer.java
└── Stage2fObjectiveValidation.java
```

No existing Stage 2B–2E class was modified. No Python pipeline file was
modified.

## Build / Run

```
cd cloudsim
../apache-maven-3.9.16/bin/mvn -q -o compile -pl modules/cloudsim-examples -am

java -cp "modules/cloudsim/target/classes;modules/cloudsim-examples/target/classes" \
  org.cloudbus.cloudsim.examples.stage2f.Stage2fObjectiveValidation
```

(Use `:` instead of `;` in the classpath on Linux/macOS.)

---

**HARD STOP. Objective evaluation only. NSSAO, optimization, non-dominated
sorting, Sparrow Search, and mutation/crossover are NOT implemented.**
