# Stage 2G.2 — NSSAO Core Implementation

**Status: COMPLETE — optimizer core only**

**Date: 2026-09-11**

Implements exactly the approved Stage 2G.1 design
(`stage_2g1_nssao_design.md`). No migration, no migration scheduling, no
final CloudSim integration, no experiment framework, no baselines, no
graphs, no adaptive-threshold changes, no Python changes, no Stage 2F
formula changes.

**NSSAO generates hypothetical placements. Actual VM migration is NOT
performed in Stage 2G.2.**

---

## Classes Created (`org.cloudbus.cloudsim.examples.stage2g`)

| Class | Role |
|---|---|
| `NssaoConfig` | All optimizer parameters exposed via builder; Stage 2G.1 defaults; melting anneal and sub-population schedule helpers. |
| `Pareto` | Minimization dominance on raw values, fast non-dominated sorting, crowding distance (Stage 2F `ObjectiveNormalizer`, 0.5 equal-range rule), environmental selection, front-1 vector-set comparison for stall detection. |
| `PlacementDecoder` | Approved discretization `clamp(rint(x_k), 0, H−1)`; builds hypothetical `CandidatePlacement`s; deterministic repair-first feasibility; rejection with reason. |
| `NssaoOptimizer` | Paper Eq. (21)–(24) dual-population updates; single seeded RNG; environmental selection each iteration; stall termination. |
| `Stage2gNssaoValidation` | 17-check validation harness on a small deterministic scenario. |

Stage 2F classes are consumed as-is: `CandidatePlacement`,
`ObjectiveEvaluator`, `ObjectiveValues`, `ObjectiveNormalizer`. Objective
formulas are NOT duplicated anywhere in stage2g — the optimizer only ever
calls `ObjectiveEvaluator.evaluate(...)` (verified by check T7 comparing
stored objectives to a fresh evaluator run).

## NSSAO Flow (per iteration)

```
init:  P[0] = current placement (documented seed)
       P[1..N-1] = uniform random positions in [0, H-1]
       evaluate all (decode -> ObjectiveEvaluator -> raw f1..f4)
       F = NDS(values)
loop t = 0 .. t_max-1:
  |P_b| = round(N/2 · (1 - t/t_max));  |P_a| = N - |P_b|
  X_best = lexicographic best of front 1 (larger CD, then lower f1, then lower index)
  X̄      = component-wise centroid of positions
  M      = M_max - (M_max - M_min)·t/t_max
  for each individual i (fixed order):
    rho = U[-1,1]  if i ∈ P_b (exploitation, ρ2)
        = U[0,1]   if i ∈ P_a (exploration, ρ1)
    dir = rho·(X_best - x_i) + (1-rho)·(X̄ - x_i)          Eq. (21)/(23)
    if i ∈ P_b:  child = M·X_best + A·dir                  Eq. (24)
    else:        child = X_best + A·dir                    Eq. (22)
    A ~ N(0,1) per component; clamp child to [0, H-1]
  evaluate offspring (decode -> evaluator)
  survivors = environmentalSelect(parents ∪ offspring, N)   (NDS + CD)
  F = NDS(survivors)
  stall++ if front-1 objective-vector set unchanged else stall = 0
  stop when stall >= stallLimit (documented: 3) or t = t_max
output: fronts + population (positions, placements, raw objectives)
```

## Position Representation

- One continuous position per **movable** VM; vector length = M (dimension).
- Bounds `[0, H−1]` (paper-faithful form `[0, P−1]`, P→H), clamped after
  every update and defensively at decode.
- Pinned VMs are not decision variables; their current host IDs come from
  the read-only current mapping.
- `P[0]` seeds the current placement so the optimizer always contains the
  status quo (its objectives are the reference for rejected decodes too).

## Continuous → Discrete Mapping

```
hostIndex = clamp( round(x_k), 0, H−1 )      # Math.rint (banker's rounding)
```

