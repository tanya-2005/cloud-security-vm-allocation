# Stage 2I.1 — Experiment + Baseline Design (Checkpoint Only)

**Status: DESIGN ONLY — no code written, no experiments run, no graphs, no final results.**

**Date: 2026-09-11**

**Scope:** Design the experiment framework that Stage 2I.2 will implement. Everything
here is grounded in the completed, validated pipeline (Stage 2E.1, 2F, 2G, 2H) and the
existing dataset artifacts. Nothing new is invented; where the paper is silent, the
item is explicitly marked **project-defined**.

**Implementation invariant:** the Stage 2B–2H implementation is COMPLETE and frozen
for this stage. NSSAO, f1–f4, and the Python pipeline are NOT modified. Stage 2I.2
will only add *drivers* (scenarios, baselines, result collection) that call the
existing public APIs.

---

## 1. Research Questions

The RQs are restricted to what the implementation can actually measure (Stage 2F
objectives, Stage 2D migration events, Stage 2D co-residency duration tracker).

- **RQ1 (threat exposure):** Does threat-aware NSSAO placement achieve lower f1
  (mean threat × server-risk) than non-security-aware baselines at comparable
  resource cost?
- **RQ2 (co-residency risk):** Does NSSAO reduce f2 (cross-entity co-residency
  max-threat risk) versus baselines, and versus the initial placement from which
  NSSAO starts?
- **RQ3 (trade-off):** What f4 (unused capacity / utilization) and f3 (cost) price is
  paid for the security gains in RQ1/RQ2? I.e., where does the selected solution sit
  relative to baselines on the resource-efficiency axes?
- **RQ4 (migration cost):** How many migrations and how much migration cost are
  incurred by NSSAO relative to baselines that also may migrate (Best-Fit, MMF)?
  (Only baselines that perform relocation after an initial placement incur
  migrations; see §3.)
- **RQ5 (robustness across threat populations):** Do the directional conclusions of
  RQ1–RQ4 hold for both Azure-derived and Alibaba-derived threat-score populations,
  given their different score distributions and adaptive thresholds?

Non-questions (explicitly out of scope): optimality claims, real-world security
improvement, attack-detection accuracy (no labels exist), convergence theory.

---

## 2. Proposed Method (Under Test)

The completed pipeline, executed per run:

```
Isolation Forest threat scores (Python pipeline, frozen)
  → adaptive threshold (Stage 2E, percentile rule, dataset-level)
  → threat metadata on CloudSim VMs (Stage 2B → 2C VmThreatState)
  → deterministic initial placement (Stage 2H FFD) — SHARED SEED STATE
  → NSSAO multi-objective optimization (Stage 2G, hypothetical/read-only)
  → deterministic final-solution selection: lexicographic min (f2, f3, f1, f4),
    index tie-break, after front-1 placement deduplication (Stage 2H)
  → single physical migration application (Stage 2D service)
  → post-hoc metrics: f1–f4 of final state, migration events,
    co-residency duration episodes (Stage 2D tracker snapshots at t=0 and t=1)
```

Objectives (Stage 2F, unchanged):

| Objective | Formula (Stage 2F) | Direction |
|---|---|---|
| f1 | mean over VMs of (threat score × server risk of assigned host) | lower better |
| f2 | sum over hosts of max cross-entity co-resident threat | lower better |
| f3 | active hosts × perHostCost + migrations × perMigrationCost | lower better |
| f4 | 1 − overall utilization (CPU and RAM averaged) | lower better |

Server risk (Stage 2C): `ServerRiskModel(baseHostRisk, utilizationRiskWeight)` —
utilization-dependent host risk; identical parameters across all methods.

**Post-hoc (not optimized directly):** co-residency duration metrics (total,
high-threat, episode count) — computed by the Stage 2D tracker from snapshots at the
initial-placement event (t=0) and the migration event (t=1).

