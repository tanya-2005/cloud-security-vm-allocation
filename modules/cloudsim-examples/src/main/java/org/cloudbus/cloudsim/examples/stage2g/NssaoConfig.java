package org.cloudbus.cloudsim.examples.stage2g;

/**
 * NSSAO configuration (Stage 2G.2).
 *
 * <p>Every parameter from the approved Stage 2G.1 design
 * (project/docs/stage_2g1_nssao_design.md) is exposed here; nothing is
 * hard-coded inside the optimizer. Defaults are the documented Stage 2G.1
 * values.
 *
 * <p><b>PAPER-FAITHFUL vs ADAPTED</b> (per Stage 2G.1 §10 parameter table):
 * <ul>
 *   <li>maxIterations = 10 — PAPER-FAITHFUL (paper's t_max = 10).</li>
 *   <li>populationSize default 40 — ADAPTATION of scale (paper used 2000
 *       for ~30k VMs / 160 servers; Stage 2G runs at validation scale).</li>
 *   <li>Sub-population split: even at t=0 (PAPER-FAITHFUL); P_b shrinking
 *       linearly to 0 at t_max is the documented ADAPTATION schedule
 *       |P_b|(t) = round(N/2 · (1 − t/t_max)).</li>
 *   <li>Melting anneal M_max=1.0 → M_min=0.1 — documented ADAPTATION
 *       (paper's degree-day formula is not recoverable).</li>
 *   <li>stallLimit = 3 — documented ADAPTATION (paper: "preset number",
 *       unspecified).</li>
 * </ul>
 *
 * <p>Dimension (number of movable VMs) and host count H are supplied at
 * construction from the scenario, not hard-coded.
 */
public final class NssaoConfig {

    private final int populationSize;
    private final int maxIterations;
    private final long seed;
    private final int stallLimit;
    private final double meltingFactorMax;
    private final double meltingFactorMin;
    private final double highThreatThreshold;

    private NssaoConfig(Builder builder) {
        this.populationSize = builder.populationSize;
        this.maxIterations = builder.maxIterations;
        this.seed = builder.seed;
        this.stallLimit = builder.stallLimit;
        this.meltingFactorMax = builder.meltingFactorMax;
        this.meltingFactorMin = builder.meltingFactorMin;
        this.highThreatThreshold = builder.highThreatThreshold;
    }

    /**
     * N — population size (Stage 2G.1 default: 40; paper: 2000).
     */
    public int getPopulationSize() {
        return populationSize;
    }

    /**
     * t_max — maximum iterations (paper-faithful default: 10).
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Deterministic RNG seed. Same seed + same scenario ⇒ identical run.
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Consecutive stalled iterations before early stop (design default 3).
     */
    public int getStallLimit() {
        return stallLimit;
    }

    /**
     * Melting factor at t=0 (design default 1.0).
     */
    public double getMeltingFactorMax() {
        return meltingFactorMax;
    }

    /**
     * Melting factor at t=t_max (design default 0.1).
     */
    public double getMeltingFactorMin() {
        return meltingFactorMin;
    }

    /**
     * High threat threshold for classifying VMs (default 0.5).
     * Used by Stage 2H for co-residency duration classification.
     */
    public double getHighThreatThreshold() {
        return highThreatThreshold;
    }

    /**
     |P_b|(t) = round(N/2 · (1 − t/t_max)) — Stage 2G.1 §3 linear swap.
     * Exploration gets the remainder: |P_a|(t) = N − |P_b|(t).
     *
     * @param t current iteration (0-based)
     * @param dimension solution dimension (unused here; kept for signature
     *                  stability)
     * @return exploitation sub-population size at iteration t
     */
    public int exploitationSize(int t, int dimension) {
        if (maxIterations <= 0) {
            return 0;
        }
        double fraction = 1.0 - ((double) t / (double) maxIterations);
        return (int) Math.round(populationSize / 2.0 * fraction);
    }

    /**
     * |P_a|(t) = N − |P_b|(t). The two sub-populations always partition N.
     */
    public int explorationSize(int t, int dimension) {
        return populationSize - exploitationSize(t, dimension);
    }

    /**
     * Melting factor at iteration t: linear anneal M_max → M_min
     * (Stage 2G.1 §4.5 documented assumption).
     */
    public double meltingFactor(int t) {
        if (maxIterations <= 0) {
            return meltingFactorMin;
        }
        double tClamped = Math.max(0, Math.min(t, maxIterations));
        return meltingFactorMax
                - (meltingFactorMax - meltingFactorMin)
                        * (tClamped / (double) maxIterations);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int populationSize = 40;
        private int maxIterations = 10;
        private long seed = 42L;
        private int stallLimit = 3;
        private double meltingFactorMax = 1.0;
        private double meltingFactorMin = 0.1;
        private double highThreatThreshold = 0.5;

        public Builder populationSize(int n) {
            if (n < 4) {
                throw new IllegalArgumentException(
                        "populationSize must be >= 4 (need >=2 per sub-population), got: " + n);
            }
            this.populationSize = n;
            return this;
        }

        public Builder maxIterations(int tMax) {
            if (tMax < 1) {
                throw new IllegalArgumentException(
                        "maxIterations must be >= 1, got: " + tMax);
            }
            this.maxIterations = tMax;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder stallLimit(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException(
                        "stallLimit must be >= 0, got: " + limit);
            }
            this.stallLimit = limit;
            return this;
        }

        public Builder meltingFactorMax(double mMax) {
            if (Double.isNaN(mMax) || Double.isInfinite(mMax)) {
                throw new IllegalArgumentException("meltingFactorMax must be finite");
            }
            this.meltingFactorMax = mMax;
            return this;
        }

        public Builder meltingFactorMin(double mMin) {
            if (Double.isNaN(mMin) || Double.isInfinite(mMin)) {
                throw new IllegalArgumentException("meltingFactorMin must be finite");
            }
            this.meltingFactorMin = mMin;
            return this;
        }

        public NssaoConfig build() {
            if (meltingFactorMin > meltingFactorMax) {
                throw new IllegalArgumentException(
                        "meltingFactorMin cannot exceed meltingFactorMax");
            }
            return new NssaoConfig(this);
        }
    }
}
