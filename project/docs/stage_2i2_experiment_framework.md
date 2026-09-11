# Stage 2I.2 — Experiment Framework Implementation

Status: **COMPLETE (framework only)** · Date: 2026-09-11 · Design: `stage_2i1_experiment_design.md`

Stage 2I.2 implements the experiment framework approved in Stage 2I.1. **No full 240-run suite was executed, no graphs were produced, and no thesis conclusions are drawn here.** All code lives in the new package `org.cloudbus.cloudsim.examples.stage2i`; Stages 2B–2H, the Python pipeline, and the NSSAO objectives were **not modified**.

> **Attribution notice:** Baselines **B1, B2 and B3 are project-defined baselines**. They are NOT from the paper and must not be attributed to it.

---

## 1. Files created (all in `stage2i`)

| File | Role |
|---|---|
| `Stage2iScenario.java` | Scenario builder: datasets, profiles A–E, scales, host fleets, VM resources, adaptive threshold; deterministic slicing of the real score CSVs |
| `Stage2iEngine.java` | Shared experiment engine: builds hosts/VMs, computes the common FFD initial placement via Stage 2H `InitialPlacementStrategy`, owns the **single** `ObjectiveEvaluator` + `CoResidencyDurationTracker` used by every method |
| `Stage2iBaselines.java` | B1 (static), B2 (best-fit + f4-oriented local search), B3 (seeded random feasible + relocation) — all hypothetical searches on a view model |
| `Stage2iProposedMethod.java` | Proposed method: composes the frozen public Stage 2F evaluator, Stage 2G `NssaoOptimizer` + `PlacementDecoder`, Stage 2H `ParetoSolutionSelector` — no Stage 2G/2H code changed |
| `Stage2iRunner.java` | Structured run configuration `(ScenarioSpec, Method, seed)`; runs one cell; owns the only physical CloudSim application and metric extraction |
| `Stage2iRunRecord.java` | Immutable per-run record with the full 37-column CSV schema and append semantics |
| `Stage2iSummarizer.java` | Descriptive summaries: median/min/max/mean/sd, paired per-seed differences, dominance |
| `Stage2iSmokeTest.java` | The 12 required verification checks (Section 9) |

---

## 2. Scenario construction

**Datasets (real, no synthetic scores).** Each dataset reads the within-dataset score CSVs produced by the Python pipeline:

- `azure` → `project/outputs/azure_to_azure_scores.csv`
- `alibaba` → `project/outputs/alibaba_to_alibaba_scores.csv`

Scores are loaded through the Stage 2B `ThreatScoreCsvReader` and sliced deterministically: sort by (score asc, entityId asc), then select fixed slices per profile. The CSVs are cached per dataset so a scenario is byte-for-byte reproducible.

**Profiles (Stage 2I.1 §4):**

| Profile | Meaning | Selection rule (deterministic) |
|---|---|---|
| A — Low | Low threat pressure | lowest-score slice |
| B — Moderate | Moderate pressure | middle-score slice |
| C — High | High pressure | highest-score slice |
| D — Co-residency pressure | Many VMs, few hosts | largest available slice capped to fleet capacity at ≤ 80 % CPU |
| E — Resource pressure | High utilization | VM count solved for fleet utilization ratio in [0.85, 0.95] |

