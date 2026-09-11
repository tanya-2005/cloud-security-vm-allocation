# Stage 2G.1 — NSSAO Design / Inspection Checkpoint

**Status: DESIGN ONLY — NO CODE WRITTEN**

**Date: 2026-09-11**

**Hard stop honored:** Stage 2F code is untouched (git status shows only the
pre-existing untracked stage2b–2f directories; nothing modified). No Java
files were added or modified by this stage. The only file produced is this
document. No NSSAO code and no optimization code exist after this stage.

---

## 0. Source Basis and Honesty Note

The analysis below is grounded in the text of the local copy of the paper
(`project/A_User_Threat-Driven_Multi-Objective_Adaptive_VM_Allocation_
Framework_for_Cloud_Security.pdf`, IEEE TIFS Vol. 21, 2026, Yang et al.),
which I extracted directly from the PDF content streams. Equation text,
Algorithm 1, and the implementation-details paragraph were recovered
verbatim (modulo ligature artifacts). Where the paper does NOT specify a
detail, this document says `NOT SPECIFIED` — nothing is invented, per the
stage contract.

**Recovered verbatim from the paper:**

- Initialization: binary solution matrix, unfolded row-by-row, random 0/1.
- Five evaluated objectives (Eq. 20): `f_ORS`, `f_Srisk`, `f_Φ`,
  `f_Ctotal`, `f_(−RU)`.
- Pareto dominance definition and iterated non-dominated sorting into
  Pareto levels; crowding distance for diversity.
- Exploration update (Eq. 21–22) and exploitation update (Eq. 23–24),
  transcribed in Section 1 below.
- Dual-population split (even), with P_b shrinking and P_a growing over
  iterations.
- Algorithm 1 skeleton (transcribed in Section 1.4).
- Parameters: population size 2000, max iterations 10, search bounds
  `[0, P−1]` per decision variable, P = 160 servers; solution dimension =
  number of migrated VMs; Azure dataset, ~30,000 VMs.

**NOT SPECIFIED by the paper (gaps we must fill as documented adaptations):**

1. **Eq. (10) melting factor** — the algorithm references `M ← Eq. (10)` in
   Algorithm 1, but the printed Eq. (10) belongs to the *initial placement*
   section (high/normal VM classification). The degree-day melting-rate
   formula for M is not present in the extracted text. → We must define M
   ourselves (adaptation, see §4.5).
2. **Discretization/rounding rule** — solutions are real-valued vectors in
   `[0, P−1]`, but the paper never states how a continuous position becomes
   a discrete server index (round? floor? probabilistic?). → §5.
3. **Infeasible-solution handling** — the paper states the constraint
   ("server must have sufficient remaining CPU/memory") but never says how
   violations are handled inside the loop. → §6.
4. **P_a/P_b size schedule** — "exploitation gradually decreases while
   exploration correspondingly grows" — no equation. → §4.4 (linear swap
   assumed, flagged as assumption).
5. **Role names** — the paper's NSSAO has NO producer/scrounger/vigilance
   roles (those belong to the original SSA sparrow algorithm, cited only as
   ref [29], whose text is not in this PDF). NSSAO = NDS + a dual-population
   snow-ablation optimizer. → Our design follows the NSSAO paper, not SSA.
6. **Crowding-distance formula** — used but not printed; standard Deb NSGA-II
   form is assumed (flagged).
7. **"Best individual" `X_best`** — referenced in Eq. 21/23 but never defined
   (best by what, in a multi-objective setting?). → §8 (NDS+CD lexicographic
   choice, flagged as adaptation).

---

## 1. Paper NSSAO Algorithm Summary

### 1.1 Solution representation (paper-faithful)

> "each potential post-migration VM allocation is encoded as a binary
> solution matrix [Ω], where each entry Ω_ij is 1 if VM j is assigned to
> server i and 0 otherwise." — initialization phase.
>
> "Each solution represents a VM placement vector, whose dimension equals
> the number of migrated VMs. ... For each decision variable, the search
> bounds are defined as [0, P−1], where P denotes the number of servers and
> P = 160 in our simulation." — implementation details.

