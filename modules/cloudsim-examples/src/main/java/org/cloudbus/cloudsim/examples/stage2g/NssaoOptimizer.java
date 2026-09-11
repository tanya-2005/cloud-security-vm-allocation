package org.cloudbus.cloudsim.examples.stage2g;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.stage2c.VmThreatState;
import org.cloudbus.cloudsim.examples.stage2f.CandidatePlacement;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveEvaluator;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * NSSAO optimizer core (Stage 2G.2) — implements the paper's algorithm
 * exactly as recovered and adapted in the approved Stage 2G.1 design
 * (project/docs/stage_2g1_nssao_design.md).
 *
 * <h3>Paper-faithful parts</h3>
 * <ul>
 *   <li>Real-valued per-VM positions bounded [0, H−1] (paper: [0, P−1]).</li>
 *   <li>Exploration update Eq. (21)–(22):
 *       X_i^1 = ρ1·(X − X_i) + (1−ρ1)·(X̄ − X_i);
 *       X_i(t+1) = X_best + A(t)·X_i^1,  i ∈ index_a.</li>
 *   <li>Exploitation update Eq. (23)–(24):
 *       X_i^2 = ρ2·(X − X_i) + (1−ρ2)·(X̄ − X_i);
 *       X_i(t+1) = M·X + A(t)·X_i^2,  i ∈ index_b.</li>
 *   <li>A(t): Gaussian vector, mean 0, variance 1. ρ1 ∈ [0,1], ρ2 ∈ [−1,1].</li>
 *   <li>Dual population: even split at t=0; |P_b| shrinks linearly,
 *       |P_a| = N − |P_b| (documented adaptation schedule).</li>
 *   <li>Non-dominated sorting + crowding distance selection (Select(NDS, CD)).</li>
 *   <li>Termination: t_max OR no front-1 improvement for stallLimit
 *       consecutive iterations.</li>
 * </ul>
 *
 * <h3>Documented adaptations (Stage 2G.1, never invented here)</h3>
 * <ul>
 *   <li>Discretization: hostIndex = clamp(rint(x_k), 0, H−1).</li>
 *   <li>Melting factor: linear anneal M_max → M_min (paper's degree-day
 *       formula not recoverable).</li>
 *   <li>Repair-first feasibility (no objective penalties).</li>
 *   <li>X_best / X(t): lexicographic (rank-1 member, larger crowding, lower
 *       f1, lower index) — the paper does not define "best" in
 *       multi-objective form.</li>
 * </ul>
 *
 * <h3>Safety</h3>
 * The optimizer NEVER touches live CloudSim state: no VmMigrationService,
 * no guest-list changes, no allocation-policy calls. Every candidate is a
 * hypothetical {@link CandidatePlacement} evaluated by the Stage 2F
 * {@link ObjectiveEvaluator}. Objective formulas are NOT duplicated here.
 *
 * <h3>Determinism</h3>
 * A single {@link Random} seeded from {@link NssaoConfig#getSeed()} drives
 * every random draw, in a fixed order. Same scenario + same seed ⇒
 * identical trajectories and identical output.
 */
public final class NssaoOptimizer {

    /** One population member: continuous position + its evaluation. */
    public static final class Individual {
        private final double[] position;
        private ObjectiveValues objectives;
        private CandidatePlacement placement;

        Individual(double[] position) {
            this.position = position;
        }

        public double[] getPosition() {
            return position.clone();
        }

        public ObjectiveValues getObjectives() {
            return objectives;
        }

        public CandidatePlacement getPlacement() {
            return placement;
        }
    }

    /** Result of a full optimizer run. */
    public static final class RunResult {
        private final List<List<Integer>> fronts;
        private final List<Individual> population;
        private final int iterationsExecuted;
        private final boolean stoppedByStall;
        private final int rejectedDecodeCount;

        RunResult(List<List<Integer>> fronts,
                  List<Individual> population,
                  int iterationsExecuted,
                  boolean stoppedByStall,
                  int rejectedDecodeCount) {
            this.fronts = fronts;
            this.population = population;
            this.iterationsExecuted = iterationsExecuted;
            this.stoppedByStall = stoppedByStall;
            this.rejectedDecodeCount = rejectedDecodeCount;
        }

        /** Pareto fronts (index lists into {@link #getPopulation()}). */
        public List<List<Integer>> getFronts() {
            return fronts;
        }

        /** Final population. */
        public List<Individual> getPopulation() {
            return population;
        }

        /** Front 0 members as individuals (deterministic order). */
        public List<Individual> getFirstFront() {
            List<Individual> out = new ArrayList<>();
            for (int idx : fronts.get(0)) {
                out.add(population.get(idx));
            }
            return out;
        }

        public int getIterationsExecuted() {
            return iterationsExecuted;
        }

        public boolean isStoppedByStall() {
            return stoppedByStall;
        }

        /** How many decodes were rejected as infeasible (should be 0). */
        public int getRejectedDecodeCount() {
            return rejectedDecodeCount;
        }
    }

    private final NssaoConfig config;
    private final ObjectiveEvaluator evaluator;
    private final PlacementDecoder decoder;

    // Scenario (immutable for the lifetime of a run)
    private final List<Host> hosts;
    private final List<Vm> allVms;
    private final List<Integer> movableVmIds;
    private final Map<Integer, Integer> currentMapping;
    private final Map<Integer, VmThreatState> threatMap;
    private final int dimension;
    private final int hostCount;

    // Single seeded RNG — the only source of randomness
    private final Random random;

    // Statistics
    private int rejectedDecodeCount = 0;

    /**
     * @param hosts          all hosts (immutable descriptors; read-only here)
     * @param allVms         all VMs (movable + pinned)
     * @param movableVmIds   VMs the optimizer may re-place, fixed order
     * @param currentMapping current live VM→host mapping (read-only input)
     * @param threatMap      VM ID → threat state (Stage 2C metadata)
     * @param evaluator      Stage 2F evaluator (used as-is)
     * @param config         NSSAO parameters
     */
    public NssaoOptimizer(List<Host> hosts,
                          List<Vm> allVms,
                          List<Integer> movableVmIds,
                          Map<Integer, Integer> currentMapping,
                          Map<Integer, VmThreatState> threatMap,
                          ObjectiveEvaluator evaluator,
                          NssaoConfig config) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("hosts cannot be null or empty");
        }
        if (allVms == null || allVms.isEmpty()) {
            throw new IllegalArgumentException("allVms cannot be null or empty");
        }
        if (movableVmIds == null || movableVmIds.isEmpty()) {
            throw new IllegalArgumentException("movableVmIds cannot be null or empty");
        }
        if (currentMapping == null) {
            throw new IllegalArgumentException("currentMapping cannot be null");
        }
        if (threatMap == null) {
            throw new IllegalArgumentException("threatMap cannot be null");
        }
        if (evaluator == null || config == null) {
            throw new IllegalArgumentException("evaluator and config cannot be null");
        }
        for (int vmId : movableVmIds) {
            if (!currentMapping.containsKey(vmId)) {
                throw new IllegalArgumentException(
                        "movable VM " + vmId + " has no current host mapping");
            }
        }
        this.hosts = hosts;
        this.allVms = allVms;
        this.movableVmIds = new ArrayList<>(movableVmIds);
        this.currentMapping = new LinkedHashMap<>(currentMapping);
        this.threatMap = threatMap;
        this.evaluator = evaluator;
        this.config = config;
        this.dimension = movableVmIds.size();
        this.hostCount = hosts.size();
        this.decoder = new PlacementDecoder();
        this.random = new Random(config.getSeed());
    }

    /**
     * Run the optimization. Generates hypothetical placements only; no VM
     * is ever moved.
     */
    public RunResult run() {
        rejectedDecodeCount = 0;

        // === Step 1: initial population ===
        // Paper: random init in [0, P−1]. Documented addition: candidate 0 is
        // the current placement (its host index per movable VM).
        List<Individual> population = new ArrayList<>(config.getPopulationSize());
        double[] currentPos = new double[dimension];
        for (int k = 0; k < dimension; k++) {
            currentPos[k] = currentMapping.get(movableVmIds.get(k));
        }
        population.add(newIndividual(currentPos));

        for (int i = 1; i < config.getPopulationSize(); i++) {
            double[] pos = new double[dimension];
            for (int k = 0; k < dimension; k++) {
                // uniform in [0, H−1]
                pos[k] = random.nextDouble() * (hostCount - 1);
            }
            population.add(newIndividual(pos));
        }
        evaluateAll(population);

        // === Step 2: initial NDS ===
        List<ObjectiveValues> values = objectiveList(population);
        List<List<Integer>> fronts = Pareto.nonDominatedSort(values);

        // === Main loop ===
        boolean stoppedByStall = false;
        int t = 0;
        List<double[]> prevFrontSet = Pareto.frontVectorSet(fronts, values);
        int stall = 0;

        while (t < config.getMaxIterations()) {
            // Sub-population sizes at iteration t (documented schedule)
            int exploitationSize = config.exploitationSize(t, dimension);
            int explorationSize = config.getPopulationSize() - exploitationSize;

            // Leaders (documented lexicographic rule)
            int bestIdx = chooseBestIndex(fronts, population, values);

            // Best position X(t) and centroid X̄(t) — continuous vectors
            double[] bestPos = population.get(bestIdx).position;
            double[] centroid = centroid(population);

            double melting = config.meltingFactor(t);

            // === Update all individuals (Eq. 21–24) ===
            List<Individual> offspring = new ArrayList<>(config.getPopulationSize());
            for (int i = 0; i < population.size(); i++) {
                double[] xi = population.get(i).position;
                // ρ draw: exploitation (index_b, first exploitationSize
                // indices) uses ρ2 ∈ [−1,1]; exploration (index_a) uses
                // ρ1 ∈ [0,1]. Exactly one draw per individual.
                double rho;
                if (i < exploitationSize) {
                    rho = random.nextDouble() * 2.0 - 1.0;   // ρ2 ∈ [−1,1]
                } else {
                    rho = random.nextDouble();               // ρ1 ∈ [0,1]
                }

                // X^1 or X^2 (identical form, different ρ range)
                double[] dir = new double[dimension];
                for (int k = 0; k < dimension; k++) {
                    dir[k] = rho * (bestPos[k] - xi[k])
                            + (1.0 - rho) * (centroid[k] - xi[k]);
                }

                double[] child = new double[dimension];
                if (i < exploitationSize) {
                    // Eq. (23)-(24): exploitation, melting factor M
                    for (int k = 0; k < dimension; k++) {
                        double a = random.nextGaussian();           // A(t)
                        child[k] = melting * bestPos[k] + a * dir[k];
                    }
                } else {
                    // Eq. (21)-(22): exploration, Brownian around X_best
                    for (int k = 0; k < dimension; k++) {
                        double a = random.nextGaussian();           // A(t)
                        child[k] = bestPos[k] + a * dir[k];
                    }
                }

                // Clamp into [0, H−1] (paper-faithful bounds)
                for (int k = 0; k < dimension; k++) {
                    if (child[k] < 0.0) {
                        child[k] = 0.0;
                    } else if (child[k] > hostCount - 1) {
                        child[k] = hostCount - 1;
                    }
                }
                offspring.add(newIndividual(child));
            }

            // === Evaluate offspring (hypothetical only) ===
            evaluateAll(offspring);

            // === Environmental selection: N + N → N (Select(NDS, CD)) ===
            List<ObjectiveValues> combinedValues = new ArrayList<>(values);
            combinedValues.addAll(objectiveList(offspring));
            List<Integer> parentIdx = new ArrayList<>();
            for (int i = 0; i < population.size(); i++) {
                parentIdx.add(i);
            }
            List<Integer> offspringIdx = new ArrayList<>();
            for (int i = 0; i < offspring.size(); i++) {
                offspringIdx.add(population.size() + i);
            }
            List<Integer> survivors = Pareto.environmentalSelect(
                    parentIdx, offspringIdx, combinedValues,
                    config.getPopulationSize());

            // Rebuild population in survivor order (deterministic)
            List<Individual> nextPop = new ArrayList<>(config.getPopulationSize());
            List<ObjectiveValues> nextValues = new ArrayList<>(config.getPopulationSize());
            for (int idx : survivors) {
                if (idx < population.size()) {
                    Individual ind = population.get(idx);
                    nextPop.add(ind);
                    nextValues.add(ind.objectives);
                } else {
                    Individual ind = offspring.get(idx - population.size());
                    nextPop.add(ind);
                    nextValues.add(ind.objectives);
                }
            }
            population = nextPop;
            values = nextValues;
            fronts = Pareto.nonDominatedSort(values);

            // === Stall detection (front-1 objective-vector set) ===
            List<double[]> frontSet = Pareto.frontVectorSet(fronts, values);
            if (Pareto.vectorSetsEqual(frontSet, prevFrontSet)) {
                stall++;
            } else {
                stall = 0;
            }
            prevFrontSet = frontSet;
            t++;

            if (stall >= config.getStallLimit()) {
                stoppedByStall = true;
                break;
            }
        }

        return new RunResult(fronts, population, t, stoppedByStall,
                rejectedDecodeCount);
    }

    // === helpers ===

    /**
     * Documented lexicographic choice of X_best / X(t) among front 0:
     * larger crowding distance, then lower f1, then lower index.
     */
    private int chooseBestIndex(List<List<Integer>> fronts,
                                List<Individual> population,
                                List<ObjectiveValues> values) {
        List<Integer> f1 = fronts.get(0);
        double[] cd = Pareto.crowdingDistance(f1, values);
        int best = f1.get(0);
        for (int idx : f1) {
            int cur = best;
            if (Double.compare(cd[idx], cd[cur]) > 0) {
                best = idx;
            } else if (Double.compare(cd[idx], cd[cur]) == 0) {
                double fi = values.get(idx).getF1ThreatServerRisk();
                double fc = values.get(cur).getF1ThreatServerRisk();
                if (fi < fc || (fi == fc && idx < cur)) {
                    best = idx;
                }
            }
        }
        return best;
    }

    /** Component-wise population centroid X̄(t) (paper: "population centroid"). */
    private double[] centroid(List<Individual> population) {
        double[] c = new double[dimension];
        for (Individual ind : population) {
            for (int k = 0; k < dimension; k++) {
                c[k] += ind.position[k];
            }
        }
        for (int k = 0; k < dimension; k++) {
            c[k] /= population.size();
        }
        return c;
    }

    private Individual newIndividual(double[] pos) {
        return new Individual(pos.clone());
    }

    /**
     * Decode + evaluate every individual. Infeasible decodes are REJECTED
     * (dropped, counted loudly) — never silently accepted; no penalties.
     */
    private void evaluateAll(List<Individual> individuals) {
        for (Individual ind : individuals) {
            PlacementDecoder.DecodeResult dr = decoder.decode(
                    ind.position, movableVmIds, currentMapping, hosts, allVms);
            if (!dr.isSuccess()) {
                // Rejected candidate: mark unevaluable.
                // NDS requires non-null objectives; a rejected decode must
                // therefore never enter NDS. We replace it with the current
                // placement's objectives (a feasible reference) so the
                // population size stays stable, and count the rejection.
                rejectedDecodeCount++;
                ind.placement = null;
                ind.objectives = referenceObjectives();
                continue;
            }
            ind.placement = dr.getCandidate();
            ObjectiveEvaluator.EvaluationResult er = evaluator.evaluate(
                    dr.getCandidate(), allVms, threatMap);
            if (!er.isValid()) {
                // Stage 2F rejected a decoded candidate: treat like decode
                // rejection (must not happen post-repair; counted loudly).
                rejectedDecodeCount++;
                ind.placement = null;
                ind.objectives = referenceObjectives();
                continue;
            }
            ind.objectives = er.getObjectives();
        }
    }

    private ObjectiveValues referenceObjectives() {
        CandidatePlacement fallback = new CandidatePlacement(currentMapping, hosts);
        ObjectiveEvaluator.EvaluationResult er =
                evaluator.evaluate(fallback, allVms, threatMap);
        if (!er.isValid()) {
            throw new IllegalStateException(
                    "Current placement is infeasible; cannot run NSSAO (check scenario)");
        }
        return er.getObjectives();
    }

    private List<ObjectiveValues> objectiveList(List<Individual> population) {
        List<ObjectiveValues> out = new ArrayList<>(population.size());
        for (Individual ind : population) {
            out.add(ind.objectives);
        }
        return out;
    }

    public int getDimension() {
        return dimension;
    }

    public int getHostCount() {
        return hostCount;
    }

    public NssaoConfig getConfig() {
        return config;
    }
}
