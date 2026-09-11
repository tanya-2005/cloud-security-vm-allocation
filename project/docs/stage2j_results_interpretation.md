# Stage 2J — Final Results Interpretation & Claims Audit

Status: **COMPLETE (interpretation + claims audit only)** · Date: 2026-09-11
Sources read: `stage_2i1_experiment_design.md`, `stage_2i3_experiment_run.md`, `stage_2i4_results_analysis.md`, `stage2i5_visualization.md`
Evidence used (read-only): `project/results/stage2i3/`, `project/results/stage2i4/`, `project/results/stage2i5/`

**No experiments were re-run, no code or stored results were modified (SHA-256 verified before/after), and no new graphs were created.** All headline claims were independently re-checked against `analysis_summary.csv` and `paired_method_differences.csv` before being restated here; two 2I.4 headline statements required precision corrections (§3, §13).

Convention: all objectives are minimization; paired difference = baseline − NSSAO (positive = NSSAO smaller); f4 = 1 − overall utilization (lower = tighter packing).

---

## 1. Experimental evidence

- **Datasets (2):** Azure V2-derived and Alibaba V2018-derived threat-score populations, read from the frozen Python-pipeline outputs (`azure_to_azure_scores.csv`, 248 entities; `alibaba_to_alibaba_scores.csv`, 67 entities). No synthetic scores.
- **Cells (12):** profiles A (low threat), B (moderate), C (high threat), D (co-residency pressure), E (resource pressure) × both datasets at scale M (24 VMs / 8 hosts; A/C use 10-VM slices; D uses 32 VMs), plus the two approved scale-F cells B-azure-L and B-alibaba-L (48 VMs / 16 hosts).
- **Methods (4):** Proposed/NSSAO; **B1** FFD static (also the shared initial placement); **B2** Best-Fit + f4-oriented local search; **B3** seeded random feasible relocation. **B1–B3 are project-defined baselines**, not from the paper.
- **Seeds:** 5 per cell × method (1–5). **Total: 240 runs**, all completed, 0 failures, 0 duplicates (2I.3), independently re-validated (2I.4).
- **Metrics:** objectives f1 (mean threat × server-risk), f2 (cross-entity co-residency max-threat risk), f3 (active-host cost + migration cost), f4 (1 − overall utilization); migration count/cost (perMigrationCost = 1); co-residency duration (total and high-threat) and episode count as post-hoc metrics from the Stage 2D two-snapshot timeline (t=0 placement, t=1 migration).
- **Fairness (verified from stored fingerprints):** one initial-placement fingerprint per cell across all methods; one evaluator/tracker path; baseline searches never mutated live state (runner invariant held in 240/240 runs).

## 2. Objective-wise findings

**f1 (threat × server-risk)**
- OBSERVED: NSSAO has the lowest median f1 in all 12 cells vs B1 and B2 (paired medians positive everywhere; e.g., C-azure: 0.1425 vs B1 0.2389). vs B3, NSSAO is better in 7 cells and worse in 4 (B-azure-L, D-azure-M, D-alibaba-M, E-alibaba-M), with E-azure-M effectively tied (+0.000009 paired median).
- Consistency: vs B1/B2, 5/5 seeds in every cell; vs B3, seed-level wins flip in the D/E cells (B3 wins up to 4/5 seeds in D-azure/D-alibaba).
- Dataset/profile differences: absolute f1 is largest in C cells (high scores, threshold 0.900/0.775) and tiny in A cells (~0.0001–0.0018).
- Unfavorable: **a seeded random search matches or beats NSSAO on f1 under D/E pressure and in B-azure-L** — NSSAO's f1 advantage is not universal.

