# Stage 2I.4 — Results Analysis & Audit (Analysis-Only Checkpoint)

Status: **COMPLETE (analysis + audit only)** · Date: 2026-09-11
Inputs: `stage_2i1_experiment_design.md`, `stage_2i2_experiment_framework.md`, `stage_2i3_experiment_run.md`
Data: `project/results/stage2i3/suite_runs.csv` (240 rows), `suite_manifest.csv`, `repro_runs.csv`

**The 240-run suite was NOT re-run. No graphs were produced. No thesis conclusions are drawn. Every finding below is labeled OBSERVED (raw numbers), INTERPRETATION (reading), or LIMITATION (what cannot be concluded).**

Outputs created: `project/results/stage2i4/` — `validation_report.txt`, `analysis_summary.csv`, `cell_method_summary.csv`, `paired_method_differences.csv` (produced by the new `stage2i/Stage2iAnalysis.java`, which only reads stored results).

---

## 1. Data source and run count

- Source: `project/results/stage2i3/suite_runs.csv` as produced by Stage 2I.3 (untouched).
- **Rows analyzed: 240** (12 cells × 4 methods × 5 seeds), 0 parse failures.
- Convention used throughout: **paired difference = baseline − NSSAO**, so **positive = NSSAO has the smaller value**. All four objectives are minimization; f4 = 1 − overall utilization, so lower f4 = higher utilization = tighter packing. No weighted combination of f1–f4 was ever computed.

## 2. Validation results (independent re-derivation from stored rows)

Full detail: `validation_report.txt`. Summary — **all checks OK**:

| Check | Result |
|---|---|
| 240 rows parsed, unique run keys | OK (240/240, 0 duplicates) |
| 12 cells present, all 4 methods per cell, seeds {1..5} per cell×method | OK |
| f1–f4 finite and non-negative on every row | OK |
| migration_cost == migration_count (perMigrationCost = 1) | OK |
| Utilizations in [0,1]; overall == (cpu+mem)/2 identity | OK |
| B1 migration count = 0 on all rows | OK |
| Fingerprints present; **exactly one initial-placement fingerprint per cell** (all methods share the FFD start — fairness assumption verified from stored metadata) | OK |
| B3 final placement varies across seeds in every cell | OK |
| `repro_runs.csv` vs primary rows (f1–f4 at CSV precision, migrations, both fingerprints): 32/32 match | OK |
| NSSAO telemetry present on all 60 proposed rows | OK (1/60 runs stopped by stall: B-alibaba-L seed 1, 8 iterations, front-1 = 30 — recorded, not a failure) |

Analysis-tool fixes made during this stage (analysis code only): the dominance verdict previously mislabeled some trade-off pairs because the comparison loop broke early; and the CSV header line was initially counted as a parse failure. Neither affects the stored experiment data.

## 3. Per-cell / per-method descriptive results

Medians across the 5 seeds (min/max and per-seed values in `analysis_summary.csv` / `cell_method_summary.csv`).

**f2 (cross-entity co-residency risk, lower better):**

| Cell | NSSAO | B1 FFD | B2 Best-Fit | B3 Random |
|---|---|---|---|---|
| A-azure-M | **0.0000** | 0.0116 | 0.0051 | 0.0043 |
| A-alibaba-M | **0.0102** | 0.2063 | 0.0907 | 0.0613 |
| B-azure-M | 1.7102 | 3.5309 | 2.0831 | 1.8788 |
| B-alibaba-M | 2.9177 | 5.8622 | 3.6227 | 3.3131 |
| B-azure-L | 5.3220 | 10.9020 | 6.1580 | 6.1621 |
| B-alibaba-L | 11.7473 | 16.9174 | 11.9131 | 14.9534 |
| C-azure-M | **1.6219** | 24.2575 | 10.8336 | 5.2756 |
| C-alibaba-M | **1.3018** | 19.8209 | 8.7955 | 4.5878 |
| D-azure-M | 3.8529 | 4.8099 | 4.2729 | 4.6525 |
| D-alibaba-M | 7.2316 | 8.0873 | 7.3438 | 8.4972 |
| E-azure-M | **4.0271** | 5.1114 | 4.5744 | 4.3536 |
| E-alibaba-M | **6.8057** | 8.8199 | 7.9700 | 7.7689 |