Adaptive threshold: computed **per scenario** from the scenario's threat-score
population via `AdaptiveThreatThreshold` (P75/P90 percentile rule, Stage 2E), and
used only for the high-threat classification inside the co-residency duration
tracker. It is NOT a production threshold and NOT an input to f1–f4.

---

## 3. Baselines

### 3.1 Baseline set (recommended: 3)

| # | Baseline | Exists in paper? | One-line description |
|---|----------|------------------|----------------------|
| B1 | FFD (First-Fit Decreasing) static | **project-defined** (standard heuristic; not from the paper) | Deterministic greedy bin-packing; also serves as the shared initial placement |
| B2 | Best-Fit Decreasing + utilization-oriented local search | **project-defined** (standard heuristic; not from the paper) | Resource-efficiency-oriented: packs VMs to the fullest host, then tries single-VM moves that reduce f4 |
| B3 | Random feasible placement + random relocation | **project-defined** (standard control; not from the paper) | Randomized control; distributes outcomes over the feasible space |

No security-aware non-NSSAO baseline is added (justification in §3.6).

The paper (the PDF in `project/`) is NOT the source of any of these baselines; they
are the project's own comparison set, deliberately chosen as standard,
name-transparent heuristics. **No invented named algorithm is attributed to the
paper.**

### 3.2 B1 — FFD static

- **What it does:** Sort VMs by decreasing CPU demand (ties: ascending VM ID); place
  each VM on the first host with sufficient CPU and RAM. This is exactly the
  Stage 2H `InitialPlacementStrategy`; reuse it unchanged.
- **Why appropriate:** It is the canonical deterministic greedy baseline, trivially
  reproducible, and it is *also the common initial placement* for every method
  (including ours), so it doubles as the reference point "no optimization at all."
- **Controls for:** Placement quality obtainable with zero security awareness, zero
  optimization, and zero migrations (it is purely initial placement).
- **Does NOT control for:** Security awareness, multi-objective trade-offs, any
  utilization optimization beyond incidental FFD packing.