So the paper uses BOTH views: an allocation matrix Ω ∈ {0,1}^{P×Q}
(equations 11–18 evaluate objectives on Ω) and a real-valued per-VM vector
of length = number of migrated VMs, bounded `[0, P−1]` (positions updated by
Eq. 21–24). The real vector is the search space; Ω is the decode used for
evaluation. The bridge between them (rounding) is NOT SPECIFIED.

### 1.2 Objectives (paper-faithful, Eq. 19–20)

Minimize `{f_ORS(Ω), f_Srisk(Ω), f_Φ(Ω), f_Ctotal(Ω), f_(−RU)(Ω)}`:
overall VM risk, server co-residency risk, high/normal co-residency
duration Φ, defensive cost (migration count × C_migration + activated
servers × C_server, Eq. 14), and negative total resource utilization.
Constraints: server CPU S_C and memory S_Mem must not be exceeded.

### 1.3 Position-update equations (paper-faithful, transcribed)

Exploration sub-population P_a (Brownian sublimation):

```
X_i^1(t) = ρ1 · ( X(t) − X_i(t) ) + (1 − ρ1) · ( X̄(t) − X_i(t) )        (21)
X_i(t+1) = X_best(t) + A(t) · { X_i^1(t) },  i ∈ index_a                 (22)
```

Exploitation sub-population P_b (degree-day melting):

```
X_i^2(t) = ρ2 · ( X(t) − X_i(t) ) + (1 − ρ2) · ( X̄(t) − X_i(t) )        (23)
X_i(t+1) = M · X(t) + A(t) · { X_i^2(t) },  i ∈ index_b                  (24)
```

where X(t) = current best allocation, X_best(t) = best individual,
X̄(t) = population centroid, A(t) = Gaussian random vector (mean 0,
variance 1), ρ1 ∈ [0,1] controls exploration perturbation magnitude,
ρ2 ∈ [−1,1] influences exploitation, M = melting factor
("degree-day method") — formula NOT SPECIFIED.

### 1.4 Algorithm 1 (paper-faithful, transcribed)

```
Algorithm 1  NSSAO for Multi-Objective VM Migration
1:  Initialize population {X_i}_{i=1..N}, t = 0, t_max
2:  Evaluate f(X_i);  F ← NDS({X_i})
3:  while t < t_max do
4:      M ← Eq.(10);  split {X_i} into P_a ; P_b
5:      for X_i ∈ P_a do   Update by Eq.(21)–(22)   endfor
6:      for X_i ∈ P_b do   Update by Eq.(23)–(24)   endfor
7:      Evaluate f(X_i);  F ← Select(NDS, CD)
8:      t ← t + 1
9:  endwhile
10: return F
```

### 1.5 Mechanism summary

Dual population: exploration mimics rapid sublimation (large-scale
perturbation of VM allocations via Gaussian/Brownian motion); exploitation
mimics slow melting (fine-grained local refinement around the current best).
Population is split evenly at t=0; P_b shrinks and P_a grows as iterations
progress. Termination: t_max reached OR no improvement over a preset number
of consecutive iterations.

---

## 2. Our Discrete Solution Representation (ADAPTATION)

**Decision variable (project):** for the migration-optimization scope, the
solution is the assignment of the M movable VMs to hosts: a length-M integer
vector `x = (h_1, …, h_M)`, `h_k ∈ [0, H−1]` (H = host count), where VM_k is
the k-th VM in a deterministic (fixed, sorted-by-VM-id) migration list.
Pinned VMs — the (Q − M) VMs we choose not to move this round — are not
decision variables; their fixed host IDs are a constant input used when
decoding.

**Internal search representation (paper-faithful):** each individual keeps
a continuous position vector of length M with per-variable bounds
`[0, H−1]` — exactly the paper's `[0, P−1]` with P→H. Position updates use
the paper's Eq. 21–24 unchanged on this continuous vector.

**Decode (the bridge):** `CandidatePlacement` is built by:

1. start from the current live placement (all VMs at current hosts);
2. for each movable VM k, set host = discretize(x_k) (§5);
3. construct `new CandidatePlacement(vmToHostMap, hosts)`.

