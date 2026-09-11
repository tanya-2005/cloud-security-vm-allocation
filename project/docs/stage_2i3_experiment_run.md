# Stage 2I.3 — Approved Experiment Suite: Raw Run Report

Status: **COMPLETE (raw results + validation only)** · Date: 2026-09-11
Design: `stage_2i1_experiment_design.md` · Framework: `stage_2i2_experiment_framework.md`

**No graphs, no analysis, no thesis conclusions, no significance claims were produced. This stage executed the approved suite and validated the raw results only. HARD STOP after Stage 2I.3.**

---

## 1. What was executed

The full approved suite from Stage 2I.1 §4.4/§6, driven by the new
`Stage2iSuiteRunner` (stage2i package):

- **12 cells:** profiles A–E × {azure, alibaba} at scale M (10 cells) + approved scale-F cells **B-azure-L** and **B-alibaba-L** (2 cells)
- **4 methods:** `proposed_nssao`, `b1_ffd_static`, `b2_bestfit_f4_local_search`, `b3_random_feasible_relocation` (B1/B2/B3 are **project-defined** baselines, not from the paper)
- **5 seeds: 1, 2, 3, 4, 5** (see §7 on the seed-policy note)
- **Expected total: 12 × 4 × 5 = 240 runs**

## 2. Exact commands used

```bash
# Compile (authoritative; offline so no network probing / timeout)
cd cloudsim && ../apache-maven-3.9.16/bin/mvn -q -o -DskipTests compile \
    -pl modules/cloudsim-examples -am          # exit 0

# (during development) targeted fast compile of stage2i only:
cd cloudsim/modules/cloudsim-examples && javac -encoding UTF-8 \
    -cp "../cloudsim/target/classes;target/classes" -d target/classes \
    $(find src/main/java/org/cloudbus/cloudsim/examples/stage2i -name "*.java")

# Execute the full suite (from repository root; data dir auto-resolves
# project/outputs):
java -cp "cloudsim/modules/cloudsim/target/classes;cloudsim/modules/cloudsim-examples/target/classes" \
    org.cloudbus.cloudsim.examples.stage2i.Stage2iSuiteRunner

# Re-invocation #1 (after validator key fix) — exercised the resume path:
# same command; all 240 stored rows skipped, validation re-executed.
```

Invocation was **structured configuration** (cells × methods × seeds enumerated
in code from the approved design), not 240 hard-coded blocks.

## 3. Run accounting

| Item | Count |
|---|---|
| Expected runs | **240** |
| Completed runs | **240** |
| Failed runs | **0** |
| Missing expected keys | **0** |
| Duplicate run keys | **0** |
| Unexpected extra keys | **0** |
| Suite wall time | **25.4 s** (first full execution) |

A failure occurred at no point; `suite_failures.log` was therefore never
created. Had a run failed, the driver records the run key + full stack trace
verbatim and continues (runs are fully independent — fresh scenario/engine per
run); failures are **never** replaced by synthetic results.

## 4. Output file locations

Stage 2I.3 has a dedicated results directory; **Stage 2I.2 smoke outputs were not touched** (`project/results/stage2i/smoke_runs.csv` retains its 9 pre-existing rows; timestamps unchanged).

| File | Contents |
|---|---|
| `project/results/stage2i3/suite_runs.csv` | **240 raw run rows** (37-column schema of Stage 2I.2 §7) + header |
| `project/results/stage2i3/suite_manifest.csv` | Per-cell scenario manifest (§5 below) |
| `project/results/stage2i3/repro_runs.csv` | 32 validation re-run rows (kept separate so the primary CSV is never churned by validation) |
| `project/results/stage2i3/suite_failures.log` | *(not created — zero failures)* |

Per-row metadata covers every required field: dataset, scenario cell, profile,
scale, method, seed, VM/host counts, adaptive threshold, initial **and** final
placement fingerprints, f1–f4, migration count/cost, active hosts, CPU/memory/
overall utilization, total/high-threat co-residency duration, episode count,
NSSAO search metrics (proposed only), runtime, and success status (a row exists
only for a run that passed every in-runner invariant; failures go to the
failure log and are never silently skipped).

## 5. Scenario manifest (as executed)

| Cell | VMs | Hosts | Adaptive threshold | Fleet MIPS | Notes |
|---|---|---|---|---|---|
| A-azure-M | 10 | 8 | 0.500000000 | 10000 | lowest-score slice |
| B-azure-M | 24 | 8 | 0.500000000 | 10000 | middle slice |
| C-azure-M | 10 | 8 | **0.900000000** | 10000 | highest-score slice |
| D-azure-M | 32 | 8 | 0.500000000 | 10000 | co-residency pressure (≤ 80 % CPU) |
| E-azure-M | 24 | **4** | 0.500000000 | 7000 | solved fleet, 85–95 % utilization |
| A-alibaba-M | 10 | 8 | 0.500000000 | 10000 | |
| B-alibaba-M | 24 | 8 | 0.500000000 | 10000 | |
| C-alibaba-M | 10 | 8 | **0.775320578** | 10000 | |
| D-alibaba-M | 32 | 8 | 0.500000000 | 10000 | middle pool (28) deterministically expanded to 32 |
| E-alibaba-M | 24 | **4** | 0.500000000 | 7000 | |
| B-azure-L | 48 | 16 | 0.500000000 | 20000 | approved scale-F cell |
| B-alibaba-L | 48 | 16 | 0.500000000 | 20000 | pool expanded to 48 (dataset has 67 entities) |