Exactly the approved Stage 2G.1 Option A. Multiple VMs mapping to one host
is legitimate co-residency and occurs freely (check T4 exercises an
all-on-H0 decode). Missing assignments, duplicate assignments, and
out-of-range hosts are structurally impossible: one position per VM, one
host index per position, full host list indexed inside `CandidatePlacement`.

## Repair Strategy (approved: repair-first, deterministic)

After decode, any host whose candidate CPU or RAM demand exceeds capacity is
relieved deterministically:

1. Hosts processed in ascending ID; on each overloaded host, the movable VM
   with the **smallest CPU demand** (ties: lower VM ID) is relocated.
2. Destination = feasible host (excluding the overloaded one) minimizing
   resulting CPU utilization; exact ties keep the lower host ID.
3. Only overloaded hosts are touched — maximum preservation of the proposed
   assignment (check T6: 4×500 MIPS proposed onto a 1000-MIPS host; repair
   leaves a feasible subset on the proposed host and relocates the rest).
4. If no feasible destination exists for a needed move, the candidate is
   **rejected** (`DecodeResult` without candidate), counted in
   `rejectedDecodeCount`, and its population slot falls back to the current
   placement's objectives — never a silent accept, never a penalty.
5. Post-repair, Stage 2F `ObjectiveEvaluator.validate()` remains the final
   authority; any residual violation is treated as a rejection (must not
   occur; counted loudly).

## Pareto Sorting

- **Dominance (raw values, all minimize):** a dominates b iff
  ∀k f_k(a) ≤ f_k(b) and ∃k f_k(a) < f_k(b). Equal vectors do not dominate.