**f2 (cross-entity co-residency risk)**
- OBSERVED: **NSSAO has the lowest median f2 in all 12 cells**, 5/5 seeds vs B1 and B2, and 4–5/5 seeds vs B3 (closest: B-alibaba-L vs B2, B2 wins 2/5 seeds). Largest gaps in C: C-azure B1 = 24.2575 vs NSSAO = 1.6219 (paired median 22.64).
- Consistency: strongest, most uniform finding in the study.
- Dataset/profile: directions identical on both datasets; magnitudes differ (Alibaba's B-L cell reaches f2 ≈ 11.75 vs Azure 5.32).
- Unfavorable: under D/E packing pressure the gaps compress (D-azure: 3.85 vs B1 4.81 — a ~20 % reduction, not 10×).

**f3 (defensive cost)**
- OBSERVED: NSSAO's f3 is higher than B1's and B2's in all 12 cells (paired medians −13…−53 vs B1; −2…−16 vs B2). vs B3 it is higher in 11 of 12 cells and **tied at median in E-azure-M (17 vs 17)**, where B3's f3 is actually lower in 3/5 individual seeds.
- Driver (OBSERVED): migration counts — NSSAO migrates most in the 8 B/D/E cells (e.g., B-azure-L: 45 vs B2's 32); in the 4 A/C cells NSSAO migrates 8 vs B2's 10 (fewer than B2) but still pays more total cost than every baseline except that tie.
- Unfavorable: the security result is never free; in E cells the cost gap narrows but does not close vs B1.

**f4 (resource inefficiency)**
- OBSERVED: **B2 has the best (lowest) f4 in 10 of 12 cells (5/5 seeds) and ties NSSAO in both E cells** (all methods hit the capacity ceiling f4 = 0.34375; in E-alibaba B2 is better in 1/5 seeds and tied in 4/5, never worse). B1 is worst in every cell.
- Precision correction to 2I.4: "B2 better in all 12" → **strictly better in 10, tied (never worse) in 2**.
- Unfavorable: NSSAO packs less tightly than a pure efficiency search in every non-E cell (e.g., B-M: 0.520 vs 0.438).

**Migration count/cost and co-residency duration** — see §6–§7.

## 3. NSSAO vs baselines (separately)

**NSSAO vs B1 (FFD static):**
- Wins: f2 in 12/12 cells (5/5 seeds); f1 in 12/12; f4 in 12/12 (B1 never tightens its packing).
- Loses: f3 in 12/12 (B1 pays zero migrations); migration count 12/12 (0 vs 8–45).
- Verdict: trade-off — risk reduction bought with cost. **All pairs are TRADE-OFF; neither dominates the other.**

**NSSAO vs B2 (Best-Fit + f4 local search):**
- Wins: f2 in 12/12 (5/5 seeds; min gap 0.0051 in A-azure, max 9.21 in C-azure); f1 in 12/12 (small margins).
- Loses: f3 in 12/12; f4 strictly in 10/12, tied in the 2 E cells (never better than B2 on f4 anywhere).
- Verdict: the clearest efficiency-vs-security contrast; B2 is the f4 champion, NSSAO the f2 champion.

**NSSAO vs B3 (seeded random relocation):**
- Wins: f2 in 12/12 on medians (4–5/5 seeds); f1 in 7/12; f3 in 11/12.
- Loses: f1 in 4/12 (B-azure-L, D-azure-M, D-alibaba-M, E-alibaba-M; E-azure-M tied); f4 ties in E cells; f3 median tie in E-azure-M with B3 lower in 3/5 seeds there.
- Verdict: NSSAO's f2 edge over random search is consistent, but a seeded random search is a genuine competitor on f1 under pressure — reported, not explained away.

**Preserved summary (with the two precision corrections):**
- NSSAO has lower f2 in all 12 cells. ✅ (unchanged)
- NSSAO has higher f3 in **11 of 12 cells; tied with B3 at median in E-azure-M**. (corrected from "all 12")
- B2 has better f4 than NSSAO in **10 of 12 cells; tied in the 2 E cells; never worse**. (corrected from "all 12")
- B3 can beat NSSAO on f1 in the D/E cells **and in B-azure-L**. ✅ (range widened slightly)
- **All 36 proposed-vs-baseline comparisons are trade-offs; NSSAO is neither universally dominant nor dominated.** ✅ (re-verified)

## 4. Dataset comparison

- **Azure V2-derived (OBSERVED):** NSSAO lowest f2 in all 6 Azure cells (largest gap C-azure: 1.62 vs 24.26); highest f3 in all 6; B2 best f4 in 4/6, tied in E-azure; B3 wins f1 in D-azure, E-azure is a per-seed split.
- **Alibaba V2018-derived (OBSERVED):** identical directions — NSSAO lowest f2 in all 6 (C-alibaba: 1.30 vs 19.82); highest f3 in all 6 (B3 tie never occurs here; E-alibaba f3 20 vs B3 17); B2 best f4 in 6/6 medians (tied 4–5/5 seeds in E); B3 wins f1 in D-alibaba, E-alibaba, and on B-alibaba-L… (f1 vs B3: NSSAO wins B-alibaba-M/L medians; the B3 f1 wins are D/E + B-azure-L on the Azure side only at L).
- **Not claimed:** generalization to all cloud workloads, cloud providers, or other trace populations. Only these two evaluated datasets are covered; they happen to produce direction-consistent results with different magnitudes (and a dataset-specific adaptive threshold per cell: 0.900 Azure-C vs 0.775 Alibaba-C).

## 5. Profile analysis

- **A — low threat:** OBSERVED near-identical f1 across methods (0.00005–0.0018); f2 separates methods in the expected order (NSSAO 0.0000/0.0102 best; B1 0.0116/0.2063 worst). Distinguishable on f2 only — consistent with the design intent "methods differentiate least when pressure is low".
- **B — moderate:** clear f2 ordering (NSSAO 1.71/2.92 vs B1 3.53/5.86 at M); scales cleanly to L (10.90/16.92 for B1); migration differences emerge (NSSAO 20–45 vs B2 16–32).
- **C — high threat:** the strongest differentiation in the study: B1's f2 is ~15× NSSAO's (24.26/19.82 vs 1.62/1.30); f1 spreads widest (0.24 vs 0.14 Azure). Design intent "threat-awareness matters most here" is OBSERVED.
- **D — co-residency pressure:** forced ~80 % utilization compresses everything: f2 gaps shrink to 4.81→3.85 (Azure) and 8.09→7.23 (Alibaba); f4 ordering inverts (B2 0.344 < NSSAO 0.367); B2 migrates least (8). OBSERVED: co-residency pressure narrows the security advantage and exposes the packing price.
- **E — resource pressure:** capacity ceiling: all relocation methods tie at f4 = 0.34375 (only B1 sits higher); f1 differences collapse to ≤ 0.0013 (and B3 wins 4 of 5 relevant comparisons); f3 still separates (B1 3 < B2 8 < B3 13–17 ≤ NSSAO 17–20). OBSERVED: under extreme capacity pressure the objectives converge and only cost reliably distinguishes methods.
- No explanations are offered beyond the mechanism recorded in the design (slice composition, fleet utilization, and threshold per cell — all in the 2I.3 manifest).

## 6. Migration and defensive cost

- OBSERVED relationship: migration count ↔ migration cost is 1:1 (perMigrationCost = 1) in all 240 rows, and f3 = activeHosts×1.0 + migrations×1.0 holds by construction (validated per run).
- OBSERVED: NSSAO's f2 reduction relative to B1 is accompanied by f3 increases of ×5.3–×7.9 vs B1 (e.g., C-azure: 2 → 16; B-azure-L: 8 → 61) and migration counts of 8–45 where B1 uses 0.
- OBSERVED cross-cell pattern: the more room NSSAO has (A/C, 10 VMs on 8 hosts), the smaller its relocation count (8) relative to B2 (10); the tighter the packing (B/D/E), the more NSSAO must migrate to separate threats (up to 45).
- **Explicit statement:** the proposed method **trades additional defensive cost and migrations for lower modeled co-residency risk**. In f4 terms it also accepts looser packing than B2 in 10/12 cells. Neither the magnitude of the risk reduction nor the acceptable price is settled by this data — that is a policy judgment outside the experiment.

## 7. Co-residency duration

- OBSERVED: total co-residency duration and high-threat duration are **numerically identical across all four methods within every cell** (A = 29; B-M = 68; B-L = 136; D = 80; E = 84 time units; high-threat = 0 except C-azure 8, C-alibaba 1, B-alibaba-L 5). Episode counts DO differ by method.
- LIMITATION (not a finding about real security): this equality is a property of the **two-snapshot experimental timeline** (placement snapshot at t=0, migration snapshot at t=1) in which every method's VMs occupy the fleet for the same interval; duration therefore cannot distinguish methods here. **This must NOT be presented as evidence that the methods have equal real-world security behavior** — it reflects measurement-design insensitivity, not equivalence of protective value.

## 8. What the data supports

Defensible, evidence-scoped claims:

1. The evaluated NSSAO configuration produced **lower modeled co-residency risk (f2) than the three evaluated baselines across all 12 experiment cells** (5/5 seeds vs B1 and B2; seed-matched medians vs B3 in 12/12).
2. The reduction in modeled co-residency risk was **accompanied by higher defensive cost (f3) in 11 of 12 cells (median tie in the twelfth) and more migrations in the 8 B/D/E cells**.
3. **Best-Fit + f4-oriented local search achieved lower resource inefficiency (f4) than NSSAO in 10 of 12 cells and matched it in the remaining 2**, never being worse.
4. All 36 proposed-vs-baseline comparisons are **trade-offs**: NSSAO is better on some objectives and worse on others in every cell; no dominance in either direction.
5. **A seeded random relocation baseline matched or beat NSSAO on f1 in 4 of 12 cells** (D/E pressure and B-azure-L), showing the f1 advantage of NSSAO over B1/B2 is not robust against random search under pressure.
6. Every method started from the **same fingerprint-verified FFD initial placement** in each cell, used the same evaluation path, and produced **seed-reproducible results** (32/32 re-run matches); B1 performed zero migrations in all runs.
7. The Azure-derived and Alibaba-derived populations produced **direction-consistent results with different magnitudes** across all 12 cells.
8. Under capacity ceilings (profile E), objective differences between relocation methods largely collapse; only cost remains clearly separated.

## 9. What the data does NOT support

The following are explicitly **prohibited** as conclusions from this study:

- Global or local **optimality** of NSSAO (front-1 membership ≠ optimality; no dominance was even observed).
- **Universal superiority** ("NSSAO is the best method") — contradicted by the f3/f4 results.
- **Proven convergence** of the NSSAO search.
- **Security improvement in a real cloud** — the environment is a simulator with modeled risk abstractions.
- **Prevention of actual side-channel attacks** — no attacks were simulated; f2 is a modeled risk metric.
- **Physical co-residency validation** — the public traces provide no host-placement ground truth; CloudSim supplies simulated co-residency.
- **Exact reproduction of the paper** — NSSAO here is an adapted implementation with documented deviations.
- **Statistical significance** — n = 5 seeds, descriptive statistics only, no hypothesis tests.
- **Generalization** beyond the two evaluated datasets (Azure V2, Alibaba V2018) and the 12 tested cells.
- Any claim that **co-residency duration was improved** — duration was invariant across methods under the two-snapshot timeline (§7).

## 10. Threats to validity / limitations

(Existing documented limitations; none invented.)

1. Public traces provide **no physical host / co-residency ground truth**; scores are inputs, not placement facts.
2. **One workload entity → one simulated VM** abstraction; multi-VM entities are unmodeled.
3. **Project-specific risk abstractions** (threat score, server-risk model, f2's max-per-host aggregation) — other formulations could rank methods differently.
4. **Vulnerability / network components unavailable** in the risk model.
5. **Instantaneous migration abstraction** — no downtime, no live-migration cost beyond a unit cost.
6. **Two-snapshot duration limitation** — co-residency duration cannot distinguish methods (§7).
7. **Small simulated scale** (≤ 48 VMs / 16 hosts).
8. **Stage 2G repair instrumentation unavailable** — repair counts/rates recorded as NA; NSSAO's repair/search-distribution behavior is unmeasured (see Stage 2G.3 audit).
9. **Dataset feature/unit differences** between Azure V2 and Alibaba V2018 telemetry complicate magnitude comparisons.
10. **Adaptive threshold boundary behavior** — several cells land exactly on the [0.5, 0.9] bounds; threshold sensitivity is untested.
11. NSSAO search-distribution limitations documented in the Stage 2G audit (adapted melting schedule, discretization, stall limit) apply unchanged here.

## 11. Research questions (answers strictly evidence-scoped)

- **RQ1 (threat exposure, f1):** Supported in part. Evidence: f1 paired medians, 240 rows. NSSAO achieved lower f1 than B1/B2 in 12/12 cells, but **lost or tied vs B3 in 5/12 cells**. Qualification: margins are small; the D/E findings are unfavorable and must be reported with the favorable ones.
- **RQ2 (co-residency risk, f2):** Supported. Evidence: f2 medians and paired differences; 12/12 cells, 5/5 seeds vs B1/B2. Qualification: modeled risk in a simulator; gap compresses under D/E pressure; no significance testing.
- **RQ3 (security/resource/cost trade-off):** Supported. Evidence: f3/f4 paired differences; dominance verdicts (36/36 TRADE-OFF). The security gain is paid for in cost (11/12 cells) and packing efficiency (B2 better or equal in 12/12). Qualification: the "acceptable" exchange rate is a judgment, not a measurement.
- **RQ4 (migration cost of security):** Supported. Evidence: migration counts (0 for B1; 8–45 NSSAO; 4–35 B2/B3), 1:1 cost mapping. NSSAO's f2 reduction required 8–45 migrations per cell; in 8/12 cells it migrates the most. Qualification: instantaneous-migration abstraction; unit cost is a design constant.
- **RQ5 (consistency across Azure/Alibaba populations):** Supported descriptively. Evidence: per-dataset direction agreement on every objective in every profile cell. Qualification: two datasets only, different magnitudes and thresholds; no generalization claim.

## 12. Draftable thesis results narrative (NOT the final thesis)

> The evaluation comprised 240 simulated placement runs: four placement methods — the proposed threat-driven NSSAO pipeline and three project-defined baselines (static first-fit-decreasing, best-fit with utilization-oriented local search, and seeded random relocation) — applied to twelve scenario cells derived from two real anomaly-score populations (Azure V2 and Alibaba V2018) under five threat-pressure profiles, with five seeds per configuration. All methods began from an identical fingerprint-verified initial placement and were evaluated through a single shared measurement path; every run was seed-reproducible.
>
> Across all twelve cells, the proposed method produced the lowest modeled cross-entity co-residency risk of the four methods (for example, median risk 1.62 versus 24.26 for static first-fit in the high-threat Azure cell), with the reduction present in every seed against the two deterministic baselines. This reduction was never free: the proposed method incurred the highest or joint-highest defensive cost in the evaluated cells — up to 45 migrations where the static baseline performed none — and packed resources less tightly than the utilization-oriented baseline in ten of twelve cells, matching it only when capacity was the binding constraint. Consequently, every proposed-versus-baseline comparison constituted a trade-off rather than a dominance relation.
>
> Two results qualify the approach's advantages. First, under co-residency and resource pressure (profiles D and E) the margins on all objectives narrowed sharply, and a seeded random relocation search matched or exceeded the proposed method's threat-exposure objective in four cells. Second, co-residency duration — a post-hoc metric — was identical across methods under the study's two-snapshot measurement design, and therefore cannot be used to distinguish the methods; the risk reduction achieved is visible in the risk-weighted metric, not in duration. The findings are bounded by the simulated environment, the small experiment scale, and the absence of physical co-residency ground truth in the public datasets, and they are descriptive (five seeds per configuration) rather than inferential.

*(This narrative contains no optimality, superiority, real-world-security, or significance claims; the negative findings are integral parts of it.)*

---

## 13. Claims audit ledger

| Claim (as stated in earlier stages) | Verdict | Note |
|---|---|---|
| NSSAO lowest f2 in all 12 cells | **Validated** | re-checked from medians + paired rows |
| NSSAO highest f3 in all 12 cells | **Qualified** | strict in 11/12; median tie with B3 in E-azure-M (B3 lower in 3/5 seeds there) |
| B2 better f4 in all 12 cells | **Qualified** | strict in 10/12; tied in both E cells; never worse |
| B3 beats NSSAO on f1 in D/E | **Validated & widened** | also B-azure-L; E-azure-M is a near-tie (+9e-6 paired median) |
| All 36 comparisons are trade-offs | **Validated** | dominance table re-checked |
| B1 migrations = 0 everywhere | **Validated** | 240/240 rows |
| Duration identical across methods per cell | **Validated** | structural property of the two-snapshot design |
| Same initial placement per cell; seed-reproducible | **Validated** | fingerprints; 32/32 re-run matches |
| "NSSAO migrates most in every cell" (2I.4 phrasing) | **Qualified** | most in 8/12 (B/D/E); in A/C cells B2 migrates more (10 vs 8) |

**HARD STOP after Stage 2J respected: the interpretation and claims-audit document is complete. No code, no stored data, and no graphs were modified or created; no experiments were re-run.**