Thresholds are the Stage 2E defaults (tailWeight 0.5, bounds [0.5, 0.9]) applied
to each cell's real score population; several cells land exactly on the bounds —
a property of the real populations under the approved defaults, **not** a fixed
classification rule.

## 6. Run counts and validation checks performed

**Per-dataset counts:** azure 120, alibaba 120. **Per-method counts:** each of
the 4 methods exactly 60. **Per-cell counts:** each of the 12 cells exactly 20
(4 methods × 5 seeds).

Validation executed automatically by the suite driver after execution:

1. **Row count** — 240 rows vs 240 expected ✅
2. **Duplicate run keys** — 0 ✅ (the storage layer additionally refuses
   duplicate keys by design; nothing was overwritten silently)
3. **Cell coverage** — every expected dataset × scenario × method × seed key
   present; no missing, no unexpected ✅
4. **Metric sanity on every row** — objectives finite and non-negative, no
   negative migrations, active hosts ≥ 1, both placement fingerprints present,
   **B1 migration count = 0 on every row** → 0 violations ✅
5. **Same-seed reproducibility** — representative subset (both B-M and both
   scale-F cells × all 4 methods × seeds 1 and 3 = 32 re-runs) re-executed and
   compared against stored rows on f1–f4 (at CSV precision), migration count,
   and both placement fingerprints: **32/32 MATCH, 0 mismatches** ✅
   (wall-clock `runtime_ms` is excluded by definition; a byte-level diff of a
   stored vs fresh row showed identity in **every** other field)
6. **Runner-internal invariants (per run, thrown loudly on violation)** —
   baseline search does not mutate live CloudSim state; proposed/baseline final
   placements pass the Stage 2F feasibility gate; migration events = changed
   assignments; final live placement = evaluated placement; evaluator migration
   term = actual events ✅ (240/240 runs passed → all rows valid)
7. **Regression gate after the suite** (Stage 2I.1 §11 step 9): Stage2B, 2C,
   2D, 2E, 2E.1 (both), 2F, 2G, 2H validations + CloudSimExample1/2 all **exit
   0** with `Checks failed: 0`; stage2i smoke test still 12/12 ✅

## 7. Seed policy note (design vs implementation, stated honestly)

Stage 2I.1 §6 contains two seed statements: the concrete list **"seeds 1, 2, 3,
4, 5"** and a master-seed formula `S = 1000 + cellIndex + r`. The 2I.2
implementation takes seeds as explicit run-configuration inputs and records
them per row. This stage used the **five concrete approved values 1–5** (what
the design and the Stage 2I.3 brief both name as "the 5 approved seeds"). The
exact seed per run is in the CSV, so any alternative seed mapping can be
re-derived and re-run without code changes.

## 8. Warnings / limitations

1. **`runtime_ms` is wall-clock** and varies across processes/JVMs; it is
   recorded per row but excluded from reproducibility comparisons (standard
   practice; it is also not an NSSAO objective).
2. **Reproducibility tolerance:** stored objectives are parsed from the CSV's
   9-decimal text, so re-run comparison uses a 1e-9 tolerance; fingerprints,
   counts, and booleans are compared exactly. A full byte-diff of a stored vs
   fresh row confirmed identity in all non-runtime fields.
3. **Validator fixes made during this stage** (stage2i code only, no impact on
   stored data):
   - `Stage2iSummarizer.Run` parsed f1–f4 from the wrong CSV columns
     (off-by-3; the 2I.2 smoke test's T12 covered the math helpers but not
     real-row parsing). Fixed to the actual schema; smoke T12 still passes.
   - The suite validator initially compared two different run-key formats
     (3-part display vs 4-part storage) — fixed by normalizing to the storage
     format; and reproducibility re-runs initially targeted the primary CSV
     (which would trip the no-silent-overwrite guard) — redirected to the
     derived `repro_runs.csv`.
4. **Profile-D cap and pool expansion** (documented in the 2I.2 doc): D uses
   32 VMs on the standard M fleet (≤ 80 % CPU) so the host fleet is identical
   across datasets; Alibaba's small middle pool is deterministically expanded
   where a cell needs more entities than the pool holds. Both are recorded in
   the manifest (`pool_expanded`).
5. **Not done here (by design):** no summarizer pass over the suite CSV, no
   paired-difference/dominance tables, no figures, no hypothesis tests, no
   superiority or security-improvement claims, no thesis conclusions. All of
   that belongs to the next stage (2I.4), which was **not** started.

## 9. Code created/modified in this stage

| File | Change |
|---|---|
| `stage2i/Stage2iSuiteRunner.java` | **Created** — suite driver: structured 240-run plan, manifest, resume-safe execution, explicit failure recording, post-run validation + reproducibility subset |
| `stage2i/Stage2iSummarizer.java` | **Fixed** — `Run` row parser column indices (f1–f4 were read 3 columns late) + added the fields the validator consumes |
| Stages 2B–2H, Python/ML, NSSAO, f1–f4, adaptive-threshold logic, baseline definitions | **Unchanged** |

Compile state: `mvn -o -DskipTests compile -pl modules/cloudsim-examples -am` → exit 0 (both before and after the suite).

---

**HARD STOP after Stage 2I.3 respected: the raw 240-run suite and its validation are complete; Stage 2I.4 analysis has NOT been started.**