The decode therefore always yields a complete map over the evaluated VM
set: no missing entries, no duplicates by construction (one position per
VM). `CandidatePlacement` is immutable, so decoded candidates never alias
or mutate live state. ObjectiveEvaluator.evaluate() then produces
`ObjectiveValues` (raw f1–f4) or violations.

M determinism: the movable-VM list is deterministic (fixed order), and all
random draws come from a single seeded `Random`/`RandomGenerator` instance
created once per NSSAO run → same seed ⇒ same run (Stage 2F T9 determinism
carries over).

---

## 3. Population Design

| Item | Paper | Our design | Class |
|---|---|---|---|
| Population size N | 2000 | configurable, default 40–60 (Stage 2G scale) | ADAPTATION (parameter only) |
| Initial generation | random 0/1 in binary matrix; real vector in `[0, P−1]` | seeded uniform random host index per movable VM, in `[0, H−1]`; candidate 0 = current placement | ADAPTATION (discrete seed) |
| Split | even, P_a = P_b = N/2 | even at t=0 | PAPER-FAITHFUL |
| Schedule | P_b shrinks, P_a grows | linear: |P_b|(t) = round(N/2 · (1 − t/t_max)) | ADAPTATION (formula assumed) |
| Randomness | Gaussian A(t), ρ1, ρ2 | seeded PRNG, same distributions | PAPER-FAITHFUL |

Rationale for smaller N: paper's N=2000 with t_max=10 was tuned for
~30,000 VMs and 160 servers on a workstation; Stage 2G runs at validation
scale (handfuls of hosts/VMs). N is a parameter, not a mechanism change;
the mechanism (dual-population + NDS + CD) is kept.

---

## 4. Position Encoding

1. **Dimension:** M = |movable VM list| for the current optimization round.
2. **Bounds:** `[0, H−1]` per variable (paper-faithful form `[0, P−1]`,
   P→H). Clamped after every update.
3. **Continuous ops:** Eq. 21–24 operate on raw reals; nothing discretizes
   mid-update. X(t), X_best(t), X̄(t) are stored as continuous vectors;
   X̄(t) is the component-wise mean over the whole population
   (paper: "population centroid").
4. **Centroid with pinned VMs:** centroid is computed over movable-VM
   positions only; pinned VMs contribute nothing.
5. **Melting factor M (paper NOT SPECIFIED — our assumption, flagged):**
   linearly decaying scalar over iterations, `M(t) = M_max −
   (M_max − M_min) · t / t_max`, defaults M_max = 1.0, M_min = 0.1.
   Rationale: "degree-day" melting slows as temperature drops, matching the
   paper's "exploitation ... decreases" narrative and annealing-style
   shrinkage of exploitation step size. This is an ASSUMPTION, documented as
   such, not a paper claim.
6. **ρ1, ρ2:** drawn fresh per individual per iteration — ρ1 ~ U[0,1]
   (paper: "random number ρ1 ∈ [0,1] controls the perturbation magnitude"),
   ρ2 ~ U[−1,1] (paper: ρ2 ∈ [−1,1]). A(t): i.i.d. standard normal per
   component.

---

## 5. Discretization Strategy (the core design problem)

### Option A — Per-variable rounding + clamping (RECOMMENDED)

```
h_k = clamp( round( x_k ), 0, H−1 )
```

- `round` = `Math.rint` (banker's rounding; deterministic, no positive
  bias), `clamp` enforces bounds.
- Pros: simplest, order-preserving (nearby positions map to nearby hosts —
  matches exploitation "fine-grained adjustment" semantics), zero cost,
  fully deterministic, trivially produces valid host IDs, one host per VM
  so no duplicates/missing assignments by construction.
- Cons: discretization-induced plateaus — many distinct continuous vectors
  decode to the same placement (benign); exploitative steps < 0.5 in
  position space can be invisible after rounding (mitigated by ρ2 ∈ [−1,1]
  and A(t) Gaussian spread, and by M·X(t) drift toward the best solution).

### Option B — Rank/threshold (Boltzmann-style softmax) mapping

```
P(h_k = j) ∝ exp(−|x_k − j| / τ)   or   sigmoid thresholds between host ranks
```

