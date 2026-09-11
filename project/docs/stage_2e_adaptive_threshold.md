# Stage 2E — Adaptive Threat Threshold

**Status: COMPLETE**

**Date: 2026-09-10**

## Purpose

Replace fixed thresholding (0.5 testing, 0.6 paper) with a percentile-based
adaptive threshold derived dynamically from the current threat-score
distribution. This is a prerequisite for cross-dataset evaluation (Azure V2 +
Alibaba V2018) where different datasets produce different score distributions.

This is NOT NSSAO, NOT VM allocation optimization, NOT multi-objective
optimization. It is a standalone threshold computation mechanism.

## Why Fixed Thresholding Is Insufficient

1. **The paper's 0.6 is a fixed constant** calibrated for a specific dataset
   and clustering method. Our project uses Isolation Forest (different
   anomaly scoring) on different datasets (Azure V2, Alibaba V2018). A
   fixed 0.6 may be too aggressive for one dataset and too conservative
   for another.

2. **The temporary 0.5 used in Stage 2C/2D testing** was deliberately
   chosen as a low floor to validate the risk-state machinery. It was
   never intended as a classification decision. It classifies almost
   every VM as high-threat when scores are concentrated above 0.5.

3. **A universal fixed threshold ignores the score distribution.**
   If one dataset has scores concentrated in [0.1, 0.4] and another
   in [0.6, 0.9], a single fixed threshold cannot serve both.

## Why 0.6 Is NOT the Final Project Threshold

The paper's 0.6 threshold was designed for a specific experimental setup:
- Their clustering method (not Isolation Forest)
- Their dataset (not Azure V2 / Alibaba V2018)
- Their specific feature engineering

Our project:
- Uses Isolation Forest (unsupervised, anomaly-score-based)
- Uses different datasets with different score distributions
- Requires the threshold to adapt to the observed population

0.6 is documented as a reference point, NOT adopted as our final threshold.

## Why 0.5 Is NOT the Final Threshold

0.5 was the testing-only threshold used in Stage 2C and Stage 2D to
validate the risk-state and migration machinery. It was chosen because:
- A low threshold ensures enough VMs are classified as high-threat
  for testing co-residency risk calculations
- It is a reasonable lower bound, not a classification decision

The Stage 2E adaptive threshold replaces this with a distribution-driven
value that responds to the actual threat scores.

## Adaptive Threshold Formula

```
raw = P75 + tailWeight × (P90 − P75)
threshold = clamp(raw, lowerBound, upperBound)
```

Default parameters:
- `tailWeight` = 0.5 (balanced between conservative P75 and aggressive P90)
- `lowerBound` = 0.5 (floor — never below this)
- `upperBound` = 0.9 (ceiling — never above this)

Where:
- P75 = 75th percentile of the threat-score population
- P90 = 90th percentile of the threat-score population
- Percentiles computed via linear interpolation on sorted scores

### Why P75 and P90?

- **P75** represents the boundary of the upper quartile — the point where
  the top 25% of scores begin. This anchors the threshold at a
  statistically meaningful point in the distribution.
- **P90** represents the extreme tail — the top 10% of scores. This
  captures the most anomalous entities.
- **tailWeight** controls how much the threshold shifts toward the
  extreme tail. At 0.5, the threshold is the midpoint between P75 and P90,
  balanced between identifying the upper quartile and the extreme tail.

### Why Not Other Approaches?

- **Fixed percentile (e.g., always P80)**: Ignores the spread of the
  distribution. A population with scores in [0.7, 0.9] and one with
  scores in [0.1, 0.9] would get the same percentile cutoff, but the
  appropriate threshold differs.
- **Standard deviation-based**: Requires assuming a distribution shape.
  Isolation Forest scores are not normally distributed.
- **IQR-based (Q1 + 1.5×IQR)**: Designed for outlier detection in
  box plots, not for threat classification. Can produce thresholds
  outside [0, 1].