- **Source:** Project-defined (standard first-fit heuristic; not claimed as paper's).

### 3.3 B2 — Best-Fit Decreasing + utilization-oriented local search

- **What it does:** (a) Sort VMs by decreasing CPU demand; place each VM on the
  host with the smallest *remaining* capacity that still fits (tightest fit wins;
  ties: lower host ID). (b) Then one pass of single-VM relocation local search:
  repeatedly move the VM whose move yields the largest f4 decrease, subject to
  feasibility, until no single move improves f4 or a small iteration cap (e.g., 2×N
  moves) is reached. Migrations counted as in the proposed method.
- **Why appropriate:** It is the resource-efficiency counterpart of the proposed
  method: it optimizes f4 (and incidentally f3) without any security input. It
  answers "is NSSAO's security gain just re-discovering what a packer would do?"
- **Controls for:** Utilization/cost optimization ability without security awareness.
- **Does NOT control for:** Multi-objective trade-off search (it optimizes one
  objective greedily); it may accidentally degrade f1/f2.
- **Source:** Project-defined (best-fit + one-shot local search; not claimed as
  paper's).

### 3.4 B3 — Random feasible placement + random relocation

- **What it does:** (a) Shuffle VM IDs with a per-run seeded RNG; place each VM on
  the first host where it fits (shuffled first-fit). (B) After the *shared* initial
  placement, with probability p_rel (e.g., 0.1) pick a random feasible VM move
  (uniform over VM × feasible target hosts) and apply it, for a fixed number of
  attempts (e.g., 2×N attempts). All randomness from the shared seeded RNG stream,
  never from unseeded `Random`.
- **Why appropriate:** Every heuristic encodes assumptions; a randomized control
  shows the *spread* of outcomes achievable without any intelligence, and prevents
  overclaiming when our method beats fixed heuristics.
- **Controls for:** Lower envelope of intelligent placement; distribution context.
- **Does NOT control for:** Anything specific — by design.
- **Source:** Project-defined (standard randomized control; not claimed as paper's).

### 3.5 Optional B4 — Multi-Start FFD with local search (MSF4) — only if needed

- **What it does:** Best-Fit (B2) run from K different deterministic perturbations
  of the VM order (e.g., K=5 orderings derived from the seeded RNG), selecting the
  best f4 outcome.
- **Why appropriate:** If reviewers worry B2 is too weak, a multi-start variant
  shows a *searched* non-security result without inventing a new algorithm.
- **Controls for:** Search effort without security awareness.
- **Does NOT control for:** Security objectives (like B2).
- **Source:** Project-defined. **Recommended only if RQ3 results look too easy;**
  keep it out of the first suite.

### 3.6 Is a security-aware non-NSSAO baseline necessary? — No (for now)

A security-aware non-NSSAO baseline would mean a greedy heuristic that sorts or
scores VMs by threat and packs them to minimize f1/f2 directly (e.g., threat-sorted
FFD, or per-host threat-cap greedy). The recommended position for this project:
**not in the first suite.** Reasons:

1. The scientific question is whether *multi-objective optimization* (NSSAO) is
   worth its cost — the baselines establish what non-optimized placement achieves.
   A security-greedy baseline would need its own objective balancing, effectively
   inventing a second optimizer with arbitrary parameter choices that invite
   exactly the criticism Stage 2H's selection rule avoided.
2. The smallest sufficient set principle (below): 3 baselines already separate
   "no intelligence" (B1), "efficiency-optimized" (B2), and "randomized" (B3).
3. If a reviewer demands it, the natural candidate is **threat-sorted FFD** (B1
   with VM sort key = decreasing threat score instead of decreasing MIPS) — a
   one-line variant, project-defined, zero new parameters. It is the designated
   fallback (B5) and is documented here for transparency, not implemented in
   Stage 2I.2 unless required.

**Recommended smallest set: B1, B2, B3** — sufficient for a student research
project: they isolate (a) no-optimization, (b) single-objective efficiency
optimization, (c) randomized control, and (d) our full method. This is the minimum
that supports RQ1–RQ4; anything more invites the "arbitrary baseline zoo" criticism.

---

## 4. Scenario Matrix

All scenarios are small enough to run locally (seconds per run at these scales).
Every scenario is (dataset, profile) × scale.

### 4.1 Threat-pressure profiles

Threat pressure is controlled by **subsetting the real entity score CSVs** — no
synthetic scores are generated. Profiles are selected by sorting real entities by
score and taking slices:

| Profile | Definition (from a within-dataset score CSV) | Purpose |
|---|---|---|
| A — Low threat pressure | Lowest-score entities: sorted ascending, take first ceil(0.4·N) entities; VM threat scores = entity scores | Tests whether methods differentiate when almost all scores are low; f2 naturally small |
| B — Moderate | Middle slice: entities with rank in [0.3N, 0.7N] | Realistic mixed population |
| C — High | Highest-score entities: top ceil(0.4·N) by score | Threat-awareness should matter most here |
| D — Strong co-residency pressure | Moderate scores but VM: host ratio forced high (e.g., 4×) so many VMs must share hosts → many cross-entity pairs | Separates methods on f2 specifically |
| E — Resource pressure | Moderate scores; aggregate VM demand ≈ 85–95% of aggregate host capacity | Tight packing forced; security vs. feasibility tension |
| F — Scale | Same profile as B, at the 3 scales below | Robustness of conclusions across sizes |

Profile A/C use the *whole* moderate-scale entity set filtered to a slice; the
"0.4·N" fractions exist to keep run sizes near the moderate scale. (Exact slice
sizes are recorded in the results table by the driver, so the scenario is
reproducible from the doc.)

### 4.2 Scales (for profile F, and default for all)

| Scale | VMs (entities) | Hosts | Feasibility margin |
|---|---|---|---|
| S | 8 | 3 | comfortable |
| M (default) | 24 | 8 | comfortable |
| L | 48 | 16 | tighter but feasible |

- **S** validates driver mechanics and keeps total suite runtime in seconds.
- **M** is the primary analysis scale.
- **L** probes robustness at doubled size; NSSAO at M/L uses the Stage 2G defaults
  (N=40, t_max=10, seed per §6).

Scale feasibility rule: aggregate VM MIPS/RAM ≤ 85% of aggregate host capacity
(margin for FFD feasibility guarantee), except profile E which targets 85–95%
*by construction* and must pass the FFD feasibility check before any method runs.

### 4.3 Dataset axis

Two within-dataset score populations, read from the **existing** pipeline outputs
(no Python re-runs):

- **Azure-derived:** `project/outputs/azure_to_azure_scores.csv` (248 entities)
- **Alibaba-derived:** `project/outputs/alibaba_to_alibaba_scores.csv` (67 entities)

VM→entity mapping is one VM per entity (Stage 2B `EntityVmMapping`, sequential VM
IDs). Hosts per scenario are constructed with heterogeneous-but-deterministic
configurations drawn from the small set used in validation (e.g., 2000/1000/1000
MIPS classes), with exact counts per scale (3/8/16).

`dataset` field on each `VmThreatState` is set from the CSV's dataset column, so the
co-residency tracker's entity identity (dataset, entity_type, entity_id) is exact.

### 4.4 Scenario matrix (full first suite)

12 (dataset × profile) cells × 3 methods × seeds (§6). The full grid is:

| | Azure | Alibaba |
|---|---|---|
| A (low) | A-Az | A-Al |
| B (moderate) | B-Az / B-Az-L (scale F) | B-Al / B-Al-L (scale F) |
| C (high) | C-Az | C-Al |
| D (co-residency) | D-Az | D-Al |
| E (resource) | E-Az | E-Al |

Total runs = 12 cells × 4 methods (proposed + 3 baselines) × 5 seeds = **240 runs**.
At validation-scale runtime (Stage 2H full pipeline ≈ 1–2 s per run), the entire
suite is minutes. Scale L is included in the 12 cells (2 cells), so no extra runs.

**Scale-F cells are B-Az-L and B-Al-L (profile B at scale L); all other cells at
scale M; S is used only in a 1-cell smoke cell (B-Az at scale S) before the suite
runs.**

### 4.5 What is deliberately NOT varied

- NSSAO parameters: Stage 2G defaults everywhere (N=40, t_max=10, stallLimit=3,
  melting 1.0→0.1). Varying them is a sensitivity study, out of scope.
- Cost parameters: perHostCost = 1.0, perMigrationCost = 1.0 everywhere (sensitivity
  analysis deferred).
- Server-risk parameters: `ServerRiskModel(0.1, 0.3)` everywhere (Stage 2C/2F
  validation convention).
- Objective definitions f1–f4: frozen (Stage 2F).

---

## 5. Fair-Comparison Rules

### 5.1 Identical across ALL methods in a scenario cell

| Item | Rule |
|---|---|
| VM set | Same entities, same order, same VM IDs (Stage 2B mapping: VM ID = base + entity rank) |
| Host set | Same host count, same per-host (MIPS, RAM, BW, storage) list, same host IDs |
| VM resource requirements | Same (MIPS, RAM, BW, size) per VM for every method |
| Threat scores | Byte-identical score values from the same CSV slice |
| Adaptive threshold | Computed once per scenario from the shared score population; passed to every method identically (classification-only use) |
| Initial placement | FFD (Stage 2H `InitialPlacementStrategy`) for ALL methods, incl. baselines |
| Cost parameters | perHostCost = 1.0, perMigrationCost = 1.0 (same as Stage 2H validation) |
| Server-risk parameters | `ServerRiskModel(0.1, 0.3)` |
| Simulation duration | Same two-event timeline: placement snapshot t=0, migration snapshot t=1.0 |
| Seeds | One shared scenario seed drives: VM order for FFD (fixed), baseline RNGs, NSSAO seed. Same seed → same inputs for every method |
| NSSAO seed policy | Seed = scenario seed for every method (used only by proposed method and B3's RNG stream, B2's perturbations if B4 is enabled) |

Wait — one important asymmetry to declare honestly: baselines B1/B2/B3 need a
"what would the initial placement be" stage only if they relocate. To keep the
migration accounting identical, ALL methods (including B1 static) start from the
same FFD initial placement and, if their strategy differs from that placement,
the difference is applied as **migrations counted identically** by the Stage 2D
service.

### 5.2 Baselines that necessarily differ in initial placement — honesty table

| Method | Initial placement | Final differs from shared FFD? |
|---|---|---|
| Proposed | shared FFD, then NSSAO decides final | yes (migration events recorded) |
| B1 FFD static | shared FFD, no relocation | no |
| B2 Best-Fit + local search | shared FFD, then re-pack per B2 rules, counted as migrations | yes (final differs, migrations counted) |
| B3 random + relocation | shared FFD, then random relocations from the FFD state, counted as migrations | yes (final differs, migrations counted) |

The cleanest design: **all methods start from the SAME FFD initial placement**
(that is the "initial state" row of §5.1). B1 stops there. The proposed method,
B2, and B3 then relocate. This makes migration counts comparable across all
methods: migration count = |final − initial|, recorded by the Stage 2D service.

### 5.3 Avoiding unfair advantage to our method

1. **Shared initial placement:** every method starts from the same FFD state, so
   NSSAO's improvement is measured from the same starting point, never from a
   worse one.
2. **Same feasibility machinery:** baselines relocate through the same
   feasibility checks (Stage 2F `validate`), so a baseline is never handed
   infeasible outputs that would inflate our relative result.
3. **Same evaluation code path:** all methods' final placements are evaluated by
   the same Stage 2F `ObjectiveEvaluator` and the same Stage 2D trackers. No
   method-specific metric definitions.
4. **No threshold gaming:** the adaptive threshold is a scenario property
   computed once from the shared score population — identical for all methods.
5. **No test-set leakage:** profiles A/C slice the same CSV that defines the
   threshold population — this is placement simulation, not ML model selection;
   no held-out concept applies. The Python scoring pipeline is frozen and was
   not tuned on any Java-side outcome.
6. **Baselines get the same effort budget:** B2's local search is capped at the
   same order of evaluation effort as NSSAO (≈ 2×N moves ≈ one NSSAO iteration's
   evaluations); if NSSAO evaluates ~N_pop×t_max candidates, B2's cap is set to
   the same order. Documented per run.

### 5.4 Search never touches live state

Baseline placement search (B2's local search, B3's relocation sampling) never
touches live CloudSim state; only the final baseline placement is applied via the
Stage 2D service. This mirrors the NSSAO hypothetical-evaluation invariant
validated in Stage 2H.

---

## 6. Seed / Repetition Policy

- **Seeds per (dataset × profile × method) cell: 5** (seeds 1, 2, 3, 4, 5).
- **Repetitions:** 5 runs per cell per method. Methods with no randomness (B1, B2,
  proposed — NSSAO is seeded) produce identical repeats; the driver may detect
  this and record 1 run + note, but the analysis plan (§8) is written for 5.
- **Seed policy:** master seed S = 1000 + cellIndex; run seed = S + r for r in
  0..4. The scenario seed also feeds NSSAO's `NssaoConfig.seed` for the proposed
  method and the seeded RNGs of B3/B4. Seed values recorded per run in results
  CSV. No unseeded randomness anywhere in the pipeline.
- **Why 5 is sufficient here:** the dominant variance across the 5 repetitions of
  a cell comes only from the randomized baselines (B3) — deterministic methods
  (B1, B2, proposed) have zero within-cell variance. With n=5 the median and
  range are well estimated for the randomized baseline; for the deterministic
  methods, repetition only confirms determinism. This is a small controlled
  simulation study, not a sampled empirical study; 240 total runs already exceeds
  what is needed for the planned descriptive statistics (§8). Increasing seeds
  would only widen B3's empirical distribution estimate; it cannot change the
  deterministic methods' outcomes, so the marginal value is low. If any RQ turns
  out "too close to call," a documented follow-up with 20–30 seeds on that single
  cell is the prescribed remedy — not a blanket increase.

---

## 7. Metrics

Every metric: direction, formula/source, NSSAO-objective vs post-hoc.

### 7.1 Primary (the four objectives, evaluated on the FINAL placement)

| Metric | Direction | Formula / Source | NSSAO objective? |
|---|---|---|---|
| f1 threat×server-risk | lower better | Stage 2F `ObjectiveValues.getF1ThreatServerRisk()` of final placement | yes |
| f2 co-residency risk | lower better | Stage 2F `getF2CoResidencyRisk()` of final placement | yes |
| f3 cost | lower better | Stage 2F `getF3Cost()` of final placement (active hosts × perHost + migrations × perMigration) | yes |
| f4 unused capacity | lower better | Stage 2F `getF4UnusedCapacity()` of final placement | yes |

### 7.2 Secondary

| Metric | Direction | Formula / Source | Type |
|---|---|---|---|
| Migration count | lower better | Stage 2D `VmMigrationService.getMigrationEvents().size()` | post-hoc |
| Migration cost | lower better | count × perMigrationCost (Stage 2F f3 migration term) | post-hoc (part of f3) |
| Active hosts | context (lower usually better) | hosts with ≥1 VM in final live state | post-hoc (part of f3) |
| CPU utilization | higher better | Σ vm MIPS / Σ host MIPS over final live state | post-hoc |
| Memory utilization | higher better | Σ vm RAM / Σ host RAM over final live state | post-hoc |
| Overall utilization | higher better | (CPU + RAM util)/2 | post-hoc |
| Total co-residency duration | lower better | Stage 2D tracker `getTotalCoResidencyDuration()` from event snapshots | post-hoc |
| High-threat co-residency duration | lower better | tracker `getTotalHighThreatCoResidencyDuration()` | post-hoc |
| Co-residency episode count | lower better | tracker `getEpisodes().size()` | post-hoc |
| Runtime / computational overhead | lower better | wall-clock per run; NSSAO iterations + evaluations vs baseline move counts; front-1 size | post-hoc |
| Front-1 size / unique placements | context | Stage 2H selector stats | post-hoc (proposed method only) |

### 7.3 Metric notes

- **Primary metrics are the four objectives** — this is what the method optimizes;
  secondary metrics explain *why* and *at what price*.
- **Co-residency duration is post-hoc** — nothing in NSSAO or the baselines
  optimizes it directly; it is a consequence of the final placement + event
  timeline. Its high-threat variant depends on the adaptive threshold.
- **Migration cost is inside f3** by definition; it is broken out separately so the
  security-price decomposition (RQ4) is explicit.
- All metric extraction uses the same code path for every method (same evaluator,
  same tracker), so cross-method differences cannot be instrumentation artifacts.

---

## 8. Statistical Summary Plan

Expected sample size: n=5 per cell per method (§6); deterministic methods collapse
to n=1 distinct outcome.

- **Report per cell per method:** mean, median, standard deviation, min, max.
- **95% confidence intervals** only for methods with genuine within-cell variance
  (B3) — via t-interval (n=5, df=4); flag that it assumes approximate normality of
  B3's outcome distribution, which is an assumption, not a guarantee.
- **No p-value hypothesis tests.** With n=5 and a deterministic competing method,
  significance machinery would be statistical theater. The honest claims are
  descriptive: overlaps of ranges and medians.
- **Paired comparison per seed:** because every method shares the scenario and
  seed, report *paired differences* (proposed − baseline) per seed with median and
  range of the differences. For deterministic methods the difference is constant
  across seeds, which is itself the finding.
- **Dominance summary:** per cell, report which methods are dominated (worse or
  equal on all four objectives and strictly worse on ≥1) — the Pareto stance among
  methods, mirroring the solution-level Pareto logic of Stage 2G.
- **Cross-dataset direction agreement (RQ5):** per RQ and per baseline, report
  the sign of the median paired difference (proposed − baseline) for Azure and
  for Alibaba. Agreement rule (fixed now, before any results exist): each dataset
  contributes 5 non-scale cells (profiles A–E); the direction is "consistent
  across datasets" when the sign agrees in ≥ 4 of 5 profile cells within each
  dataset, and the Azure and Alibaba majority signs are the same.
- **Effect presentation, not proof:** all comparisons presented as observed
  differences with ranges; no claim of statistical significance; no causal
  language.

---

## 9. Expected Tables / Figures

**Tables (results CSV → summary tables in the final report):**

1. **Per-cell summary table:** rows = method; columns = f1, f2, f3, f4
   (median [min, max] across seeds). One table per scenario cell.
2. **Paired-difference table:** rows = baseline (B1, B2, B3); columns = Δf1, Δf2,
   Δf3, Δf4 (proposed − baseline, median [min, max]).
3. **Migration accounting table:** method × scenario → migration count, migration
   cost, active hosts.
4. **Co-residency table:** method × scenario → total duration, high-threat
   duration, episode count.
5. **Runtime table:** method × scenario → mean wall-clock, NSSAO iterations /
   baseline move attempts, front-1 size (proposed only).
6. **Direction-agreement table (RQ5):** RQ × dataset → direction of median
   paired difference.

**Figures (deferred to the analysis stage after 2I.2; listed here for planning):**

- F1: Parallel-coordinates or scatter (f2 vs f3) per cell showing the
  method-level trade-off surface.
- F2: Bar + range plots of paired Δf2 (proposed − best baseline) per scenario.
- F3: Co-residency duration decomposition (total vs high-threat) per method.

No graphs are produced in Stage 2I.1; none in 2I.2 unless explicitly requested —
2I.2 delivers results CSVs and the tables above as text/CSV.

---

## 10. Limitations — What Experiments CANNOT Prove

1. **No real-world cloud security improvement.** CloudSim is a discrete-event
   simulator with instantaneous migrations and no attacker model. Results speak
   only to the simulated environment.
2. **No physical co-residency claims from Azure/Alibaba traces.** The public
   datasets provide resource-usage telemetry and entity metadata; they do NOT
   provide physical host placement. CloudSim supplies the simulated placement
   environment; scores derived from the traces are inputs, not co-residency facts.
3. **No globally optimal placement claim.** NSSAO is a metaheuristic with a
   documented stopping rule; front-1 membership ≠ optimality.
4. **No exact paper reproduction.** Documented adaptations (Stage 2G.1/G.3):
   population scale, melting-factor schedule, stall limit, discretization, repair,
   selection rule. NSSAO is "adapted from," not "reproduced from," the paper.
5. **No causal superiority claim.** Comparisons are observational within the
   simulator, with controlled inputs; "causal" language is avoided; dominance
   claims are restricted to the simulated scenario cells.
6. **No detection-accuracy claim.** Isolation-Forest scores are unsupervised
   anomaly scores; there are no attack labels to validate detection quality.
7. **No generalization beyond tested cells.** Conclusions hold for the tested
   (dataset × profile × scale) grid; other distributions/scales are untested.
8. **Small-scale constraint.** L = 48 VMs/16 hosts is far below production scale;
   runtime behavior at scale is untested.
9. **Threshold is classification-only.** The adaptive threshold influences only
   the high-threat co-residency duration reporting; f1–f4 are threshold-free.
   Results therefore cannot speak to threshold-choice sensitivity.
10. **Repair shaping (2G.3 I1):** at these scales repair may shape NSSAO's
    effective search distribution; repair-rate instrumentation is a known gap
    (Stage 2H §12.7) and is NOT retrofitted in 2I.2.

---

## 11. Exact Experiment Execution Plan (for Stage 2I.2)

Order matters; every step is runnable locally. NO step modifies Stage 2B–2H code.

1. **Pre-flight:** `mvn -o compile` clean; Stage2hValidation passes (2×).
2. **Scenario builder (new, stage2i package):** load CSV slice → `EntityVmMapping`
   (vmIdBase=0) → build hosts/VMs/threatMap per §4; compute adaptive threshold from
   the slice; emit a scenario manifest (cell id, dataset, profile, scale, entity
   slice range, host list, seed, threshold) to results dir.
3. **Driver per method:**
   - Proposed: `Stage2hCloudSimIntegration.run()` as-is (it already does FFD →
     NSSAO → select → migrate → metrics).
   - B1: FFD initial placement; apply to CloudSim; evaluate + track; zero
     migrations expected.
   - B2: FFD initial; best-fit re-pack + f4-local-search pass; apply delta as
     migrations via Stage 2D service; evaluate + track.
   - B3: FFD initial; K random feasible relocations (seeded); apply; evaluate.
   All methods: same scenario object, same evaluator/tracker instances
   configuration, metrics extracted identically.
4. **Runner:** iterate cells × methods × seeds; write one CSV row per run
   (`results/stage2i_runs.csv`) with cell metadata + all §7 metrics + seed +
   runtime. Also write per-run manifest and the scenario manifest.
5. **Determinism audit:** re-run 5 random (cell, method, seed) triples; byte-compare
   metric rows. Any mismatch = blocker.
6. **Smoke run:** 1 cell (B-Az at scale S) × 4 methods × seed 1; verify CSV schema,
   metric sanity (e.g., B1 migrations = 0, proposed front-1 ≥ 1).
7. **Full suite:** 240 runs (12 cells × 4 methods × 5 seeds). Estimated minutes on
   a laptop.
8. **Statistical summaries:** implement §8 as a small deterministic summarizer
   (median/min/max/mean/sd per cell; paired differences; dominance table;
   direction-agreement table). Output: `results/stage2i_summary.csv` + the §9
   tables as text.
9. **Regression gate:** after the suite, re-run Stage2B…2G.2 + Stage2hValidation +
   CloudSimExample1/2; all must pass (proves the experiment harness did not touch
   the pipeline).
10. **Documentation:** `stage_2i2_experiment_implementation.md` with observed
    results, anomalies, and the §9 tables.

**Runtime budget:** whole suite well under 15 minutes at documented scales; if a
cell exceeds ~2 s/run, drop scale-L cells before touching anything else.

---

## 12. What Stage 2I.2 Will Implement

1. `stage2i` package (new code only): scenario builder (CSV slices → scenarios),
   the three baseline drivers (B1 reuse of `InitialPlacementStrategy`, B2
   best-fit + f4 local search, B3 seeded random relocation), the results CSV
   writer, and the deterministic summarizer.
2. The runner over the 12-cell grid × 4 methods × 5 seeds with determinism audit
   and smoke cell.
3. Regression re-run gate after the suite.
4. `stage_2i2_experiment_implementation.md` documentation.

**Will NOT be implemented in 2I.2:** changes to Stage 2B–2H code, Python pipeline
re-runs, new objectives or thresholds, NSSAO parameter sweeps, statistical
significance testing, graphs, any large-scale runs.

---

**HARD STOP AFTER STAGE 2I.1. No code. No experiments. No graphs.**
