package org.cloudbus.cloudsim.examples.stage2e;

import org.cloudbus.cloudsim.Log;

import java.util.*;

/**
 * Stage 2E — Adaptive Threat Threshold Validation.
 *
 * <p>Purpose: validate the adaptive threshold mechanism that replaces
 * fixed thresholding (0.5 or 0.6) with a percentile-based rule
 * derived from the observed threat-score population.
 *
 * <p>This does NOT implement NSSAO, VM allocation optimization,
 * or multi-objective optimization. It is a standalone validation
 * of the threshold computation mechanism.
 *
 * <p>Validation scenarios:
 * <ol>
 *   <li>Empty input → lower bound</li>
 *   <li>Single score → clamp(score, 0.5, 0.9)</li>
 *   <li>Normal distributed population → reasonable threshold</li>
 *   <li>Low-threat population → floor (0.5)</li>
 *   <li>High-threat tail population → elevated threshold</li>
 *   <li>All scores identical → clamp(value, 0.5, 0.9)</li>
 *   <li>Scores near 0 and 1 → threshold in [0.5, 0.9]</li>
 *   <li>Out-of-range values rejected</li>
 *   <li>NaN/infinity values rejected</li>
 *   <li>Threshold always in [0.5, 0.9]</li>
 *   <li>Deterministic identical input → identical output</li>
 *   <li>Increasing high-threat tail → threshold does not decrease</li>
 *   <li>Classification uses adaptive threshold (not hardcoded)</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   java -cp "..." org.cloudbus.cloudsim.examples.stage2e.Stage2eAdaptiveThresholdValidation
 * </pre>
 */
public class Stage2eAdaptiveThresholdValidation {

    private static final double EPSILON = 1e-9;