- **Clustering-based (the paper's method)**: Requires ground-truth labels
  or a specific clustering algorithm. Our project uses Isolation Forest.

## Bounds and Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| tailWeight | 0.5 | [0, 1] | Weight for P90 contribution. 0 = use P75 only, 1 = use P90 only |
| lowerBound | 0.5 | [0, 1] | Minimum threshold. Ensures classification is meaningful |
| upperBound | 0.9 | [0, 1] | Maximum threshold. Prevents over-restriction |

The bounds [0.5, 0.9] are chosen because:
- Below 0.5: too many VMs classified as high-threat (noise)
- Above 0.9: too few VMs classified as high-threat (misses real threats)
- The range is wide enough to accommodate different distributions
- The bounds can be tuned in later experiments

## Edge-Case Handling

| Scenario | Behavior | Rationale |
|----------|----------|-----------|
| Empty population | Returns lowerBound (0.5) | Most conservative when no data |
| Single score s | clamp(s, 0.5, 0.9) | Cannot determine tail from one point |
| All scores identical | clamp(value, 0.5, 0.9) | No variation → no meaningful tail |
| Scores concentrated low | Floor applies (0.5) | Low-threat population has no high-threat tail |
| Scores concentrated high | Ceiling may apply (0.9) | High-threat population correctly identified |
| NaN/Infinity input | Exception thrown | Invalid data must not silently produce a threshold |
| Values outside [0,1] | Exception thrown | Threat scores are bounded by Isolation Forest |
| Fat tail (P90 >> P75) | Threshold shifts toward P90 | More aggressive classification when tail is prominent |
| Thin tail (P90 ≈ P75) | Threshold stays near P75 | Conservative when tail is not prominent |

## How Threshold Determines Classification

A VM is classified as high-threat when:

```
vm.threatScore >= adaptiveThreshold
```

The adaptive threshold is computed from the full population of threat scores
available to the simulation. It is recomputed when the population changes
(new VMs added, scores updated, dataset changes).

The `VmThreatState` class accepts any threshold value via its constructor.
The adaptive threshold plugs in directly:

```java
// Compute adaptive threshold from current population
AdaptiveThreatThreshold calculator = new AdaptiveThreatThreshold();
double threshold = calculator.compute(allThreatScores);

// Use it for classification
VmThreatState state = new VmThreatState(vmId, score, dataset, type, entityId, threshold);
```

## Why No Attack Labels Required

The adaptive threshold operates on the unsupervised Isolation Forest
anomaly scores. It does NOT require ground-truth attack labels.

The percentile-based approach identifies statistically unusual scores
relative to the observed population. A score at the 90th percentile
is unusual regardless of whether it corresponds to a confirmed attack.

This is appropriate because:
- The Azure V2 and Alibaba V2018 datasets do not provide reliable
  attack labels for all entities
- Isolation Forest is an unsupervised method — it produces anomaly
  scores without labeled training data
- The adaptive threshold complements the unsupervised approach by
  providing a data-driven classification boundary

## Cross-Dataset Evaluation Support

**Stage 2E.1 status: real-data adaptive thresholds have been computed
from the actual project score CSV files.**

The adaptive threshold responds to the observed score distribution, so
different datasets can produce different thresholds. With real project
data (via `Stage2eRealDataThresholdValidation`):

| Scenario | N | P75 | P90 | raw | adaptive threshold | high-threat % |
|---|---|---|---|---|---|---|
| Azure within | 248 | 0.2915 | 0.5436 | 0.4176 | **0.5000** | 12.50% |
| Alibaba within | 67 | 0.3182 | 0.5852 | 0.4517 | **0.5000** | 16.42% |
| Azure→Alibaba | 67 | 0.9965 | 1.0000 | 0.9983 | **0.9000** | 32.84% |
| Alibaba→Azure | 248 | 1.0000 | 1.0000 | 1.0000 | **0.9000** | 93.55% |

**Honest empirical observation:** The bounded percentile rule is
currently **boundary-dominated** for these distributions.

- **Within-dataset scores** (Azure and Alibaba) have low P75/P90 values
  (0.29–0.59). The raw threshold falls below the lower bound of 0.5, so
  clamping produces the floor (0.5000) for both datasets.
- **Cross-scenario scores** have saturated P75/P90 values near 1.0.
  The raw threshold exceeds the upper bound of 0.9, so clamping produces
  the ceiling (0.9000) for both cross-scenarios.

The formula has **NOT been modified** to force interior thresholds.
This boundary behavior is a legitimate empirical finding from the
current score distributions.

**Classification uses the computed adaptive threshold** (not 0.5 or 0.6).
The `VmThreatState` receives the threshold via its constructor:
`threatScore >= adaptiveThreshold`.

**No security improvement claim is made.** The adaptive threshold is
demonstrated to compute and classify; whether it improves security
requires later experiments.

**What the real-data computation proves:**
- The adaptive formula runs correctly on real data (no exceptions,
  correct percentiles, correct clamping)
- The `VmThreatState` classification with the adaptive threshold
  exactly matches direct `threatScore >= threshold` comparison
- Different cross-scenario distributions produce different thresholds
  (0.5 within, 0.9 cross-scenario), confirming adaptiveness
- The within-dataset thresholds clamp to the same floor, but the
  underlying raw values differ (0.4176 vs 0.4517), confirming the
  formula is responsive to distribution differences

## Validation Results

### Stage 2E — Adaptive Threshold Mechanism (Synthetic Tests)

Running `Stage2eAdaptiveThresholdValidation` produces:

```
STAGE 2E VALIDATION PASSED
========================================
```

All 14 checks pass: empty input, single score, normal/low/high populations,
identical values, extremes, out-of-range rejection, NaN/Infinity rejection,
bounds, determinism, monotonicity, and distinction from fixed thresholds.

Regression results:
- Stage 2B: PASSED (exit code 0)
- Stage 2C: PASSED (exit code 0)
- Stage 2D: PASSED (exit code 0)

### Stage 2E.1 — Real-Data Adaptive Threshold Integration

Running `Stage2eRealDataThresholdValidation` (both runs) produces:

```
STAGE 2E.1 REAL-DATA VALIDATION PASSED
========================================
```

All 10 checks pass: real CSV loading (Azure 248, Alibaba 67 records),
adaptive threshold computed from actual scores, thresholds within
[0.5, 0.9], different populations produce different thresholds, real-data
classification uses the adaptive threshold, no hidden fixed 0.5/0.6,
deterministic, second run identical.

**Empirical finding:** the bounded percentile rule is boundary-dominated
for the current score distributions — within-dataset thresholds clamp to
0.5 (lower bound), cross-scenario thresholds clamp to 0.9 (upper bound).
The formula is NOT modified to force interior thresholds.

Regression results:
- Stage 2B: PASSED (exit code 0)
- Stage 2C: PASSED (exit code 0)
- Stage 2D: PASSED (exit code 0)
- Stage 2E: PASSED (exit code 0)
- Stage 2E.1: PASSED (exit code 0), second run PASSED (exit code 0)

## Files

### Created

| File | Purpose |
|------|---------|
| `stage2e/AdaptiveThreatThreshold.java` | Adaptive threshold computation from score population |
| `stage2e/Stage2eAdaptiveThresholdValidation.java` | 14-check validation of threshold mechanism |
| `stage2e/Stage2eRealDataThresholdValidation.java` | Real-data integration + classification validation |

### Not Modified

The following remain unchanged (no redesigned formula, no new dependencies):
- `AdaptiveThreatThreshold.java` — formula, defaults, and behavior unchanged
- `VmThreatState.java` — classification design (`threatScore >= threshold`) kept,
  threshold supplied as constructor parameter
- Python Isolation Forest pipeline — untouched
- CloudSim core — untouched
- Stage 2B/2C/2D existing test scenarios — unchanged (their 0.5 remains explicitly
  marked TESTING ONLY)

## What Remains for Stage 2F / NSSAO

- Use adaptive threshold as the classification input for the risk model
- Experiment with tailWeight tuning using evaluation experiments
- Consider per-dataset vs global threshold computation
- Note: the formula is currently boundary-dominated for existing data;
  tuning experiments may explore whether a different tailWeight or
  bounds are more appropriate for these score distributions
- Do NOT claim adaptive thresholding improves security yet — that
  requires later experiments with the full NSSAO pipeline

## What Stage 2E Proves vs. What It Does Not Prove

**Proves:**
- Adaptive threshold formula correctly implemented and deterministic
- Formula operates on real project data without errors
- Classification via `VmThreatState` with computed threshold is
  mathematically equivalent to `threatScore >= threshold`
- Different score distributions produce different raw threshold values
- No hidden fixed 0.5 or 0.6 classification exists in the new code path

**Does NOT prove:**
- That the adaptive threshold improves security metrics
- That any specific threshold value is "correct"
- That cross-dataset evaluation benefits from adaptive thresholds
- That NSSAO or placement optimization would improve with this threshold
- That the formula is optimal for these score distributions
