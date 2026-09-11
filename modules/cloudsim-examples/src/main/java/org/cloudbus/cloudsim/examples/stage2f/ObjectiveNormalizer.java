package org.cloudbus.cloudsim.examples.stage2f;

/**
 * Min-max normalizer for Stage 2F objective values.
 *
 * <p>Keeps normalization STRICTLY SEPARATE from raw objective computation:
 * {@link ObjectiveValues} carries raw values only; this class converts a raw
 * value into [0,1] given a (min, max) range.
 *
 * <h3>Formula</h3>
 * <pre>
 *   normalized = (value - min) / (max - min)      when max > min
 *   normalized = 0.5                              when max == min
 * </pre>
 *
 * <p>The max == min case returns 0.5 (the neutral midpoint) because all
 * observed candidates are equal on that objective: it neither rewards nor
 * penalizes any of them.
 *
 * <h3>Out-of-range inputs</h3>
 * A value outside [min, max] is CLAMPED into [0,1] rather than rejected, so
 * that a late candidate slightly beyond a previously computed range cannot
 * produce an out-of-scale score. Range arguments themselves are validated:
 * NaN/infinite ranges and min > max are rejected.
 */
public final class ObjectiveNormalizer {

    /** The value returned when max == min (all candidates equal). */
    public static final double EQUAL_RANGE_NEUTRAL_VALUE = 0.5;

    private ObjectiveNormalizer() {
        // Utility class
    }

    /**
     * Normalize a raw objective value into [0,1].
     *
     * @param value the raw objective value
     * @param min   the minimum raw value across the candidate set
     * @param max   the maximum raw value across the candidate set
     * @return (value-min)/(max-min), or 0.5 when max == min; clamped to [0,1]
     * @throws IllegalArgumentException if min or max is NaN/infinite or min > max
     */
    public static double normalize(double value, double min, double max) {
        if (Double.isNaN(min) || Double.isInfinite(min)) {
            throw new IllegalArgumentException("min must be finite, got: " + min);
        }
        if (Double.isNaN(max) || Double.isInfinite(max)) {
            throw new IllegalArgumentException("max must be finite, got: " + max);
        }
        if (min > max) {
            throw new IllegalArgumentException(
                    "min (" + min + ") cannot exceed max (" + max + ")");
        }

        if (max == min) {
            return EQUAL_RANGE_NEUTRAL_VALUE;
        }

        double normalized = (value - min) / (max - min);
        return clampUnit(normalized);
    }

    /**
     * Normalize one objective across a candidate set.
     *
     * @param values raw values of this objective, one per candidate
     * @param index  index of the candidate to normalize
     * @return the normalized value for values[index]
     * @throws IllegalArgumentException if values is null/empty or index out of bounds
     */
    public static double normalizeAcross(double[] values, int index) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values cannot be null or empty");
        }
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("index out of bounds: " + index);
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException(
                        "objective values must be finite, got: " + v);
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        return normalize(values[index], min, max);
    }

    private static double clampUnit(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