- Pros: keeps a stochastic link between continuous position and host even
  for tiny moves; adds built-in diversity.
- Cons: NOT paper-supported in any extractable form; needs temperature
  schedule τ (another invented parameter); non-deterministic feel unless
  extra seeded draws are managed; harder to test; can map identical
  positions to different hosts (hurts reproducibility of a saved solution).

### Option C — Binary matrix + repair (paper's initialization view taken literally)

Encode Ω ∈ {0,1}^{H×M}, one 1 per column.
- Cons: the paper's *update equations* are vector-based, not matrix-based;
  Option C would require inventing matrix update rules; dimension H×M is
  redundant (H entries per VM for what one integer encodes); repair burden
  much higher. Rejected.

**Recommendation: Option A.** It is the minimal deterministic bridge, keeps
the paper's continuous search + bounds intact, and confines all
problem-specific logic to the decode step, which Stage 2F
(`CandidatePlacement` + `ObjectiveEvaluator`) already consumes. Option B's
extra stochasticity is unnecessary given NSSAO's Gaussian perturbation; and
the paper gives no basis for it. Diversity is preserved by the dual
population and crowding distance, not by the discretizer.

---

## 6. Feasibility Strategy

Requirement: the optimizer must NEVER silently accept missing/duplicate
assignments, nonexistent hosts, CPU overflow, or RAM overflow.

**Chosen strategy: repair-first with deterministic greedy fallback; reject
only as a last resort; never silent.**

1. **Pre-construction guarantees.** Decode always emits exactly one host per
   movable VM from `[0, H−1]` → missing assignment / duplicate assignment /
   nonexistent host are structurally impossible (they cannot survive the
   decode, and `CandidatePlacement`'s host index is built from the full
   host list). If a decode bug ever produced one, Stage 2F
   `ObjectiveEvaluator.validate()` returns violations and the candidate is
   NOT scored — it is discarded and logged loudly.
2. **Repair (deterministic).** After decode, run a deterministic repair
   pass over VMs in the fixed migration-list order: for each host whose
   candidate CPU or RAM demand exceeds capacity, move the smallest-
   impact overloading VM to the feasible host (from the current placement)
   that minimizes the resulting f1; ties broken by lower host id, then by
   lower VM id. No randomness → same input, same repair.
3. **Greedy fallback.** If no feasible host exists for an overloaded VM,
   re-place it by first-fit on the feasible host minimizing (added
   utilization), over ALL hosts; if the current placement itself was
   feasible, this cannot fail for that VM because its current host remains
   a candidate destination.
4. **Reject as last resort.** If the whole feasible space is empty
   (aggregate demand > aggregate capacity — a scenario-construction bug,
   not an optimizer state), the run aborts with a hard error. Objectives of
   a rejected candidate are never reported or used.
5. **Why repair-first (not penalty):** (a) the paper's fitness pipeline has
   no penalty term — adding one would change the objective vector's
   dimension and break strict Pareto comparability with Stage 2F semantics;
   (b) penalty functions waste evaluations on infeasible regions that a
   2-line repair can eliminate; (c) repair keeps every evaluation inside
   ObjectiveEvaluator's approved domain (valid candidates only), so Stage 2F
   code and its validated behavior remain untouched; (d) determinism is
   testable.

NOT implemented in this stage — design only.

---

## 7. Multi-Objective / Pareto Strategy

### Pipeline

```
candidate population (continuous positions)
      ↓  decode (round+clamp) + repair
CandidatePlacement  (immutable)
      ↓  ObjectiveEvaluator.evaluate()          [Stage 2F, unchanged]
raw f1–f4  (or violations → discard + log)
      ↓  normalization  — OPTIONAL, for diagnostics / hypervolume /
         human-readable Pareto plots ONLY (see below)
Pareto dominance on RAW values (all minimize: a dominates b iff
   ∀k f_k(a) ≤ f_k(b) and ∃k f_k(a) < f_k(b))
      ↓  non-dominated sorting (fast non-dominated sort) → fronts F1, F2, …
      ↓  crowding distance within each front (Deb NSGA-II form;
         boundary points = ∞)
      ↓  selection (environment selection: N parents + updated offspring
         → N by (rank, crowding) lexicographic)
      ↓  NSSAO position update (Eq. 21–24 on survivors)
```

