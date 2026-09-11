# Stage 2G.3 — NSSAO Audit

**Status: AUDIT ONLY — no files modified**

**Date: 2026-09-11**

Scope: the Stage 2G implementation (`NssaoConfig`, `Pareto`,
`PlacementDecoder`, `NssaoOptimizer`, `Stage2gNssaoValidation`) audited
against `stage_2g1_nssao_design.md` and the NSSAO equations/Algorithm 1
recovered verbatim from the paper during Stage 2G.1. No code was changed
by this audit (verified: `git status` shows zero modified tracked files;
the temporary jshell script used for evidence was deleted afterwards).

Classification per item: **PASS** (matches Stage 2G.1 design) /
**ADAPTATION** (intentional, documented in the approved design) /
**ISSUE** (implementation does not match approved design).

---

## 1. Paper-Fidelity Audit (AUDIT 1)

| Item | Evidence (code) | Classification |
|---|---|---|
| Eq. (21)/(23) — intermediate step `X_i^d = ρ·(X−X_i) + (1−ρ)·(X̄−X_i)` | `NssaoOptimizer` main loop: `dir[k] = rho*(bestPos[k]-xi[k]) + (1-rho)*(centroid[k]-xi[k])` — form identical for both sub-populations, differing only in ρ range, exactly as the paper's Eq. 21 vs 23 | **PASS** |
| Eq. (22) — exploration `X_i(t+1) = X_best + A·dir` | `child[k] = bestPos[k] + a*dir[k]` for `i ∉ P_b` | **PASS** |
| Eq. (24) — exploitation `X_i(t+1) = M·X + A·dir` | `child[k] = melting*bestPos[k] + a*dir[k]` for `i ∈ P_b`; note the paper writes `M·X(t)` and X(t)=current best, so M multiplies the *best* vector, not the individual's own — implemented as designed | **PASS** |
| A(t) Gaussian, mean 0 var 1 | `random.nextGaussian()` per component | **PASS** |
| ρ1 ∈ [0,1] (exploration), ρ2 ∈ [−1,1] (exploitation) | `i < exploitationSize → rho = U[−1,1] (ρ2); else U[0,1] (ρ1)`; one draw per individual, branch decided before the draw (fixed RNG stream) | **PASS** (bug fixed in 2G.2 was the ranges being swapped; current code correct) |
| Dual-population structure | first `exploitationSize` indices = index_b (P_b), remainder = index_a (P_a); each individual updated by exactly one of Eq. 22 / Eq. 24 | **PASS** |
| Population update order | all N individuals updated first, evaluated, then N+N→N environmental selection (Algorithm 1 line order: update → evaluate → Select) | **PASS** |
| P_a/P_b schedule | `|P_b|(t) = round(N/2·(1−t/t_max))`, `|P_a| = N − |P_b|` in `NssaoConfig.exploitationSize/explorationSize` | **ADAPTATION** (split even at t=0 is paper-faithful; the linear decay formula is the Stage 2G.1 documented assumption — the paper states only "P_b gradually decreases") |
| Melting factor / annealing | `M(t) = M_max − (M_max−M_min)·t/t_max`, defaults 1.0→0.1 | **ADAPTATION** (paper's degree-day Eq. reference is unrecoverable from the PDF; linear anneal is the Stage 2G.1 documented assumption) |
| Termination / stall | loop `while t < t_max`; after each iteration, front-1 objective-vector set compared (exact, sorted, `Double.compare`) to previous; `stall >= stallLimit` (3) → stop | **PASS** (mechanism paper-faithful; stallLimit value is a Stage 2G.1 documented adaptation) |
| "best" X_best/X(t) definition | lexicographic among front 1: larger CD, then lower f1, then lower index | **ADAPTATION** (paper does not define "best" in a multi-objective setting) |

No SSA producer/scrounger/vigilance roles exist anywhere in stage2g
(grep-verified) — correct: NSSAO as printed has none, and inventing them is
forbidden.

---

## 2. Discretization Audit (AUDIT 2)

Chain verified in `PlacementDecoder.decode`:

```
real position x_k  →  Math.rint(x_k)  →  clamp(·, 0, H−1)  →  vmToHost[vm_k] = hostIndex
```

- `Math.rint` = banker's rounding (deterministic, no positive bias);
  clamp applied defensively after rint and after every optimizer update. ✓
- **Multiple VMs can share a host:** yes — nothing in decode prevents
  collisions; check T4 decodes four VMs onto one host and the experiment
  shows heavily co-resident candidates surviving in front 1. ✓
- **Every VM gets exactly one host:** decode starts from the full current
  mapping (pinned VMs) then sets exactly one entry per movable VM. A
  `CandidatePlacement` is a `Map<Integer,Integer>`, so by construction no
  VM is missing and none appears twice. ✓
- **No invalid host ID survives decoding:** host index is clamped to
  `[0, H−1]` and `CandidatePlacement`'s host index is built from the
  supplied host list; a position outside bounds cannot produce an unknown
  host. ✓
- **Repair is deterministic:** hosts processed in ascending ID; victim =
  movable VM with smallest CPU demand on the overloaded host (ties: lower
  VM ID); destination = feasible host minimizing resulting CPU utilization
  (ties: lower host ID). No RNG involved. Re-running the same decode twice
  yields the same mapping (T6 and the audit experiment re-decodes all final
  positions with identical results). ✓
- **Rejection is explicit:** when no feasible destination exists for a
  needed move, `DecodeResult` carries no candidate, the optimizer counts
  `rejectedDecodeCount`, and the slot falls back to the current placement's
  objectives — never a silent accept, never a penalty. ✓
- **Does repair introduce a search bias?** Yes — a *strong but intended*
  one, documented here: repair always moves VMs toward *lower-utilization,
  lower-ID* hosts, which is well-aligned with f4 (packing) and f1 (utilized
  hosts are riskier). In the audit experiment 5/12 initial and 8/12 final
  decoded candidates were repaired — repair is doing significant work in
  this scenario, so the optimizer's effective search distribution is
  partially shaped by the repair heuristic rather than by NSSAO positions
  alone. Consequence: candidates' *positions* can decode-and-repair to the
  same placement (plateau), and front 1 inflates with duplicates (see §4).
  This is defensible (feasibility must be guaranteed; no penalty allowed)
  but must be stated as a limitation, not hidden. **Flagged, no change.**

---

## 3. Pareto Audit (AUDIT 3)

- **All four objectives MINIMIZE:** `Pareto.dominates` uses
  `va > vb → false` / `va < vb → strictly better`. ✓
- **Dominance on RAW values:** `dominates` reads `ObjectiveValues` getters
  directly; no normalizer call anywhere in the dominance/NDS path. ✓
- **Normalization NOT used for dominance:** `ObjectiveNormalizer` is
  referenced only inside `crowdingDistance` (per-front, per-objective
  scaling for the diversity metric). Dominance, NDS ranks, and
  environmental selection order within fronts are all raw-value-based. ✓
- **No weighted-sum fitness:** grep-verified — no scalar aggregation
  exists in stage2g. Behaviorally proven by T8: a=(0.1,0,10,0.5) vs
  b=(0.2,0,3,0.5) — neither dominates, both land in front 1 (any equal-
  weight scalarization would rank one above the other and eliminate one). ✓
- **Fast NDS correctness:** hand-computed 3-front example passes (T10):
  front1={A,C}, front2={B}, front3={D}, full partition. During Stage 2G.2 a
  bookkeeping bug (missing `dominationCount[j]++` in the i-dominates-j
  branch) collapsed everything into front 1; it was caught by T10 and
  fixed. Current code registers both directions correctly:
  `dominated[i].add(j); dominationCount[j]++` and symmetric branch. ✓
- **Domination counts updated correctly:** decrement-and-promote loop over
  dominated lists is the standard Kahn-style NDS; equal vectors increment
  nothing (neither dominates) and both remain non-dominated. ✓
- **Crowding distance:** per-front, per-objective min-max normalization
  (equal-range → 0.5 via Stage 2F normalizer), boundary members = +∞,
  interior = sum of normalized neighbor gaps; deterministic tie order.
  Matches the design and the Deb form. ✓
- **Environmental selection preserves N:** fills fronts front-by-front to
  N; splits the overflow front by descending CD (ties: lower index). T12:
  final population N=12, fronts cover exactly 12. ✓

One structural caveat (not a correctness issue): crowding distance treats
**duplicate placements** (distinct positions decoding to the same mapping,
hence identical objective vectors) as distinct solutions at zero mutual
distance, which further inflates front 1 (§4). **Flagged, no change.**

---

## 4. Final-Solution Audit (AUDIT 4)

**What `run()` returns:** the final population (after the last
environmental selection) plus its fronts. `getFirstFront()` exposes front 1
members. There is **no single "final solution" object** — the caller
receives the whole non-dominated set.

- **Can the returned solution be dominated by another retained solution?**
  No, for members of front 1: environmental selection keeps the entire
  first non-dominated front, so no retained survivor dominates any front-1
  member. (Individuals in deeper fronts ARE dominated by front-1 members —
  by definition — and are retained only for diversity.)
- **Guaranteed feasible?** Yes, for every returned individual with a
  placement: decode guarantees structural validity, repair guarantees
  capacity feasibility, and Stage 2F validation is the final gate (T16
  verifies all front-1 members). Edge case: if a decode had been rejected,
  that individual carries `placement == null` and the *current placement's*
  objectives — such an individual must not be handed to any later stage as
  a "solution" (its placement is null, so this is detectable, and
  `rejectedDecodeCount` reports it; 0 in all runs here).
- **Selected from the final non-dominated set?** Front 1 is recomputed from
  the final population at the end of `run()` — yes.
- **Tie-break rule among multiple Pareto-optimal solutions:** the optimizer
  itself does not pick one. The only tie-break inside the loop is the
  *documented lexicographic rule* for choosing X_best/X(t) (larger CD,
  then lower f1, then lower index) — that is a **guidance choice for the
  search**, not a final-answer preference. A final single-solution choice
  (which solution to actually migrate later) is **not implemented** —
  correctly deferred to the future integration stage, where it belongs.
- **Is the rule arbitrary scalar preference?** No weighted/scalarized
  selection exists. The CD-based X_best rule is a diversity-preserving
  heuristic, documented in Stage 2G.1 §8, not a scalarized fitness. **No
  flag beyond the already-documented adaptation.**

---

## 5. Objective Integration Audit (AUDIT 5)

- Stage 2G contains **zero** objective arithmetic: `NssaoOptimizer` and
  `PlacementDecoder` only call `ObjectiveEvaluator.evaluate(...)`
  (`grep`-verified: no `threatScore *`, no `perHostCost *`, no risk formula
  in stage2g). ✓
- **Bit-identical re-evaluation:** experiment re-evaluated population
  member 0 with a fresh evaluator call → `Double.compare == 0` on f1 and
  f3 (and T7 covers all four across the population). Deterministic
  evaluation makes bitwise equality the correct comparison. ✓
- **Formula identity (from Stage 2F validation, unchanged):**
  - f1 = mean(threat × clamp(base + w·candidateUtil)) — threat×server-risk ✓
  - f2 = Σ max(threatA, threatB) over cross-entity candidate co-resident
    pairs; same-entity pairs excluded (Stage 2F T3, V=1.7 vs Y=0.0) ✓
  - f3 = activeHosts×perHostCost + migrationCount×perMigrationCost,
    migration counted against current live mapping ✓
  - f4 = 1 − (avgCPU + avgMem)/2 across ALL hosts incl. empty ✓
- Status quo reference vector reproduced exactly in the audit experiment:
  (f1, f2, f3, f4) = (0.1228125, 0.9, 2.0, 0.625) — the Stage 2F candidate X
  values, confirming the same evaluator produces the same numbers inside
  the optimizer loop. ✓

---

## 6. CloudSim Purity Audit (AUDIT 6)

- **No physical migration:** stage2g never imports or calls
  `VmMigrationService` (grep-verified; the only mention is a comment). No
  `allocateHostForVm`/allocation-policy calls. ✓
- **No guest-list modification:** the optimizer and decoder never call
  `getGuestList()` at all (grep-verified: guest-list references exist only
  in `Stage2gNssaoValidation` — scenario setup adds VMs to build the live
  state, and the fingerprint reads it). ✓
- **No permanent state alteration:** the audit experiment fingerprinted all
  host→VM guest lists before and after a full optimization run —
  identical (`PURITY fingerprint unchanged=true` in the run log; T15 does
  the same in the shipped harness). ✓
- Every candidate is a hypothetical `CandidatePlacement`; evaluation is
  read-only (Stage 2F purity, re-verified there by T8). ✓

---

## 7. Optimization-Behavior Evidence (AUDIT 7)

Deterministic experiment (jshell, seed 42, N=12, t_max=6, the Stage 2F/2G
scenario: H0 2000/4096, H1 1000/2048, H2 1000/2048; A,B@H0; C,D@H1; all
movable; threat 0.9/0.8/0.2/0.2). The initial population was replicated
bit-exactly by replaying the optimizer's RNG draw order.

**Status quo (current placement):** f1=0.122813, f2=0.900000, f3=2.000000,
f4=0.625000.

| Metric | Initial population | Final population |
|---|---|---|
| Feasible / repaired / rejected | 12 / 5 / 0 | 12 / 8 (re-decoded) / 0 (`rejectedDecodeCount`=0) |
| Front-1 size | 5 of 12 | 12 of 12 |
| f1 min / mean / max | 0.110156 / 0.134766 / 0.170625 | 0.110156 / 0.130547 / 0.170625 |
| f2 min / mean / max | 0.0 / 0.933333 / 1.700000 | 0.0 / 0.600000 / 0.900000 |
| f3 min / mean / max | 2.0 / 4.083333 / 7.0 | 2.0 / 3.166667 / 4.0 |
| f4 min / mean / max | 0.500 / 0.572917 / 0.625 | 0.500 / 0.572917 / 0.625 |

Final front-1 raw vectors (sorted; duplicates are distinct positions
decoding to the same placement):
`(0.11015625, 0.0, 4.0, 0.5625)×4`, `(0.1228125, 0.9, 2.0, 0.625)×5`
= status quo, `(0.170625, 0.9, 4.0, 0.5)×3`.

**Interpretation (population evidence, not a convergence claim):**
- The front-1 objective-vector set **changed** from the initial population
  (`vectorSetsEqual = false`).
- **Population means improved on all three trade-off axes without losing
  the extremes:** f2 mean 0.933→0.600 with min still 0.0; f3 mean 4.08→3.17
  with min still 2.0; f1 mean 0.1348→0.1305 with min still 0.11015625
  (theoretical best for this scenario, already present initially).
- **No solution strictly dominates the status quo** —
  (0.1228125, 0.9, 2.0, 0.625) is itself front-1 (best f1·f3·f4 combination
  at its f2 level), and (0.11015625, 0.0, 4.0, 0.5625) trades f2↓ for
  f3↑. This is a genuine Pareto *trade-off surface*, and the optimizer's
  value here is revealing/retaining the trade-off set, not beating the
  status quo on every axis.
- **Front-1 size 12/12** means the final population is entirely
  non-dominated: selection is no longer eliminating anyone (plateau +
  duplicates). With t_max=6 and N=12 this is expected behavior, NOT
  evidence of convergence.
- **Explicitly NOT claimed:** convergence, global optimality, or that the
  front cannot improve with larger N / more iterations. The extremes were
  already reachable in the initial random population for this tiny
  scenario; larger instances may behave differently.

Also recorded: the seed-7 run completed 6 iterations with 0 rejected
decodes (different trajectory, see §8).

---

## 8. Seed / Determinism Audit (AUDIT 8)

- **Same seed (42) twice:** identical iterations (6), identical fronts
  (`equals`), identical positions for all 12 members (bitwise array
  equality), identical rejectedDecodeCount. Also matches the shipped
  harness's T13 (two full runs in one process) — and the two separate JVM
  invocations of Stage2gNssaoValidation during Stage 2G.2 produced
  identical 17/17 results. ✓
- **Different seed (7):** positions differ (randomness IS seed-controlled),
  front-1 *set* happened to remain equal-comparable in this tiny scenario
  while trajectories differ. Per the contract, different seeds are only
  *required* to potentially differ — confirmed. ✓
- Determinism chain: single seeded `Random` → fixed draw order (sub-
  population membership decided before the ρ draw) → deterministic decode/
  repair → deterministic evaluator → deterministic NDS/CD/sort tie-breaks.
  ✓

---

## 9. Issues Found (with severity; NOT fixed in this stage)

| # | Issue | Severity | Notes |
|---|---|---|---|
| I1 | Repair shapes the effective search distribution (5–8 of 12 candidates repaired; repair prefers low-utilization, low-ID hosts) — NSSAO positions alone do not fully determine placements | **Medium** | Intended consequence of the approved repair-first strategy; documented here as a limitation. Revisit if repair rates stay high at realistic scale |
| I2 | Front-1 inflation: duplicates (same placement from distinct positions) and identical objective vectors all enter front 1 (final front-1 = 12/12 with only 3 distinct vectors); crowding distance treats duplicates as distinct | **Medium** | Harmless to correctness; wastes population slots and weakens CD discrimination. Deduplication could be considered in a later stage — NOT done here |
| I3 | No single final-solution selection exists (by design); callers get the front. If a later stage naively takes `getFirstFront().get(0)`, it receives an arbitrary (index-ordered) member | **Low** | Deferred intentionally to the integration stage; document the deferral so the future stage chooses a rule explicitly |
| I4 | Rejected-decode fallback substitutes the current placement's objectives, biasing those slots toward the status quo | **Low** | Never triggered in any run (rejectedDecodeCount=0); only reachable in pathological scenarios (aggregate demand > aggregate capacity) |
| I5 | Paper parameters (N=2000) vs project default (N=40) and validation-scale scenario mean behavior at realistic scale is untested | **Low** | Explicitly deferred to the experiment stage; no claim made |
| I6 | Melting-factor formula, stall limit, and P_b schedule are assumptions (paper unspecified) | **Low** | Already documented as ADAPTATION in Stage 2G.1; restated here for completeness |

No **High**-severity issues found. All correctness-critical checks
(dominance, NDS, selection size, feasibility, purity, determinism) pass
with hand-computed or replicated evidence.

---

## 10. Ready for Stage 2H?

**Yes, conditionally.** The NSSAO core matches the approved Stage 2G.1
design (every paper-faithful item PASSes; every deviation is a documented
ADAPTATION), is pure with respect to live CloudSim state, is fully
deterministic under a seed, and exclusively reuses Stage 2F evaluation.
The flagged items I1/I2 are search-quality concerns, not correctness bugs;
I3 must be resolved *by the integration stage itself* when it defines how a
single solution is chosen from the returned front. Nothing here requires
re-opening Stage 2G.2 before Stage 2H.

Suggested (non-binding) inputs for Stage 2H: explicit final-solution rule
(e.g., lexicographic on (f2, f3, f1, f4) or CD-based), optional front
deduplication, and reporting of repair rates alongside any results.

---

## Thesis-Claim Hygiene (AUDIT 9 summary)

Until experiments exist, the thesis must NOT claim: exact reproduction of
the paper; globally optimal solutions; proven convergence; security
improvement; superiority over baselines. Defensible current claims are
limited to: faithful-to-design implementation of the paper's NSSAO
mechanism with documented adaptations; correct Pareto machinery (verified
against hand-computed examples); reproducible (seeded) optimization over
hypothetical placements with zero live-state mutation; and the
population-level observations in §7 for this specific scenario. Full list
in §9 of the audit document body above (section "Thesis-Claim Hygiene").

---

**HARD STOP — AUDIT COMPLETE. No files modified. Stage 2H not implemented.**
