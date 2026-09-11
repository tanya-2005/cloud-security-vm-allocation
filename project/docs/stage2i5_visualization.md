# Stage 2I.5 — Results Visualization (Figures + Tables Only)

Status: **COMPLETE (visualization only)** · Date: 2026-09-11
Data sources (read-only): `project/results/stage2i3/suite_runs.csv` (untouched), `project/results/stage2i4/analysis_summary.csv`, `project/results/stage2i4/cell_method_summary.csv`
Generator: `project/scripts/stage2i5_make_figures.py` (Python 3.14 + matplotlib 3.11, headless Agg backend)
Output: `project/results/stage2i5/` — **8 figures × (PNG @ 200 dpi + SVG)** + **8 per-figure source CSV tables**.

**The experiment suite was NOT re-run; no stored result was modified (verified by SHA-256 before/after); no thesis prose or final conclusions are included. All interpretation rules from the stage brief are preserved below.**

---

## 0. Reproducibility

- One command regenerates every artifact from stored CSVs only:
  `python project/scripts/stage2i5_make_figures.py`
- No manually entered experimental values exist in the script; every plotted number is read from `analysis_summary.csv` / `cell_method_summary.csv`.
- Validation runs before any plotting: the script exits non-zero unless exactly **12 cells × 4 methods × 5 seeds** are present.
- Verified: two consecutive runs produce **byte-identical PNG and SVG files** (SVG date metadata suppressed via `savefig(metadata={"Date": None})`; SVG hash-salt fixed).
- Verified: SHA-256 hashes of all files under `project/results/stage2i3/` and `project/results/stage2i4/` are unchanged after generation.
- Aggregation used everywhere: **median across the 5 seeds** per (cell, method); seed variability shown as **min–max whiskers** (descriptive only; no significance tests anywhere).

Cell axis order for all per-cell figures: `A-azure-M, A-alibaba-M, B-azure-M, B-alibaba-M, B-azure-L, B-alibaba-L, C-azure-M, C-alibaba-M, D-azure-M, D-alibaba-M, E-azure-M, E-alibaba-M` (12 approved cells; B-azure-L / B-alibaba-L are the approved scale-F cells).

Method labels: `NSSAO (proposed)`, `B1 FFD static`, `B2 Best-Fit+f4 LS`, `B3 Random reloc.` — **B1/B2/B3 are project-defined baselines, not from the paper.**

---

## 1. Figure catalog

### Fig 1 — `fig1_f2_coresidency_risk.png/.svg`
- **Source CSV:** `fig1_f2_coresidency_risk_source.csv` (from `stage2i4/analysis_summary.csv`)
- **Aggregation:** median across seeds; min–max whiskers.
- **Shows:** f2 (cross-entity co-residency risk, lower better) per cell for all four methods. NSSAO has the lowest median f2 in all 12 evaluated cells; the gap vs B1 is largest in the C (high-threat) cells.
- **Does NOT prove:** real-world security improvement, optimality, or that f2 differences generalize beyond these 12 cells and 5 seeds.

### Fig 2 — `fig2_f3_defensive_cost.png/.svg`
- **Source CSV:** `fig2_f3_defensive_cost_source.csv`
- **Aggregation:** median + min–max.
- **Shows:** f3 (active-host cost + migration cost, lower better). NSSAO has the highest median f3 in all 12 cells; B1 the lowest (zero migrations, fewest active hosts).
- **Does NOT prove:** that the price is justified, or that no cheaper security-aware schedule exists.

### Fig 3 — `fig3_f4_resource_inefficiency.png/.svg`
- **Source CSV:** `fig3_f4_resource_inefficiency_source.csv`
- **Aggregation:** median + min–max.
- **Shows:** f4 = 1 − overall utilization (lower better = tighter packing). B2 has the lowest (best) f4 in every cell; B1 the worst; NSSAO in between.
- **Does NOT prove:** that utilization-oriented packing is preferable — it ignores f1/f2 by construction.

### Fig 4 — `fig4_f1_threat_server_risk.png/.svg`
- **Source CSV:** `fig4_f1_threat_server_risk_source.csv`
- **Aggregation:** median + min–max.
- **Shows:** f1 (mean threat × server-risk). Differences are small; NSSAO ties or leads vs B1/B2; **B3 can match or beat NSSAO on f1 in the D/E cells** — visible in the figure and stated in the 2I.4 audit.
- **Does NOT prove:** any detection-quality claim (scores are unsupervised anomaly scores; no labels exist).