### Normalization: not required for dominance — and where it IS useful

Pareto dominance is order-theoretic: it compares objectives coordinate-wise
and is invariant under any strictly increasing per-objective monotone
transform (including min-max scaling). Raw f1–f4 are therefore sufficient
and mathematically correct for dominance, NDS ranks, and crowding distance
(crowding is computed per-objective within a front and summed; scale
differences across objectives affect the *mix* of per-objective spacings,
not validity).

Normalization IS useful, and is planned only for:
1. **Crowding-distance balancing** — f3 is in cost units (can be tens) while
   f1/f2 are in [0,1]-ish risk units; within a front, f3 spacing would
   dominate the summed CD and under-protect f1/f2 diversity. Computing CD
   on per-objective min-max-normalized values (normalizer: (v−min)/(max−min),
   0.5 when max=min — exactly `ObjectiveNormalizer`) is the standard
   NSGA-II remedy. This is a diversity heuristic, not a correctness need.
2. **Reporting/visualization** — human-readable Pareto fronts, and
   hypervolume with a controlled reference point.
3. **Future weighted-decision stages** — if a later stage must pick ONE
   solution from front F1 with explicit weights, weights are only
   meaningful on normalized scales. (Not part of 2G.)

`ObjectiveValues` stays raw-only (Stage 2F contract); normalization happens
in the optimizer's selection layer using `ObjectiveNormalizer`, never
mutating evaluator output.

---

## 8. Selection Strategy

- **Mating/parent pool:** the paper does not use crossover/mutation-based
  parent selection at all — all updates are position updates driven by
  X_best, centroid, and Gaussian noise. So: all survivors are parents;
  no tournament, no crossover, no mutation operators. (Matches the Stage 2F
  prohibition list: none of those exist here either.)
- **Environment selection ( survivor selection, Step 7 "Select(NDS, CD)"):**
  combine N parents + N updated offspring (2N), compute fronts, fill the
  next population front by front; when a front must be split, take larger
  crowding distance first (standard NSGA-II environmental selection).
- **X(t) (current best allocation) and X_best(t):** NOT SPECIFIED in the
  paper for the multi-objective case (single "best" is ill-defined on a
  front). Adaptation, flagged: choose deterministically among the first
  front by lexicographic (rank, then larger crowding, then lower
  first-objective value, then lower solution index). Same fixed rule names
  X(t) for the position-update equations. Determinism preserved.
- **Diversity guard:** leaders are re-evaluated from the current front each
  iteration; if front F1 is empty (impossible with ≥1 evaluated candidate,
  but asserted), run aborts with error.

---

## 9. Termination

Paper-faithful, both conditions, whichever first:
1. `t ≥ t_max` (paper: max iterations; paper value 10).
2. **No-improvement stall:** no change in the first non-dominated front's
   objective-vector set over `stall_limit` consecutive iterations (paper:
   "no improvement in VM allocation is observed over a preset number of
   consecutive iterations"; the exact count is NOT SPECIFIED → parameter,
   default 3, flagged as assumption).

"Improvement" defined as: the set of objective vectors in F1 changed
(compared as sorted tuples of raw values with exact-equality semantics;
floats compared bitwise since evaluation is deterministic).

---

## 10. Parameter Table