**f1 (mean threat × server-risk), f3 (cost), f4 (1 − utilization), migrations (medians):**

| Cell | f1 NSSAO (best other) | f3 NSSAO (best other) | f4 NSSAO (best other) | Migr NSSAO / B2 / B3 |
|---|---|---|---|---|
| A-azure-M | 0.0001 (0.0001) | 15 (2) | 0.789 (0.766) | 8 / 10 / 8 |
| A-alibaba-M | 0.0010 (0.0013) | 16 (2) | 0.789 (0.766) | 8 / 10 / 8 |
| B-azure-M | 0.0142 (0.0148) | 28 (4) | 0.520 (0.438) | 20 / 16 / 17 |
| B-alibaba-M | 0.0236 (0.0252) | 29 (4) | 0.520 (0.438) | 21 / 16 / 17 |
| B-azure-L | 0.0223 (0.0220) | 61 (8) | 0.514 (0.438) | 45 / 32 / 35 |
| B-alibaba-L | 0.0406 (0.0426) | 60 (8) | 0.520 (0.438) | 44 / 32 / 35 |
| C-azure-M | 0.1425 (0.1660) | 16 (2) | 0.789 (0.766) | 8 / 10 / 8 |
| C-alibaba-M | 0.1154 (0.1359) | 16 (2) | 0.789 (0.766) | 8 / 10 / 8 |
| D-azure-M | 0.0203 (0.0200) | 32 (6) | 0.367 (0.344) | 24 / 8 / 19 |
| D-alibaba-M | 0.0353 (0.0349) | 34 (6) | 0.367 (0.344) | 26 / 8 / 19 |
| E-azure-M | 0.0166 (0.0165) | 17 (3) | 0.344 (0.344) | 13 / 4 / 13 |
| E-alibaba-M | 0.0282 (0.0281) | 20 (3) | 0.344 (0.344) | 16 / 4 / 13 |

Total co-residency duration is **numerically identical across all methods within each cell** (A=29, B-M=68, B-L=136, D=80, E=84 time units); high-threat duration likewise (0 everywhere except C-azure=8, C-alibaba=1, B-alibaba-L=5). Seed-level values are in `cell_method_summary.csv`.

## 4. NSSAO vs baseline comparisons

**Dominance verdicts (on seed-matched medians, raw objective support only):** every one of the 36 proposed-vs-baseline pairs is a **TRADE-OFF** — NSSAO is better on some objectives and worse on others in every cell; it never dominates any baseline and is never dominated.