**Scales:** `S` = 8 VMs / 3 hosts (1 LARGE + 2 SMALL), `M` = 24 VMs / 8 hosts (2L + 6S), `L` = 48 VMs / 16 hosts (4L + 12S). Host IDs are assigned LARGE-first in a fixed order (this ordering is part of FFD's definition and is frozen).

**VM resources:** every experiment VM is **250 MIPS / 256 RAM / 1 PE** — chosen so profile D is feasible (32 × 250 = 8 000 MIPS on the 10 000-MIPS M fleet = 80 %). Identical for every method.

**Adaptive threshold:** computed once per scenario from the scenario slice's score population via the Stage 2E `AdaptiveThreatThreshold` defaults (tail weight 0.5, bounds [0.5, 0.9]). It is a *scenario property* passed identically to every method — never a per-method fit. **Note:** bounds [0.5, 0.9] are the existing Stage 2E production defaults, not a fixed classification rule.

**Scenario cells:** the approved suite is `{A,B,C,D,E} × {azure, alibaba} × {S,M,L}` plus the 2 scale-F cells = **17 cells**; full enumeration × 4 methods × 5 seeds = the 240-run suite (**not executed in this stage**).

---

## 3. Baseline definitions (all project-defined)

All three baselines start from the **same FFD initial placement** as the proposed method and use the **same** feasibility rules, migration service semantics, and evaluation path.

### B1 — FFD static (`b1_ffd_static`)
- **What:** the shared deterministic FFD initial placement; zero optimization; zero relocations; zero migrations.
- **Why:** the "no optimization at all" reference. Any improvement by another method must come from its search, not from evaluation artifacts.
- **Controls for:** simulator/evaluation overhead; the value of doing *anything*.
- **Does not control for:** packing effects — a pure efficiency packer can look good for f4 without any security intent.
- **Provenance:** project-defined (FFD itself is the Stage 2H initial-placement strategy).

### B2 — Best-fit + f4-oriented local search (`b2_bestfit_f4_local_search`)
- **What:** iteratively evaluates single-VM best-fit relocation candidates on a view model and greedily accepts moves that improve **f4** (overall utilization) without violating the Stage 2F feasibility gate; deterministic pass order.
- **Why:** the *efficiency counterfactual*. Tests whether NSSAO's security gains are just a by-product of repackaging that any utilization-driven search would find.
- **Controls for:** "any extra search helps"; separates efficiency-driven gains from security-driven gains.
- **Does not control for:** explicitly security-aware search (that is the proposed method's job).
- **Provenance:** project-defined hill-climber; not from the paper.

### B3 — Random feasible + seeded random relocation (`b3_random_feasible_relocation`)
- **What:** from the same initial placement, draws feasible relocation candidates from a `java.util.Random` with the run seed and accepts improving-or-equal random moves for a fixed attempt budget.
- **Why:** randomized control. Quantifies the outcome spread a naive stochastic search achieves, so NSSAO's advantage can be judged against "random search on the same budget".
- **Controls for:** luck/budget effects; gives the min–max ranges meaning.
- **Does not control for:** intelligent guidance of any kind.
- **Provenance:** project-defined; seeded, not from the paper.

**Security-aware non-NSSAO baseline:** deliberately excluded from the first suite (Stage 2I.1 decision): a threat-aware heuristic without multi-objective search would need arbitrary weighting, i.e., a second invented optimizer. A threat-sorted FFD variant remains documented in the design as a transparency option only.

---

## 4. Fair-comparison controls (enforced in code)

Every method receives **identical**:

1. VM set, host set, and per-VM resource requirements (fixed constants, `Stage2iScenario`);
2. threat scores (deterministic slice of the real CSVs, cached);
3. scenario-level adaptive threshold;
4. cost parameters and server-risk parameters (single `ObjectiveEvaluator` instance);
5. **initial FFD placement** — computed once by the engine, shared by all methods; verified identical by fingerprint in smoke checks T1/T7;
6. simulation timeline (two-event structure: placement snapshot at t=0, migration snapshot at t=1);
7. metric/evaluation code path — one evaluator + one co-residency tracker owned by the engine, used by every method.

**Live-state invariant:** baseline searches run purely on an immutable view mapping (`CandidatePlacement` view models); the runner is the only component that applies a final placement to live CloudSim objects, and it does so exactly once, after the method returns. B1 trivially verifies the invariant (identity). The same invariant held in Stage 2H for NSSAO.

**Migration comparability:** because all methods share the initial placement, migration count = |final − initial| by construction and is verified (smoke check T9).

---

## 5. Seed policy

- Seeds are **run configuration inputs** (`RunConfig.seed`), one seed per (scenario, method, repetition).
- **B3**: the seed drives `java.util.Random` — different seeds → different trajectories; the same seed → identical results (verified: T5, T6).
- **Proposed**: the seed is passed to `NssaoOptimizer` (search trajectory varies; final selection within a front is the deterministic lexicographic rule from Stage 2H).
- **B1/B2**: deterministic; the seed is recorded but has no effect.
- Planned suite: **5 seeds** (e.g., 1–5) × 17 cells × 4 methods = 240 runs. Five repetitions is sufficient for the descriptive, non-inferential analysis planned (medians, ranges, paired differences) and keeps the suite runnable locally.

---

## 6. Runner architecture

```
Stage2iRunner.runOne(RunConfig, csvPath, allowReplace)
 ├─ Stage2iScenario.build(spec)          → ScenarioData (hosts, VMs, scores, threshold)
 ├─ new Stage2iEngine(data)              → common FFD initial placement + shared evaluator/tracker
 ├─ switch (method)
 │    ├─ B1      → Stage2iBaselines.b1Static(engine)               (view-only)
 │    ├─ B2      → Stage2iBaselines.b2BestFitF4LocalSearch(...)    (view-only)
 │    ├─ B3      → Stage2iBaselines.b3RandomRelocation(..., seed)  (view-only)
 │    └─ PROPOSED→ Stage2iProposedMethod.run(engine, seed, scale)  (frozen 2F/2G/2H components)
 ├─ engine.applyFinalPlacement(mapping)   ← the ONLY live-state mutation
 ├─ engine.runTimeline()                  → migration events + co-residency snapshots
 └─ extract metrics → Stage2iRunRecord → appendTo(csv)
```

A run key is `cellId|method|seed`; `runOne` is synchronized on the CSV path + engine lock so repeated invocations of the same key from the smoke test are safe.

---

## 7. Raw-result schema

CSV: `project/results/stage2i/<suite>_runs.csv` (smoke suite: `smoke_runs.csv`). 37 columns, header-written on file creation:

`schema_version, dataset, scenario_cell, profile, scale, method, seed, vm_count, host_count, adaptive_threshold, f1, f2, f3, f4, migration_count, migration_cost, active_hosts, cpu_utilization, memory_utilization, overall_utilization, total_coresidency_duration, highthreat_coresidency_duration, coresidency_episode_count, nssao_iterations, stopped_by_stall, rejected_decodes, front1_raw_size, unique_placements, duplicates_removed, relocations, search_attempts, initial_placement_fingerprint, final_placement_fingerprint, runtime_ms, repair_count, repair_rate, notes`

**NSSAO optimization metrics vs post-hoc CloudSim metrics** — explicitly separated:

- *NSSAO/search-only* (NA for baselines): `nssao_iterations, stopped_by_stall, rejected_decodes, front1_raw_size, unique_placements, duplicates_removed`.
- *Post-hoc CloudSim metrics* (all methods): `migration_count, migration_cost, active_hosts, *_utilization, *coresidency_*, fingerprints, runtime_ms`.
- `repair_count/repair_rate` are recorded as **`NA`** — not fabricated — because Stage 2G does not expose repair instrumentation (known limitation, carried from Stage 2H).
- `runtime_ms` is wall-clock and therefore **excluded from determinism comparisons**.

**No silent overwrite:** if the CSV exists, new rows are appended; duplicate run keys fail loudly unless the caller explicitly passes `allowReplace` (the smoke test passes `replace`).

---

## 8. Summary calculations (`Stage2iSummarizer`)

Reads a results CSV and writes three descriptive tables (text):

1. **Table 1 — per-cell per-method summaries:** median, min, max, mean, sd for f1–f4.
2. **Table 2 — paired per-seed differences:** proposed − baseline per seed per cell (requires the common seed policy).
3. **Table 3 — method-level dominance per cell (on medians):** method X dominates Y iff X ≤ Y on all of f1–f4 and < on at least one; otherwise "no dominance" is reported.

**No p-values, no significance claims, no thesis conclusions** — by design (Stage 2I.1: n=5 with deterministic competitors would make inferential theater).

---

## 9. Smoke test — results

`Stage2iSmokeTest` runs a tiny configuration (B-azure-S: 8 VMs / 3 hosts, methods × seeds 1–2, plus extra B3 seeds 1/3/42) and executes the 12 required checks:

| # | Check | Result |
|---|---|---|
| T1 | all methods share the same initial FFD placement | PASS |
| T2 | B1 produces zero relocations / zero migrations | PASS |
| T3 | B2 final placements pass the Stage 2F feasibility gate | PASS |
| T4 | B3 final placements pass the Stage 2F feasibility gate | PASS |
| T5 | same B3 seed reproduces identical results | PASS |
| T6 | different B3 seeds produce different trajectories | PASS |
| T7 | no baseline search mutates live CloudSim state (runner invariant + B1 identity) | PASS |
| T8 | final placement is the placement actually evaluated | PASS |
| T9 | migration count equals changed VM assignments | PASS |
| T10 | metrics produced through the common evaluation path (f3 decomposition + utilization identity) | PASS |
| T11 | raw results are reproducible (all 8 configs re-run) | PASS |
| T12 | summarizer reproduces hand-checkable values | PASS |

**12 / 12 checks passed.**

**Run twice in separate processes:** both runs exit 0 with **byte-identical console logs**. The results CSV differs between processes **only** in the `runtime_ms` column (wall-clock by definition); every result value — objectives, migration counts, fingerprints, front statistics — is identical. Determinism confirmed.

Observed smoke behavior (for transparency): proposed NSSAO reached front-1 sizes 40/26 (seeds 1/2) with 5 relocations; B2 converged to 8 relocations with active hosts 2 (f4 = 0.50 vs proposed 0.594) — illustrating exactly the security/efficiency trade-off the experiment is designed to quantify. These are smoke values, **not** experimental findings.

---

## 10. Reproducibility

- Scenario construction is a pure function of (dataset CSV, profile, scale) with deterministic sort orders; CSVs are cached per process.
- No run reads wall-clock, PIDs, or unordered collections for anything that affects results.
- Every row carries dataset, scenario cell, method, seed, VM/host counts, threshold, and both placement fingerprints — enough to re-run and byte-compare any single row (modulo `runtime_ms`).
- Build/run entry points:
  - compile: `mvn -o -DskipTests compile -pl modules/cloudsim-examples -am` (offline, verified exit 0)
  - smoke: `java -cp "<core>;<examples-classes>" org.cloudbus.cloudsim.examples.stage2i.Stage2iSmokeTest [replace]`
  - data dir override: `-Dstage2i.data.dir=<dir with the score CSVs>` (default resolves `project/outputs`)

---

## 11. Known limitations

1. **Repair instrumentation unavailable:** `repair_count/repair_rate` are always `NA` (Stage 2G does not expose repair statistics; instrumenting it was out of scope).
2. **Instantaneous migration** per the Stage 2D design; no live-migration downtime model.
3. **VM size fixed at 250 MIPS** — scenario D feasibility required a uniform smaller VM than Stage 2H's 500-MIPS validation VMs; identical across all methods, so comparisons remain fair.
4. **Small scale** (≤ 48 VMs) — the framework is scale-parameterized but the approved suite is deliberately small for local execution.
5. **Co-residency is simulated** — Azure/Alibaba traces provide threat-score populations only; CloudSim supplies the (simulated) physical co-residency. No claim of real physical co-residency is possible.
6. **n=5 seeds** supports descriptive comparison only; no statistical-significance testing is implemented (intentionally).
7. **Stage 2G/2H compositional reuse:** `NssaoConfig.Builder` does not expose a high-threat-threshold setter, so the engine-owned tracker applies the scenario threshold; NSSAO's *internal* decode checks use the Stage 2G default. Documented, not a fairness problem (thresholds are scenario-level inputs to all methods alike).

---

## 12. What Stage 2I.3+ may build on this

- The 240-run suite: enumerate the 17 cells × 4 methods × 5 seeds via `Stage2iRunner` (structured config already supports it; nothing is hard-coded).
- Summaries via `Stage2iSummarizer` on the suite CSV.
- Figures/thesis conclusions — **explicitly out of scope for Stage 2I.2**.

**HARD STOP after Stage 2I.2 respected: framework implemented and verified; no full experiment, no graphs, no conclusions.**