| Parameter | Paper value | Stage 2G default | Status |
|---|---|---|---|
| Population size N | 2000 | 40 (configurable) | ADAPTATION (scale) |
| Max iterations t_max | 10 | 10 | PAPER-FAITHFUL |
| Search bounds per variable | [0, P−1], P=160 servers | [0, H−1], H = host count | PAPER-FAITHFUL form, H adapted |
| Solution dimension | number of migrated VMs | M = |movable VM list| | PAPER-FAITHFUL |
| Sub-population split | even; P_b shrinks, P_a grows | even; linear swap | split PAPER-FAITHFUL; schedule ADAPTATION |
| A(t) | Gaussian, mean 0, var 1 | standard normal per component | PAPER-FAITHFUL |
| ρ1 | ∈ [0,1] | U[0,1] per update | PAPER-FAITHFUL |
| ρ2 | ∈ [−1,1] | U[−1,1] per update | PAPER-FAITHFUL |
| Melting factor M | "Eq. (10)", degree-day — NOT SPECIFIED | M(t) = 1.0 → 0.1 linear | ADAPTATION (assumption) |
| Stall limit | "preset number" — NOT SPECIFIED | 3 | ADAPTATION |
| Crowding distance | used, formula not printed | Deb NSGA-II standard | ADAPTATION (standard) |
| Rounding rule | NOT SPECIFIED | Math.rint + clamp | ADAPTATION |
| Per-host cost / per-migration cost | C_server, C_migration (simulation params) | 1.0 / 1.0 via ObjectiveEvaluator | consistent with Stage 2C/2D |
| RNG seed | not stated | fixed, single seeded PRNG | ADAPTATION (determinism requirement) |

---

## 11. Paper-Faithful vs Project-Adapted Decisions (16-item checklist)

| # | Item | Decision | Class | Where it lands |
|---|---|---|---|---|
| 1 | Solution/chromosome | continuous length-M vector `[0,H−1]` + decode to Ω-style map | PAPER-FAITHFUL (vector+bounds); decode ADAPTED | §2 |
| 2 | Initial population | random; also seed with current placement | PAPER-FAITHFUL (random init); current-placement seed ADAPTED | §3 |
| 3 | Population size | 2000 → 40 default | ADAPTATION | §3, §10 |
| 4 | Discoverers/producers proportion | **no such roles in NSSAO** (SSA ref [29] only; text not in PDF) | DO NOT INVENT — absent | §0(5), §1 |
| 5 | Joiners/followers proportion | **no such roles in NSSAO**; dual population instead: P_a = P_b = N/2 initially | PAPER-FAITHFUL (dual pop) | §3 |
| 6 | Vigilantes/alert behavior | **absent from NSSAO as printed** — none in our design | DO NOT INVENT | §0(5) |
| 7 | Position-update equations | Eq. 21–24 transcribed as-is | PAPER-FAITHFUL | §1.3, §4 |
| 8 | Continuous→discrete mapping | round + clamp (Option A) | ADAPTATION (gap) | §5 |
| 9 | Infeasible solutions | repair-first + greedy fallback; reject last; never silent | ADAPTATION (gap) | §6 |
| 10 | Fitness evaluation | ObjectiveEvaluator f1–f4 (5 paper objectives → 4 approved project objectives) | PAPER-FAITHFUL mechanism; objective set = project-approved ADAPTATION | §7 |
| 11 | Non-dominated sorting | fast NDS on raw f1–f4 | PAPER-FAITHFUL | §7 |
| 12 | Pareto rank | front index from iterated NDS | PAPER-FAITHFUL | §7 |
| 13 | Crowding distance | Deb NSGA-II form on normalized per-objective values | ADAPTATION (formula not printed) | §7 |
| 14 | Best-solution selection | environmental selection (NDS+CD); X_best/X(t) via fixed lexicographic rule | PAPER-FAITHFUL (NDS+CD select); tie rule ADAPTED | §8 |
| 15 | Termination | t_max OR stall | PAPER-FAITHFUL (conditions); stall count ADAPTED | §9 |
| 16 | Parameters | see table | mixed | §10 |