- **Non-dominated sorting:** classic fast NDS (dominator counts + dominated
  lists). Bug found and fixed during validation: the i-dominates-j branch
  originally missed `dominationCount[j]++`, collapsing all members into
  front 1; the harness (T10's 3-front hand-computed example) exposed it.
- **Crowding distance:** per objective, members sorted by within-front
  min-max-normalized value (Stage 2F `ObjectiveNormalizer`; equal-range →
  0.5 neutral); boundary members get ∞; interior accumulate normalized
  neighbor gaps. Deterministic tie order.
- **Environmental selection:** parents + offspring (2N) → front-by-front
  fill to N; overflow front split by descending CD, ties by lower index.
- **No weighted sum anywhere.** Check T8 proves the machinery keeps
  incomparable individuals together (a wins f1, b wins f3 → both in
  front 1), which any scalarized fitness would violate.

## Parameters (all in `NssaoConfig`)

| Parameter | Default | Source |
|---|---|---|
| populationSize | 40 | Stage 2G.1 adaptation of scale (paper 2000) |
| maxIterations | 10 | PAPER-FAITHFUL (t_max = 10) |
| seed | 42 | determinism requirement |
| stallLimit | 3 | Stage 2G.1 adaptation (paper unspecified) |
| meltingFactorMax / Min | 1.0 / 0.1 | Stage 2G.1 adaptation (degree-day formula not recoverable) |
| sub-population schedule | \|P_b\|(t) = round(N/2·(1−t/t_max)), \|P_a\| = N − \|P_b\| | split PAPER-FAITHFUL; schedule documented adaptation |
| dimension / hostCount | from scenario (M movable VMs, H hosts) | paper: dimension = migrated VMs; bounds [0, P−1] |

Validation used N=12, t_max=6, seed=42 for a small fast harness; defaults
apply for real runs.

## Random Seed / Determinism

One `java.util.Random` created from `config.getSeed()` drives every draw
(initial positions, per-individual ρ, per-component Gaussian A) in a fixed
order: sub-population membership is decided before the ρ draw, so the RNG
stream is branch-independent. Checks T13/T14: same seed ⇒ byte-identical
populations, objectives, fronts, iteration count; seed 7 produced a
different trajectory. Combined with deterministic evaluation (Stage 2F) and
deterministic decode/repair, the whole pipeline is reproducible.

## Live-State Safety

The optimizer never calls `VmMigrationService`, never adds/removes VMs from
host guest lists, and never touches allocation policies or the CloudSim
clock. Hosts are read for capacities and current guest lists only.
Check T15 fingerprints all host→VM guest lists before/after a full run and
requires equality. All candidates are hypothetical `CandidatePlacement`
objects; evaluation results carry no simulation side effects.

## Validation Results

Scenario: H0 (2000 MIPS/4096 RAM), H1 (1000/2048), H2 (1000/2048);
A(0.9,e1)@H0, B(0.8,e2)@H0, C(0.2,e3)@H1, D(0.2,e3)@H1; all four movable;
N=12, t_max=6, seed=42. Run 1: 6 iterations, no stall stop, 0 rejected
decodes, front 1 size 10.

**17/17 checks PASSED; run twice (separate JVM invocations, same seed), both
passed.** Coverage: T1 population size · T2 one position per VM · T3 bounds
· T4 legitimate co-residency · T5 structural validity + evaluator
acceptance · T6 repair correctness · T7 evaluator-only objectives ·
T8 no weighted sum · T9 minimization dominance (incl. equal vectors and
incomparables) · T10 NDS partition/ranks · T11 CD determinism · T12 stable
population · T13 same-seed identity · T14 different-seed divergence ·
T15 no live-state mutation · T16 front-1 feasibility · T17 within t_max.

Regression suite after Stage 2G.2 (all PASSED, no behavior change):

| Run | Outcome |
|---|---|
| Stage2bValidation | PASSED |
| Stage2cSecurityStateValidation | PASSED |
| Stage2dMigrationValidation | PASSED |
| Stage2eAdaptiveThresholdValidation | PASSED |
| Stage2eRealDataThresholdValidation (2E.1) | PASSED |
| Stage2fObjectiveValidation | PASSED |
| CloudSimExample1 / CloudSimExample2 | finished successfully |

`git status` confirms zero modified files in the cloudsim repo — Stage 2F
and all prior stages are byte-identical.

## Paper-Faithful vs Project-Adapted

**Paper-faithful:** real-valued per-VM positions bounded `[0, P−1]`; Eq.
(21)–(24) update equations with Gaussian A(t), ρ1 ∈ [0,1], ρ2 ∈ [−1,1];
even dual-population split; NDS + crowding-distance selection
(Select(NDS, CD)); termination by t_max or no-improvement stall;
dimension = number of movable VMs.

**Project adaptations (each documented in Stage 2G.1):** `rint+clamp`
discretization; repair-first feasibility; M(t) linear anneal 1.0→0.1;
stallLimit = 3; |P_b| linear decay schedule; lexicographic X_best/X(t)
choice (paper leaves "best" undefined for multi-objective); current-
placement seed individual; N default 40 (paper 2000) for validation-scale
scenarios; objectives = approved f1–f4 via Stage 2F (paper's f_Φ duration
objective deliberately excluded — post-hoc per contract).

## Limitations

- **No migration execution** — by design; a later stage must apply a chosen
  solution through Stage 2D machinery after explicit acceptance.
- **Repair genotype drift** — repaired individuals' positions no longer
  correspond exactly to their decoded placements; acceptable because
  positions re-randomize each iteration.
- **Crowding in 4-D** — CD reliability degrades with objective count
  (known NSGA-II limitation); front 1 was large (10/12) in the toy
  scenario, so CD rarely discriminates at this scale.
- **Rounding plateaus** — distinct continuous vectors can decode to the
  same placement; benign but limits effective search resolution for small
  H.
- **Population-size regime untested** — defaults are design-derived, not
  empirically tuned; parameter sweeps belong to a later experiment stage
  (explicitly out of scope here).
- **Rejected-decode fallback** — a rejected slot adopts the current
  placement's objectives; with the documented repair path this never
  triggered in validation (rejectedDecodeCount = 0), but on pathological
  scenarios it could bias the population toward the status quo.

## Explicit Non-Goals (per contract)

Actual migration · migration scheduling · final CloudSim integration ·
experiment framework · baselines · graphs · adaptive-threshold changes ·
Python changes · Stage 2F formula changes. **None implemented.**

---

**HARD STOP — STAGE 2G.2 COMPLETE.** Do not continue to Stage 2H.
