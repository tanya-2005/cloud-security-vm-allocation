package org.cloudbus.cloudsim.examples.stage2e;

import java.util.*;

/**
 * Adaptive threat threshold derived from the current threat-score distribution.
 *
 * <p>Stage 2E replaces fixed thresholding (e.g., 0.5 or 0.6) with a
 * percentile-based adaptive rule that responds to the observed threat
 * population. The threshold is recomputed whenever the threat-score
 * population changes (new VMs added, scores updated, dataset changes).
 *
 * <h3>Formula</h3>
 * <pre>
 *   raw = P75 + tailWeight * (P90 - P75)
 *   threshold = clamp(raw, lowerBound, upperBound)
 * </pre>
 *
 * <p>Where:
 * <ul>
 *   <li>P75 = 75th percentile of the threat-score population</li>
 *   <li>P90 = 90th percentile of the threat-score population</li>
 *   <li>tailWeight = configurable parameter (default 0.5)</li>
 *   <li>lowerBound = 0.5 (default)</li>
 *   <li>upperBound = 0.9 (default)</li>
 * </ul>
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>When the threat tail is fat (P90 >> P75): threshold shifts toward P90,
 *       capturing more of the high-threat cluster</li>
 *   <li>When the threat tail is thin (P90 ≈ P75): threshold stays near P75,
 *       conservative classification</li>
 *   <li>When all scores are identical: raw = P75 = that value; if below 0.5,
 *       floor applies; if above 0.9, ceiling applies</li>
 *   <li>Empty population: returns the lower bound (most conservative)</li>
 *   <li>Single score: raw = that score, clamped to [0.5, 0.9]</li>
 * </ul>
 *
 * <h3>Why not 0.6 (paper) or 0.5 (testing)?</h3>
 * <p>The paper's 0.6 is a fixed constant not adapted to our specific datasets
 * (Azure V2, Alibaba V2018). The temporary 0.5 used in Stage 2C/2D testing
 * was deliberately chosen as a low floor to validate the risk machinery,
 * not as a classification decision. The adaptive threshold responds to the
 * actual score distribution, making it suitable for cross-dataset evaluation
 * where different datasets have different score distributions.
 *
 * <h3>Why no attack labels required</h3>
 * <p>This method operates on the unsupervised Isolation Forest scores
 * (anomaly scores in [0,1]). It does NOT require ground-truth attack
 * labels. The percentile-based approach identifies statistically unusual
 * scores relative to the observed population.
 *
 * <h3>Thread safety</h3>
 * <p>This class is immutable and thread-safe.
 */
public final class AdaptiveThreatThreshold {

    /** Default tail weight parameter. */
    public static final double DEFAULT_TAIL_WEIGHT = 0.5;

    /** Default lower bound for the adaptive threshold. */
    public static final double DEFAULT_LOWER_BOUND = 0.5;

    /** Default upper bound for the adaptive threshold. */
    public static final double DEFAULT_UPPER_BOUND = 0.9;

    private final double tailWeight;
    private final double lowerBound;
    private final double upperBound;

    /**
     * Create an adaptive threshold calculator with default parameters.
     */
    public AdaptiveThreatThreshold() {
        this(DEFAULT_TAIL_WEIGHT, DEFAULT_LOWER_BOUND, DEFAULT_UPPER_BOUND);
    }

    /**
     * Create an adaptive threshold calculator with custom parameters.
     *
     * @param tailWeight weight for the P90 contribution in [0, 1].
     *                   0.0 = threshold = P75 (conservative)
     *                   1.0 = threshold = P90 (aggressive)
     *                   0.5 = balanced (default)
     * @param lowerBound minimum threshold value, must be in [0, 1]
     * @param upperBound maximum threshold value, must be in [0, 1]
     * @throws IllegalArgumentException if parameters are invalid
     */
    public AdaptiveThreatThreshold(double tailWeight, double lowerBound, double upperBound) {
        if (Double.isNaN(tailWeight) || tailWeight < 0.0 || tailWeight > 1.0) {
            throw new IllegalArgumentException(
                    "tailWeight must be in [0,1], got: " + tailWeight);
        }
        if (Double.isNaN(lowerBound) || lowerBound < 0.0 || lowerBound > 1.0) {
            throw new IllegalArgumentException(
                    "lowerBound must be in [0,1], got: " + lowerBound);
        }
        if (Double.isNaN(upperBound) || upperBound < 0.0 || upperBound > 1.0) {
            throw new IllegalArgumentException(
                    "upperBound must be in [0,1], got: " + upperBound);
        }
        if (lowerBound > upperBound) {
            throw new IllegalArgumentException(
                    "lowerBound (" + lowerBound + ") must be <= upperBound (" + upperBound + ")");
        }
        this.tailWeight = tailWeight;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    /**
     * Compute the adaptive threat threshold from a population of scores.
     *
     * @param threatScores non-null list of threat scores in [0, 1]
     * @return adaptive threshold in [lowerBound, upperBound]
     * @throws IllegalArgumentException if threatScores is null or contains
     *         values outside [0, 1], NaN, or infinity
     */
    public double compute(List<Double> threatScores) {
        if (threatScores == null) {
            throw new IllegalArgumentException("threatScores cannot be null");
        }
        if (threatScores.isEmpty()) {
            return lowerBound;
        }

        // Validate all scores
        validateScores(threatScores);

        // Sort a copy for percentile computation
        double[] sorted = threatScores.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);

        double p75 = percentile(sorted, 75.0);
        double p90 = percentile(sorted, 90.0);

        double raw = p75 + tailWeight * (p90 - p75);
        return clamp(raw);
    }