Objective-set mapping note (documented, not invented): the paper minimizes
5 objectives — f_ORS, f_Srisk, f_Φ, f_Ctotal, f_(−RU). Our approved Stage 2F
objectives are f1 (threat×serverRisk ≈ paper's ORS/SRS risk), f2
(cross-entity co-residency max-threat ≈ paper's S_risk), f3 (active-host +
migration cost = paper's C_total with C_server = perHostCost), f4
(unused capacity = paper's −RU inverted into a minimized form). The paper's
co-residency DURATION objective f_Φ is deliberately NOT included — duration
remains a post-hoc Stage 2D metric per project contract.

---

## 12. Exact Pseudocode (for the future Stage 2G implementation)

```
# All randomness from ONE seeded PRNG created at start. No CloudSim state
# is touched except reads of capacities/current placement.

input:
  hosts H (all hosts, immutable descriptors)        # Stage 2F contract
  allVms, movableList (fixed order), pinned map     # current placement
  currentPlacement (vmId -> current hostId)
  N, t_max, stall_limit, M_max, M_min

function decode(x):                                 # x: real vector len M
    vmToHost = copy(currentPlacement)               # pinned VMs fixed
    for k in 0..M-1:
        h = clamp(Math.rint(x[k]), 0, H-1)          # §5 Option A
        vmToHost[movableList[k]] = h
    cand = new CandidatePlacement(vmToHost, hosts)  # Stage 2F class
    return repair(cand, movableList, hosts)         # §6, deterministic

function evaluate(cand):
    r = evaluator.evaluate(cand, allVms, threatMap) # Stage 2F, unchanged
    if !r.isValid(): log(violations); return DISCARDED
    return r.getObjectives()                        # RAW f1..f4

# --- NDS + CD on raw values ---
function fronts = NDS(pop)                          # fast non-dominated sort
function cd    = crowding(front, values)            # Deb form; boundary = ∞

# --- main ---
P  = [ random vector in [0,H-1]^M  for i in 1..N ]  # candidate 0 = current
V  = [ evaluate(decode(x)) for x in P ]             # only valid kept
F  = NDS(P)
t, stall = 0, 0
prevFront1 = objectiveSet(F[0])

while t < t_max:
    Xbest = lexicographicBest(F[0])                 # §8 fixed rule
    Xbar  = componentwiseMean(P)                    # population centroid
    nb    = round(N/2 * (1 - t/t_max))              # |P_b| shrinks (§3)

    for i, x in enumerate(P):
        A   = gaussianVector(M)                     # mean 0 var 1
        rho = (t < nb-split ? U[0,1] : U[-1,1])     # ρ1 vs ρ2 per sub-pop
        Xd  = rho * (Xbest - x) + (1 - rho) * (Xbar - x)     # Eq. 21/23
        if i in explorationSubPop:                           # Eq. 22
            x' = Xbest + A * Xd
        else:                                                # Eq. 24
            Melting = M_max - (M_max - M_min) * t / t_max    # §4.5 (assumed)
            x' = Melting * Xbest + A * Xd
        x' = clamp(x', 0, H-1)
        offspring[i] = x'

    Voff = [ evaluate(decode(x')) for x' in offspring ]     # valid only
    survivors = environmentalSelect(P ∪ offspring,          # §8
                                     V ∪ Voff, N, NDS, CD)

    P, V = survivors
    F = NDS(P)
    if objectiveSet(F[0]) == prevFront1: stall += 1 else stall = 0
    prevFront1 = objectiveSet(F[0])
    t += 1
    if stall >= stall_limit: break                          # §9

return ParetoFronts(F, P, V)   # F1 objective vectors; NO migration executed
```

Post-conditions: every returned solution is a valid CandidatePlacement
(repair guaranteed); raw f1–f4 only; no VM moved; no Stage 2F code changed;
no NSSAO code exists yet (this stage).

---

## 13. Risks / Limitations

1. **Rounding plateaus** — continuous updates can stall on flat decode
   regions; mitigations: Gaussian A(t) spread, ρ2 ∈ [−1,1], M-annealed
   drift toward X_best, and the exploration sub-population's large
   perturbations.
2. **Melting-factor formula is an assumption** — the paper's Eq. (10) for M
   is not recoverable from the PDF; our linear anneal is documented as
   assumed. If the SSA source ([29]) becomes available, revisit.
3. **Repair distortion** — deterministic repair changes candidate genotypes
   (positions vs repaired placement diverge); acceptable because
   positions are re-randomized every iteration, but it weakens the
   position↔placement correspondence for repaired individuals.
4. **Crowding on 4 objectives** — CD reliability degrades in >3-D objective
   space (known NSGA-II limitation); with only ~dozens of candidates this
   is acceptable at Stage 2G scale.
5. **Small-population parameter regime** — paper's N=2000/t_max=10 was for
   30k VMs/160 servers; our defaults (N=40) are untested for convergence
   quality. Parameter sweeps deferred to Stage 2G experiments.
6. **Scalability** — evaluate() is O(Q²/H) per candidate (pair loops);
   NSSAO evaluates 2N candidates per iteration. Fine at validation scale;
   a 30,000-VM run (paper scale) is explicitly out of scope for this
   project stage.
7. **No migration occurs** — by design. Integrating a chosen solution into
   CloudSim (via Stage 2D VmMigrationService) belongs to a later stage.
8. **f2/f3 ties** — many placements tie on f3 (same active-host count and
   migration count); fronts may be large; fine, but worth asserting in
   tests.

---

## 14. What Must Be Tested in Stage 2G.2

1. **Determinism:** same seed ⇒ identical run (same fronts, same chosen
   F1); two runs with different seeds may differ (and their F1 sets are
   both valid).
2. **Decode correctness:** round+clamp maps positions to valid host IDs for
   H ∈ {1, 2, 3, 10}; every decoded candidate has exactly one entry per
   movable VM; pinned VMs unchanged.
3. **Never-invalid:** across a full run, zero `isValid()==false` results
   reach the archive; violation counters (if any) are zero after repair —
   and any forced decode bug is caught loudly (repair cannot mask it).
4. **Repair correctness:** force CPU/RAM overflow by decode; assert
   repaired candidate passes `ObjectiveEvaluator.validate()` and that
   repair is deterministic (same input twice ⇒ same repair).
5. **Stage 2F untouched:** `ObjectiveEvaluator`/`CandidatePlacement`/
   `ObjectiveValues`/`ObjectiveNormalizer` byte-identical to Stage 2F
   (git diff empty); evaluator unit expectations from Stage 2F validation
   still pass (X: f1=0.1228125, f2=0.9, f3=2.0, f4=0.625, etc.).
6. **Purity:** running NSSAO does not mutate live CloudSim state (host
   guest-list fingerprints before/after run identical — extends Stage 2F
   T8 to the optimizer loop); no VM ever actually migrates during
   optimization.
7. **NDS correctness:** known 4-D example — a dominated set, front sizes,
   and rank assignment verified against hand-computed dominance.
8. **Crowding distance:** hand-computed small front (boundary = ∞, interior
   by per-objective spacing); equal-value objective yields 0.5-normalized
   spacing per `ObjectiveNormalizer` contract.
9. **Dual-population schedule:** |P_b(t)| = round(N/2·(1−t/t_max)) holds
   each iteration; |P_a| + |P_b| = N.
10. **Update equations:** spot-check Eq. 21–24 numerically for a fixed seed
    against hand-computed values (ρ, A, centroid, M all injected).
11. **Termination:** t_max stop; stall stop (inject a plateau objective
    landscape); both fire at the right iteration.
12. **Improvement sanity:** on the Stage 2F scenario, NSSAO's F1 contains
    placements with f2 = 0 or f3 lower than the current placement's
    (i.e., it finds non-dominated alternatives the hand examples
    established); no objective regresses on the chosen final front vs the
    current placement without compensation in another objective.
13. **Regression suite:** Stage 2B/2C/2D/2E validations + CloudSimExample1/2
    all still pass; Stage 2F validation passes twice.
14. **NoNSSAO-leak checks:** no adaptive-threshold variable in the optimizer
    (threshold stays inside `VmThreatState.isHighThreat()`), no
    co-residency-duration objective, no producer/joiner/vigilance code.

---

## Explicitly Out of Scope (per contract)

- Adaptive threshold: remains ONLY in `VmThreatState.isHighThreat()`; never
  an NSSAO variable or objective.
- Co-residency duration: post-hoc Stage 2D metric; not in NSSAO.
- Physical migration: only after a final solution is selected AND accepted
  by the later integration stage.
- Producer/scrounger/vigilance roles, crossover, mutation: absent from
  NSSAO as printed; none designed.

---

**HARD STOP — STAGE 2G.1.** Design document only. No NSSAO code, no
optimization code, no changes to existing Java. Next stage (2G.2, when
approved) implements exactly this design with the tests in §14.