    public static void main(String[] args) {
        Log.println("========================================");
        Log.println("STAGE 2E VALIDATION");
        Log.println("Adaptive Threat Threshold Mechanism");
        Log.println("========================================");
        Log.println();
        Log.println("NOT implementing NSSAO.");
        Log.println("NOT implementing VM allocation optimization.");
        Log.println("NOT using paper's fixed 0.6 threshold.");
        Log.println("NOT using temporary 0.5 testing threshold.");
        Log.println("Deriving threshold from threat-score distribution.");
        Log.println();

        try {
            AdaptiveThreatThreshold calculator = new AdaptiveThreatThreshold();
            boolean allPassed = true;

            // === [1] Empty input → lower bound ===
            Log.println("--- [1] Empty input handling ---");
            double emptyResult = calculator.compute(Collections.emptyList());
            boolean emptyCorrect = Math.abs(emptyResult - 0.5) < EPSILON;
            Log.println("  Empty input → " + emptyResult + " (expected 0.5): "
                    + (emptyCorrect ? "PASS" : "FAIL"));
            allPassed &= emptyCorrect;

            // === [2] Single score → clamp(score, 0.5, 0.9) ===
            Log.println();
            Log.println("--- [2] Single score input ---");
            // Single score at 0.3 → clamped to 0.5
            double singleLow = calculator.compute(List.of(0.3));
            boolean singleLowCorrect = Math.abs(singleLow - 0.5) < EPSILON;
            Log.println("  Single score 0.3 → " + singleLow + " (expected 0.5): "
                    + (singleLowCorrect ? "PASS" : "FAIL"));
            allPassed &= singleLowCorrect;

            // Single score at 0.7 → stays 0.7
            double singleMid = calculator.compute(List.of(0.7));
            boolean singleMidCorrect = Math.abs(singleMid - 0.7) < EPSILON;
            Log.println("  Single score 0.7 → " + singleMid + " (expected 0.7): "
                    + (singleMidCorrect ? "PASS" : "FAIL"));
            allPassed &= singleMidCorrect;

            // Single score at 0.95 → clamped to 0.9
            double singleHigh = calculator.compute(List.of(0.95));
            boolean singleHighCorrect = Math.abs(singleHigh - 0.9) < EPSILON;
            Log.println("  Single score 0.95 → " + singleHigh + " (expected 0.9): "
                    + (singleHighCorrect ? "PASS" : "FAIL"));
            allPassed &= singleHighCorrect;

            // === [3] Normal distributed population ===
            Log.println();
            Log.println("--- [3] Normal distributed population ---");
            List<Double> normalPop = List.of(
                    0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45,
                    0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85
            );
            AdaptiveThreatThreshold.ThresholdResult normalResult =
                    calculator.computeDetailed(normalPop);
            Log.println("  Population: " + normalPop.size() + " scores, "
                    + "range [" + Collections.min(normalPop) + ", " + Collections.max(normalPop) + "]");
            Log.println("  P75=" + String.format("%.4f", normalResult.getP75())
                    + ", P90=" + String.format("%.4f", normalResult.getP90())
                    + ", tailSpread=" + String.format("%.4f", normalResult.getTailSpread()));
            Log.println("  Threshold: " + String.format("%.4f", normalResult.getThreshold()));
            boolean normalInRange = normalResult.getThreshold() >= 0.5
                    && normalResult.getThreshold() <= 0.9;
            Log.println("  In [0.5, 0.9]: " + (normalInRange ? "PASS" : "FAIL"));
            allPassed &= normalInRange;

            // === [4] Low-threat population → floor ===
            Log.println();
            Log.println("--- [4] Low-threat population ---");
            List<Double> lowPop = List.of(0.05, 0.08, 0.10, 0.12, 0.15, 0.18, 0.20, 0.22);
            AdaptiveThreatThreshold.ThresholdResult lowResult =
                    calculator.computeDetailed(lowPop);
            Log.println("  Population: " + lowPop.size() + " scores, "
                    + "range [" + Collections.min(lowPop) + ", " + Collections.max(lowPop) + "]");
            Log.println("  P75=" + String.format("%.4f", lowResult.getP75())
                    + ", P90=" + String.format("%.4f", lowResult.getP90())
                    + ", raw=" + String.format("%.4f", lowResult.getRawValue()));
            Log.println("  Threshold: " + String.format("%.4f", lowResult.getThreshold()));
            boolean lowAtFloor = Math.abs(lowResult.getThreshold() - 0.5) < EPSILON;
            Log.println("  At floor (0.5): " + (lowAtFloor ? "PASS" : "FAIL"));
            allPassed &= lowAtFloor;

            // === [5] High-threat tail → elevated threshold ===
            Log.println();
            Log.println("--- [5] High-threat tail population ---");
            List<Double> highTailPop = List.of(
                    0.1, 0.15, 0.2, 0.25, 0.3, 0.35,
                    0.7, 0.75, 0.8, 0.85, 0.9, 0.95
            );
            AdaptiveThreatThreshold.ThresholdResult highTailResult =
                    calculator.computeDetailed(highTailPop);
            Log.println("  Population: " + highTailPop.size() + " scores, "
                    + "range [" + Collections.min(highTailPop) + ", " + Collections.max(highTailPop) + "]");
            Log.println("  P75=" + String.format("%.4f", highTailResult.getP75())
                    + ", P90=" + String.format("%.4f", highTailResult.getP90())
                    + ", raw=" + String.format("%.4f", highTailResult.getRawValue()));
            Log.println("  Threshold: " + String.format("%.4f", highTailResult.getThreshold()));
            boolean highTailElevated = highTailResult.getThreshold() > 0.5
                    && highTailResult.getThreshold() <= 0.9;
            Log.println("  Elevated above floor: " + (highTailElevated ? "PASS" : "FAIL"));
            allPassed &= highTailElevated;

            // === [6] All scores identical ===
            Log.println();
            Log.println("--- [6] All scores identical ---");
            List<Double> identicalPop = List.of(0.5, 0.5, 0.5, 0.5, 0.5);
            double identicalResult = calculator.compute(identicalPop);
            boolean identicalClamped = Math.abs(identicalResult - 0.5) < EPSILON;
            Log.println("  All 0.5 → " + String.format("%.4f", identicalResult) + " (expected 0.5): "
                    + (identicalClamped ? "PASS" : "FAIL"));
            allPassed &= identicalClamped;

            List<Double> identicalHighPop = List.of(0.8, 0.8, 0.8, 0.8, 0.8);
            double identicalHighResult = calculator.compute(identicalHighPop);
            boolean identicalHighClamped = Math.abs(identicalHighResult - 0.8) < EPSILON;
            Log.println("  All 0.8 → " + String.format("%.4f", identicalHighResult) + " (expected 0.8): "
                    + (identicalHighClamped ? "PASS" : "FAIL"));
            allPassed &= identicalHighClamped;

            // === [7] Scores near 0 and 1 ===
            Log.println();
            Log.println("--- [7] Scores near 0 and 1 ---");
            List<Double> extremesPop = List.of(0.01, 0.02, 0.03, 0.97, 0.98, 0.99);
            AdaptiveThreatThreshold.ThresholdResult extremesResult =
                    calculator.computeDetailed(extremesPop);
            Log.println("  Population: " + extremesPop);
            Log.println("  Threshold: " + String.format("%.4f", extremesResult.getThreshold()));
            boolean extremesInRange = extremesResult.getThreshold() >= 0.5
                    && extremesResult.getThreshold() <= 0.9;
            Log.println("  In [0.5, 0.9]: " + (extremesInRange ? "PASS" : "FAIL"));
            allPassed &= extremesInRange;

            // === [8] Out-of-range values rejected ===
            Log.println();
            Log.println("--- [8] Out-of-range values rejected ---");
            boolean rejectedNeg = false;
            try {
                calculator.compute(List.of(-0.1, 0.5));
            } catch (IllegalArgumentException e) {
                rejectedNeg = e.getMessage().contains("[0,1]");
            }
            Log.println("  Negative value rejected: " + (rejectedNeg ? "PASS" : "FAIL"));
            allPassed &= rejectedNeg;

            boolean rejectedOver = false;
            try {
                calculator.compute(List.of(0.5, 1.5));
            } catch (IllegalArgumentException e) {
                rejectedOver = e.getMessage().contains("[0,1]");
            }
            Log.println("  Value >1 rejected: " + (rejectedOver ? "PASS" : "FAIL"));
            allPassed &= rejectedOver;

            // === [9] NaN/infinity values rejected ===
            Log.println();
            Log.println("--- [9] NaN/infinity values rejected ---");
            boolean rejectedNaN = false;
            try {
                calculator.compute(List.of(0.5, Double.NaN));
            } catch (IllegalArgumentException e) {
                rejectedNaN = e.getMessage().contains("finite");
            }
            Log.println("  NaN rejected: " + (rejectedNaN ? "PASS" : "FAIL"));
            allPassed &= rejectedNaN;

            boolean rejectedInf = false;
            try {
                calculator.compute(List.of(0.5, Double.POSITIVE_INFINITY));
            } catch (IllegalArgumentException e) {
                rejectedInf = e.getMessage().contains("finite");
            }
            Log.println("  Infinity rejected: " + (rejectedInf ? "PASS" : "FAIL"));
            allPassed &= rejectedInf;

            // === [10] Threshold always in [0.5, 0.9] ===
            Log.println();
            Log.println("--- [10] Bounds verification ---");
            // Test with diverse populations
            List<List<Double>> testPopulations = List.of(
                    List.of(0.0),
                    List.of(1.0),
                    List.of(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                    List.of(0.5),
                    List.of(0.49, 0.51),
                    List.of(0.89, 0.91),
                    List.of(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
            );
            boolean allBoundsCorrect = true;
            for (List<Double> pop : testPopulations) {
                double result = calculator.compute(pop);
                if (result < 0.5 - EPSILON || result > 0.9 + EPSILON) {
                    allBoundsCorrect = false;
                    Log.println("  FAIL: input " + pop + " → " + result);
                }
            }
            Log.println("  All diverse populations produce threshold in [0.5, 0.9]: "
                    + (allBoundsCorrect ? "PASS" : "FAIL"));
            allPassed &= allBoundsCorrect;

            // === [11] Deterministic ===
            Log.println();
            Log.println("--- [11] Determinism ---");
            List<Double> detPop = List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);
            double detRun1 = calculator.compute(detPop);
            double detRun2 = calculator.compute(detPop);
            boolean deterministic = Math.abs(detRun1 - detRun2) < EPSILON;
            Log.println("  Run 1: " + String.format("%.6f", detRun1)
                    + ", Run 2: " + String.format("%.6f", detRun2)
                    + " → " + (deterministic ? "PASS" : "FAIL"));
            allPassed &= deterministic;

            // === [12] Increasing high-threat tail → threshold does not decrease ===
            Log.println();
            Log.println("--- [12] High-threat tail monotonicity ---");
            List<Double> basePop = List.of(0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45);
            List<Double> boostedPop = List.of(0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.8, 0.9);
            double baseThreshold = calculator.compute(basePop);
            double boostedThreshold = calculator.compute(boostedPop);
            boolean monotonicityHolds = boostedThreshold >= baseThreshold - EPSILON;
            Log.println("  Base threshold: " + String.format("%.4f", baseThreshold));
            Log.println("  Boosted threshold (high tail): " + String.format("%.4f", boostedThreshold));
            Log.println("  Boosted >= base: " + (monotonicityHolds ? "PASS" : "FAIL"));
            allPassed &= monotonicityHolds;

            // === [13] Classification uses adaptive threshold (not hardcoded) ===
            Log.println();
            Log.println("--- [13] Adaptive vs fixed classification ---");
            // With adaptive threshold, classification depends on the population
            List<Double> populationA = List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);
            List<Double> populationB = List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);

            double adaptiveA = calculator.compute(populationA);
            double adaptiveB = calculator.compute(populationB);

            // Classify VMs using adaptive threshold vs hardcoded 0.6
            int adaptiveHighA = countHighThreat(populationA, adaptiveA);
            int fixedHighA = countHighThreat(populationA, 0.6);

            Log.println("  Population: " + populationA);
            Log.println("  Adaptive threshold: " + String.format("%.4f", adaptiveA));
            Log.println("  Fixed threshold (0.6): 0.6000");
            Log.println("  Adaptive high-threat count: " + adaptiveHighA);
            Log.println("  Fixed high-threat count: " + fixedHighA);

            // Verify that the adaptive threshold IS different from 0.6
            boolean adaptiveDiffersFromFixed = Math.abs(adaptiveA - 0.6) > EPSILON;
            Log.println("  Adaptive threshold differs from fixed 0.6: "
                    + (adaptiveDiffersFromFixed ? "PASS" : "FAIL"));
            allPassed &= adaptiveDiffersFromFixed;

            // Verify that adaptive threshold IS different from 0.5
            boolean adaptiveDiffersFromTesting = Math.abs(adaptiveA - 0.5) > EPSILON;
            Log.println("  Adaptive threshold differs from fixed 0.5: "
                    + (adaptiveDiffersFromTesting ? "PASS" : "FAIL"));
            allPassed &= adaptiveDiffersFromTesting;

            // === [14] Cross-dataset threshold adaptation ===
            Log.println();
            Log.println("--- [14] Cross-dataset adaptation ---");
            // Simulate Azure-like distribution (more concentrated)
            List<Double> azureScores = List.of(
                    0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50,
                    0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90
            );
            // Simulate Alibaba-like distribution (more spread, different shape)
            List<Double> alibabaScores = List.of(
                    0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50,
                    0.55, 0.60, 0.65, 0.70, 0.80, 0.85, 0.90, 0.95
            );

            double azureThreshold = calculator.compute(azureScores);
            double alibabaThreshold = calculator.compute(alibabaScores);

            Log.println("  Azure threshold: " + String.format("%.4f", azureThreshold));
            Log.println("  Alibaba threshold: " + String.format("%.4f", alibabaThreshold));

            // Both should be in valid range
            boolean azureValid = azureThreshold >= 0.5 && azureThreshold <= 0.9;
            boolean alibabaValid = alibabaThreshold >= 0.5 && alibabaThreshold <= 0.9;
            boolean crossDatasetValid = azureValid && alibabaValid;
            Log.println("  Both in [0.5, 0.9]: " + (crossDatasetValid ? "PASS" : "FAIL"));
            allPassed &= crossDatasetValid;

            // The thresholds should differ (different distributions)
            boolean thresholdsDiffer = Math.abs(azureThreshold - alibabaThreshold) > EPSILON;
            Log.println("  Thresholds differ (different distributions): "
                    + (thresholdsDiffer ? "PASS" : "FAIL"));
            allPassed &= thresholdsDiffer;

            // === FINAL REPORT ===
            Log.println();
            Log.println("========================================");
            Log.println("STAGE 2E VALIDATION REPORT");
            Log.println("========================================");
            Log.println("Formula: clamp(P75 + 0.5*(P90 - P75), 0.5, 0.9)");
            Log.println("Tail weight: 0.5");
            Log.println("Bounds: [0.5, 0.9]");
            Log.println();
            Log.println("Threshold behavior:");
            Log.println("  Empty population → 0.5000 (floor)");
            Log.println("  Single score s → clamp(s, 0.5, 0.9)");
            Log.println("  All identical s → clamp(s, 0.5, 0.9)");
            Log.println("  Fat tail (P90 >> P75) → threshold shifts toward P90");
            Log.println("  Thin tail (P90 ≈ P75) → threshold stays near P75");
            Log.println();
            Log.println("Cross-dataset:");
            Log.println("  Azure threshold:     " + String.format("%.4f", azureThreshold));
            Log.println("  Alibaba threshold:   " + String.format("%.4f", alibabaThreshold));
            Log.println();
            Log.println("All checks passed: " + allPassed);
            Log.println();

            if (!allPassed) {
                Log.println("STAGE 2E VALIDATION FAILED");
                System.exit(1);
            }

            Log.println("STAGE 2E VALIDATION PASSED");
            Log.println("========================================");

        } catch (Exception e) {
            Log.println("FATAL ERROR in Stage 2E validation:");
            Log.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Count how many scores are >= threshold.
     */
    private static int countHighThreat(List<Double> scores, double threshold) {
        int count = 0;
        for (double s : scores) {
            if (s >= threshold) count++;
        }
        return count;
    }
}