### Fig 5 — `fig5_migration_count.png/.svg`
- **Source CSV:** `fig5_migration_count_source.csv`
- **Aggregation:** median (integer metric; whiskers usually collapse to the median because the deterministic methods have zero seed variance).
- **Shows:** migration events per method per cell. NSSAO migrates most in the B/D/E cells; B1 always 0 (validated); B2/B3 in between; in the A/C cells NSSAO (8) migrates slightly less than B2 (10).
- **Does NOT prove:** anything about migration downtime or energy — the Stage 2D design models instantaneous migration with perMigrationCost = 1.

### Fig 6 — `fig6_tradeoff.png/.svg` (multi-objective trade-off, **no overall score**)
- **Source CSV:** `fig6_tradeoff_source.csv`
- **Aggregation:** medians; one marker per method, one point per cell; two panels: (f3 vs f2) and (f4 vs f2).
- **Shows:** the honest trade-off surface — NSSAO points sit at low f2 but high f3/f4; B2 at low f4 but higher f2; B1 at low f3 but highest f2. **No method's point cloud dominates another's across the cells** (matching the 2I.4 verdict that all 36 proposed-vs-baseline pairs are TRADE-OFF).
- **Does NOT prove:** superiority of any method; deliberately no weighted "overall performance" score is computed or shown.

### Fig 7 — `fig7_azure_vs_alibaba.png/.svg`
- **Source CSV:** `fig7_azure_vs_alibaba_source.csv`
- **Aggregation:** medians; profiles A–E on the x-axis; one panel per dataset (**never pooled**).
- **Shows:** f2 by profile for each dataset side by side — direction-consistent behavior (NSSAO lowest, B1 highest in every profile) with dataset-specific magnitudes (e.g., C-azure B1 ≈ 24.3 vs C-alibaba ≈ 19.8; thresholds differ per population).
- **Does NOT prove:** cross-dataset generalization as a statistical claim; with n = 5 seeds the comparison is descriptive.

### Fig 8 — `fig8_relative_tradeoff.png/.svg`
- **Source CSV:** `fig8_relative_tradeoff_source.csv`
- **Aggregation:** per cell, f2 reduction vs B1 (fraction) beside f3 increase vs B1 (fraction), both on medians.
- **Shows:** per cell, how much co-residency risk NSSAO removes relative to the shared FFD start and what cost it pays for it — the observed trade-off in one view (largest f2 reduction in C-azure ≈ 0.93, bought with f3 increase ≈ 7×).
- **Does NOT prove:** that this exchange rate is "worth it" — that is a policy judgment, not a measurement.

---

## 2. Validation results

| Check | Result |
|---|---|
| 12 cells × 4 methods × 5 seeds present in source CSVs | OK (script hard-fails otherwise) |
| Figures regenerate byte-identically from stored CSVs | OK (PNG + SVG, two consecutive runs) |
| Stored raw results modified? | **No** — SHA-256 of all `stage2i3/` + `stage2i4/` files identical before/after |
| Manually entered experimental values | None (script reads CSVs only) |
| Filenames | Presentation-ready, descriptive (see catalog) |

## 3. Visualization limitations

1. **Medians with min–max whiskers describe, they do not infer** — no error bars in the statistical sense; n = 5.
2. Fig 4's f1 differences are near the plotting precision in the low-pressure cells (values ~0.0001–0.002); the log-scale alternative was rejected to keep axes linear and honest.
3. Fig 5's migration whiskers are invisible for deterministic methods (zero seed variance) — that flatness is itself the correct depiction.
4. Fig 6 annotates only three extreme cells to avoid label clutter; full values are in `fig6_tradeoff_source.csv`.
5. Fig 8's f2-reduction fraction uses B1 as the reference (the shared starting placement); fractions vs other baselines are computable from the same source CSVs.
6. Co-residency **duration** is not plotted: Stage 2I.4 established it is structurally invariant across methods under the two-snapshot timeline (a property of the tracker design, not of any method); a figure would misleadingly suggest method differences where none exist.
7. PNG is the presentation artifact; SVG is the scalable/editable version. Both are reproducible; no PDF variant was needed.

## 4. Scope statement

- **No experiments were run, no source algorithms modified** (Stages 2B–2H, Python/ML, NSSAO, f1–f4, adaptive threshold, baselines, experiment design all untouched; raw results hash-verified).
- **No thesis claims**: the words "optimal", "superior overall", "always", and "proves security improvement" do not appear as claims anywhere in this stage; the documented observed trade-offs are: NSSAO has lower f2 across the evaluated cells; NSSAO incurs higher f3; B2 has better f4; all proposed-vs-baseline comparisons are trade-offs; B3 can outperform NSSAO on f1 in D/E cells; co-residency duration is structurally invariant under the current two-snapshot design.

**HARD STOP after Stage 2I.5 respected: figures, tables, and this documentation are complete. No further analysis or writing was started.**