    /**
     * Compute the adaptive threshold and return detailed information.
     *
     * @param threatScores non-null list of threat scores in [0, 1]
     * @return detailed result with threshold and diagnostics
     */
    public ThresholdResult computeDetailed(List<Double> threatScores) {
        if (threatScores == null) {
            throw new IllegalArgumentException("threatScores cannot be null");
        }
        if (threatScores.isEmpty()) {
            return new ThresholdResult(lowerBound, lowerBound, lowerBound,
                    0.0, 0.0, 0, tailWeight, lowerBound, upperBound);
        }

        // Validate all scores
        validateScores(threatScores);

        double[] sorted = threatScores.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);

        double p75 = percentile(sorted, 75.0);
        double p90 = percentile(sorted, 90.0);
        double raw = p75 + tailWeight * (p90 - p75);
        double threshold = clamp(raw);

        return new ThresholdResult(threshold, raw, p75, p90,
                p90 - p75, sorted.length, tailWeight, lowerBound, upperBound);
    }

    /**
     * Validate all scores in the list.
     */
    private static void validateScores(List<Double> threatScores) {
        for (int i = 0; i < threatScores.size(); i++) {
            Double score = threatScores.get(i);
            if (score == null) {
                throw new IllegalArgumentException(
                        "threatScores[" + i + "] cannot be null");
            }
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                throw new IllegalArgumentException(
                        "threatScores[" + i + "] must be finite, got: " + score);
            }
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException(
                        "threatScores[" + i + "] must be in [0,1], got: " + score);
            }
        }
    }

    /**
     * Compute percentile using linear interpolation on sorted array.
     * percentile(50) = median.
     */
    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (p / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = rank - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }

    private double clamp(double value) {
        return Math.max(lowerBound, Math.min(upperBound, value));
    }

    public double getTailWeight() {
        return tailWeight;
    }

    public double getLowerBound() {
        return lowerBound;
    }

    public double getUpperBound() {
        return upperBound;
    }

    /**
     * Detailed result of adaptive threshold computation.
     */
    public static final class ThresholdResult {
        private final double threshold;
        private final double rawValue;
        private final double p75;
        private final double p90;
        private final double tailSpread;
        private final int populationSize;
        private final double tailWeight;
        private final double lowerBound;
        private final double upperBound;

        ThresholdResult(double threshold, double rawValue, double p75, double p90,
                        double tailSpread, int populationSize, double tailWeight,
                        double lowerBound, double upperBound) {
            this.threshold = threshold;
            this.rawValue = rawValue;
            this.p75 = p75;
            this.p90 = p90;
            this.tailSpread = tailSpread;
            this.populationSize = populationSize;
            this.tailWeight = tailWeight;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
        }

        public double getThreshold() { return threshold; }
        public double getRawValue() { return rawValue; }
        public double getP75() { return p75; }
        public double getP90() { return p90; }
        public double getTailSpread() { return tailSpread; }
        public int getPopulationSize() { return populationSize; }
        public double getTailWeight() { return tailWeight; }
        public double getLowerBound() { return lowerBound; }
        public double getUpperBound() { return upperBound; }

        @Override
        public String toString() {
            return "ThresholdResult{" +
                    "threshold=" + String.format("%.4f", threshold) +
                    ", raw=" + String.format("%.4f", rawValue) +
                    ", P75=" + String.format("%.4f", p75) +
                    ", P90=" + String.format("%.4f", p90) +
                    ", tailSpread=" + String.format("%.4f", tailSpread) +
                    ", n=" + populationSize +
                    ", tailWeight=" + tailWeight +
                    ", bounds=[" + lowerBound + "," + upperBound + "]" +
                    '}';
        }
    }
}