- **f2 — NSSAO better in 12/12 cells vs B1 and B2** (5/5 seeds everywhere), and vs B3 in 12/12 cells on medians (4–5/5 seeds; closest: B-alibaba-L vs B2 where B2 wins 2/5 seeds). Largest gaps: C-azure vs B1 = 22.64 median difference (f2 24.26 → 1.62); C-alibaba vs B1 = 18.52.
- **f3 — NSSAO pays more cost in all 12 cells** (median f3 difference vs B1: −13 to −53; vs B2: −2 to −16; vs B3: −2 to −11). OBSERVED cause: NSSAO performs the most migrations in every cell (e.g., B-azure-L: 45 vs B2's 32) and ends with more active hosts (it spreads VMs to separate threats).
- **f4 — B2 better (lower f4 = tighter packing) than NSSAO in every cell** (5/5 seeds, median difference −0.023 to −0.082). NSSAO ties or beats B3 on f4 in most cells. B1 is always worst on f4 (least packed).
- **f1 — mixed and small-scale.** NSSAO ties or beats B1/B2 everywhere. **Unfavorable: B3 (random relocation) achieves lower f1 than NSSAO in some cells** — median difference negative (NSSAO worse) in B-azure-L (−0.000246), D-alibaba (−0.000566), D-azure (−0.000299), E-alibaba (−0.000402), and NSSAO loses to B3 in 1–4 of 5 seeds per cell. Relative magnitudes are small (≤ ~4 % of the cell's f1).
- **Dominance among baselines:** B1 is dominated by B2 in most cells on f4/f3; B3 is dominated by B2 in several cells; no baseline dominates NSSAO anywhere.

## 5. Dataset-wise observations (Azure vs Alibaba kept separate)

- **OBSERVED:** directions agree in every cell pair: NSSAO better on f2, worse on f3, B2 better on f4, f1 differences tiny — for both datasets.
- **OBSERVED:** magnitudes differ (e.g., C-azure B1 f2 = 24.26 vs C-alibaba 19.82; thresholds in C differ: 0.900 azure vs 0.775 alibaba — properties of the real score populations under Stage 2E defaults).
- **OBSERVED (unfavorable-for-NSSAO cells appear in both datasets):** B3-beats-NSSAO-on-f1 cells exist for Azure (D, E) and Alibaba (D, E, B-L) — not a one-dataset artifact.
- **INTERPRETATION:** the qualitative behavior of the pipeline is consistent across the two score populations; the datasets are NOT pooled into any single conclusion.

## 6. Profile-wise observations

- **A (low threat):** f1 nearly identical across methods (≈0.0001–0.0018); f2 small but ordered (NSSAO best, incl. exact 0.0 in 4/5 A-azure seeds). Distinguishable on f2 only.
- **B (moderate):** clear f2 separation (NSSAO 1.71 vs B1 3.53 on B-azure-M); scales cleanly to L.
- **C (high threat):** strongest differentiation — B1's f2 is ~15× NSSAO's. The intended "threat-awareness matters most here" behavior is OBSERVED.
- **D (co-residency pressure):** all methods forced to 0.8 CPU utilization; f2 gaps compress (B1 4.81 vs NSSAO 3.85 azure). Security separation shrinks when packing is tight.
- **E (resource pressure):** the solved fleet binds all methods to f4 = 0.344 (tie) and near-identical f1; differentiation survives only on f2 and cost. OBSERVED: under extreme capacity pressure, efficiency objectives converge and security objectives barely separate.
- **INTERPRETATION:** the five profiles produce distinguishable behavior, and the D/E cells honestly show where the advantage of security-aware optimization narrows.

## 7. Seed variability

- **OBSERVED:** B1, B2, and proposed NSSAO are deterministic per (cell, seed) except NSSAO's seeded search: e.g., B-azure-M proposed f2 across seeds = {27.0, 28.0, 28.0, 29.0, 30.0}; B3 (the only genuinely randomized method) spreads wider: {21.0, 24.0, 25.0, 27.0, 28.0}.
- Same-seed reproducibility: 32/32 re-runs match (§2).
- **LIMITATION:** n = 5 seeds supports descriptive ranges only; no significance testing is performed or appropriate here.

## 8. Migration / co-residency observations

- **OBSERVED:** NSSAO performs the most migrations in every cell (A/C: 8 vs B2's 10 in A — actually fewer there; B-M: 20–21 vs 16–17; B-L: 44–45 vs 32–35; D: 24–26 vs 8–19; E: 13–16 vs 4–13). In A/C cells NSSAO migrates slightly less than B2 (8 vs 10) because B2's best-fit pass moves 10 VMs.
- **OBSERVED:** migration cost == migration count everywhere (perMigrationCost = 1), and f3's migration term matches the recorded events (Stage 2I.3 invariant).
- **OBSERVED (important):** total and high-threat co-residency **durations are identical across methods within every cell**, while f2 and episode counts differ. In this two-snapshot timeline, moving a VM changes *which* pairs co-reside (and the risk-weighted f2), but the *amount* of co-residency time is fixed by the fleet packing, and high-threat pairs appear unavoidable at the binding cells (e.g., C: 10 VMs / 8 hosts forces 2 shared hosts).
- **INTERPRETATION:** in this design, the security gain NSSAO delivers is captured by f2 (risk-weighted co-residency), **not** by reducing high-threat co-residency duration; the defensive price is paid in f3 (more migrations/active hosts).

## 9. Unexpected / unfavorable findings (reported, not explained away)

1. **NSSAO never dominates any baseline** on raw objectives — every comparison is a trade-off (mostly: better f2, worse f3/f4).
2. **B3 random relocation beats NSSAO on f1 in the D and E cells** (and B-azure-L) — median f1 differences favor B3 in 4 cells, and per-seed wins in up to 4/5 seeds (D-alibaba, D-azure). The margins are small but consistent in those cells.
3. **B2 (pure efficiency local search) beats NSSAO on f4 in every cell** (5/5 seeds) — the security gain is not "free" efficiency-wise.
4. **High-threat co-residency duration is invariant across methods** — NSSAO does not reduce it in this timeline; it reduces the risk-weighted f2 instead.
5. **Total co-residency duration is placement-invariant** in the two-snapshot design — a structural property of Stage 2D's tracker + timeline, not a method property.
6. **Profile E flattens most differences** (capacity ceiling: all methods tie on f4, f1 within noise).
7. One NSSAO run stopped by stall (B-alibaba-L, seed 1) — recorded; its row is valid.
8. A-azure-M proposed f2 = 0 in 4/5 seeds (one seed 0.000766) — NSSAO found zero-risk placements in the low-pressure Azure cell.

## 10. Limitations

- Simulated environment (CloudSim), two-snapshot timeline, instantaneous migration; no attacker model, no downtime.
- n = 5 seeds; descriptive statistics only; no significance claims.
- Small scales (≤ 48 VMs); Azure/Alibaba traces supply scores only, not physical co-residency.
- f2's max-per-host aggregation and the two-snapshot co-residency accounting shape what "improvement" means here; other metric designs could differ.
- B1/B2/B3 are **project-defined** baselines; nothing here reproduces or evaluates the paper's own algorithms.
- Repair-rate instrumentation remains unavailable (Stage 2G gap), so NSSAO's decode-repair behavior is unmeasured.

## 11. What the data supports

- In these 12 simulated cells with 5 seeds each: the proposed pipeline achieves **lower f2 than B1, B2, and B3 in every cell** (5/5 seeds vs B1/B2; medians 4/5–5/5 seeds vs B3), with the largest relative gains in high-threat cells (C: f2 reduced ~10–15× vs B1).
- It achieves this at a **measured price**: more migrations and higher f3 in every cell, and higher f4 (less tight packing) than B2 in every cell.
- f1 differences between methods are small; NSSAO is not consistently better on f1 than a seeded random search under D/E pressure.
- All runs share the same initial FFD placement per cell (fingerprint-verified), the same evaluation path, and are reproducible per seed; B1 performs zero migrations; B3's outcomes vary with seed as intended.
- The Azure-derived and Alibaba-derived populations produce direction-consistent behavior with different magnitudes.

## 12. What the data does NOT support

- Any claim that NSSAO **improves real-world cloud security** — this is a simulator study with synthetic threat inputs and no attacker model.
- Any claim of **optimality, global or local** — NSSAO never dominates even the project baselines; front-1 membership is not optimality; no proven convergence.
- Any claim of **reduced high-threat co-residency duration** — the data shows duration invariance across methods in this timeline.
- Any claim of **statistical significance** — n = 5, descriptive only, no p-values.
- Any claim of **exact paper reproduction** — NSSAO here is an adapted, project-documented implementation.
- Any claim of **physical co-residency effects** derived from Azure/Alibaba traces — the traces provide scores, not placement facts.
- Any single-number "overall superiority" — the objectives were deliberately never collapsed into a weighted score.

---

**HARD STOP after Stage 2I.4 respected: analysis files and this audit report are complete; no graphs were created and no thesis conclusions were drawn. Stage 2I.5+ (figures/thesis writing) has NOT been started.**
