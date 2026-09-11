package org.cloudbus.cloudsim.examples.stage2g;

import org.cloudbus.cloudsim.examples.stage2f.ObjectiveNormalizer;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pareto machinery for Stage 2G (dominance, non-dominated sorting, crowding
 * distance, environmental selection) — Stage 2G.1 design §7/§8.
 *
 * <p>ALL FOUR objectives are MINIMIZE.
 *
 * <p>Dominance is computed on RAW ObjectiveValues (order-theoretic; scaling
 * cannot change it). Crowding distance is computed on per-objective
 * min-max-normalized values within each front (Stage 2G.1 §7: a diversity
 * heuristic that balances objective scales; NOT required for dominance).
 * Normalization uses the existing Stage 2F {@link ObjectiveNormalizer} —
 * including its 0.5 equal-range rule — never a reimplementation.
 *
 * <p>NOT implemented here (per contract): weighted-sum fitness, any scalar
 * objective aggregation.
 */
public final class Pareto {

    private Pareto() {
        // Utility class
    }

    /** k-th raw objective value (k in 0..3). */
    private static double obj(ObjectiveValues v, int k) {
        switch (k) {
            case 0: return v.getF1ThreatServerRisk();
            case 1: return v.getF2CoResidencyRisk();
            case 2: return v.getF3Cost();
            case 3: return v.getF4UnusedCapacity();
            default: throw new IllegalArgumentException("objective index " + k);
        }
    }

    /**
     * Strict Pareto dominance for MINIMIZATION on raw values:
     * a dominates b iff for all k f_k(a) &le; f_k(b) and for some k
     * f_k(a) &lt; f_k(b).
     *
     * @return true if a dominates b
     */
    public static boolean dominates(ObjectiveValues a, ObjectiveValues b) {
        boolean strictlyBetterInAtLeastOne = false;
        for (int k = 0; k < 4; k++) {
            double va = obj(a, k);
            double vb = obj(b, k);
            if (va > vb) {
                return false;
            }
            if (va < vb) {
                strictlyBetterInAtLeastOne = true;
            }
        }
        return strictlyBetterInAtLeastOne;
    }

    /**
     * Fast non-dominated sorting on raw objective values.
     *
     * @param values objective vectors, one per individual (null entries are
     *               rejected — infeasible candidates must be discarded before
     *               sorting)
     * @return list of fronts (index lists); front 0 is the Pareto front.
     *         Every index appears in exactly one front.
     */
    public static List<List<Integer>> nonDominatedSort(List<ObjectiveValues> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be null or empty");
        }
        for (ObjectiveValues v : values) {
            if (v == null) {
                throw new IllegalArgumentException(
                        "objective values cannot be null (infeasible candidates"
                                + " must be discarded before NDS)");
            }
        }

        int n = values.size();
        int[] dominationCount = new int[n];
        List<List<Integer>> dominated = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            dominated.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                ObjectiveValues vi = values.get(i);
                ObjectiveValues vj = values.get(j);
                if (dominates(vi, vj)) {
                    dominated.get(i).add(j);
                    dominationCount[j]++;
                } else if (dominates(vj, vi)) {
                    // j (later index) dominates i: register i in j's
                    // dominated list AND increment i's dominator count.
                    dominated.get(j).add(i);
                    dominationCount[i]++;
                }
                // equal vectors: neither dominates
            }
        }

        List<List<Integer>> fronts = new ArrayList<>();
        List<Integer> firstFront = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (dominationCount[i] == 0) {
                firstFront.add(i);
            }
        }
        fronts.add(firstFront);

        List<Integer> current = firstFront;
        while (!current.isEmpty()) {
            List<Integer> next = new ArrayList<>();
            for (int i : current) {
                for (int j : dominated.get(i)) {
                    dominationCount[j]--;
                    if (dominationCount[j] == 0) {
                        next.add(j);
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            fronts.add(next);
            current = next;
        }
        return fronts;
    }

    /**
     * Crowding distance for one front, computed on per-objective
     * min-max-normalized values within that front (Stage 2G.1 §7).
     *
     * <p>Boundary solutions (min/max per objective) receive
     * {@link Double#POSITIVE_INFINITY}. Deterministic: ties keep the front's
     * member order.
     *
     * @param frontIndices indices (into the full population) of one front
     * @param values       full population objective vectors
     * @return crowding distances indexed like the full population (only
     *         entries for front members are meaningful; others are 0)
     */
    public static double[] crowdingDistance(List<Integer> frontIndices,
                                            List<ObjectiveValues> values) {
        double[] cd = new double[values.size()];
        int size = frontIndices.size();
        if (size == 0) {
            return cd;
        }
        if (size <= 2) {
            for (int idx : frontIndices) {
                cd[idx] = Double.POSITIVE_INFINITY;
            }
            return cd;
        }

        // normalized[k][m] = objective k of member m, normalized within front
        double[][] norm = new double[4][size];
        for (int k = 0; k < 4; k++) {
            double[] raw = new double[size];
            for (int m = 0; m < size; m++) {
                raw[m] = obj(values.get(frontIndices.get(m)), k);
            }
            double min = raw[0];
            double max = raw[0];
            for (double v : raw) {
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
            for (int m = 0; m < size; m++) {
                // equal-range (max == min) returns the 0.5 neutral value
                norm[k][m] = ObjectiveNormalizer.normalize(raw[m], min, max);
            }
        }

        for (int k = 0; k < 4; k++) {
            Integer[] order = new Integer[size];
            for (int m = 0; m < size; m++) {
                order[m] = m;
            }
            final double[] nk = norm[k];
            Arrays.sort(order, (p, q) -> {
                int c = Double.compare(nk[p], nk[q]);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(p, q);
            });

            int last = size - 1;
            cd[frontIndices.get(order[0])] = Double.POSITIVE_INFINITY;
            cd[frontIndices.get(order[last])] = Double.POSITIVE_INFINITY;

            double denom = nk[order[last]] - nk[order[0]];
            if (denom <= 0.0) {
                // all members equal on this objective: no interior spacing
                continue;
            }
            for (int m = 1; m < last; m++) {
                int prev = order[m - 1];
                int curr = order[m];
                int next = order[m + 1];
                cd[frontIndices.get(curr)] += (nk[next] - nk[prev]) / denom;
            }
        }
        return cd;
    }

    /**
     * Environmental selection (Stage 2G.1 §8): fill the next population
     * front by front; split the overflow front by descending crowding
     * distance (ties: lower combined index first).
     *
     * @param parentIndices   indices into combinedValues of parents
     * @param offspringIndices indices into combinedValues of offspring
     * @param combinedValues  objective vectors for ALL combined individuals
     *                        (parents first, then offspring — the two index
     *                        lists must partition it)
     * @param targetSize      N — resulting population size
     * @return selected indices into combinedValues, in selection order
     */
    public static List<Integer> environmentalSelect(List<Integer> parentIndices,
                                                    List<Integer> offspringIndices,
                                                    List<ObjectiveValues> combinedValues,
                                                    int targetSize) {
        if (combinedValues == null || combinedValues.isEmpty()) {
            throw new IllegalArgumentException("combinedValues cannot be null or empty");
        }
        if (targetSize <= 0) {
            throw new IllegalArgumentException("targetSize must be positive");
        }
        if (parentIndices.size() + offspringIndices.size() != combinedValues.size()) {
            throw new IllegalArgumentException(
                    "parent + offspring indices must partition combinedValues");
        }
        if (parentIndices.size() + offspringIndices.size() < targetSize) {
            throw new IllegalArgumentException(
                    "combined population smaller than target size");
        }

        List<List<Integer>> fronts = nonDominatedSort(combinedValues);

        List<Integer> selected = new ArrayList<>();
        for (List<Integer> front : fronts) {
            if (selected.size() + front.size() <= targetSize) {
                selected.addAll(front);
                if (selected.size() == targetSize) {
                    break;
                }
            } else {
                double[] cd = crowdingDistance(front, combinedValues);
                List<Integer> sortedFront = new ArrayList<>(front);
                sortedFront.sort((p, q) -> {
                    int c = Double.compare(cd[q], cd[p]);
                    if (c != 0) {
                        return c;
                    }
                    return Integer.compare(p, q);
                });
                int needed = targetSize - selected.size();
                selected.addAll(sortedFront.subList(0, needed));
                break;
            }
        }
        return selected;
    }

    /**
     * Sorted set of raw objective vectors of front 0 (for the
     * no-improvement stall check, Stage 2G.1 §9).
     */
    public static List<double[]> frontVectorSet(List<List<Integer>> fronts,
                                                List<ObjectiveValues> values) {
        List<Integer> f1 = fronts.get(0);
        List<double[]> out = new ArrayList<>(f1.size());
        for (int idx : f1) {
            ObjectiveValues v = values.get(idx);
            out.add(new double[]{
                    v.getF1ThreatServerRisk(),
                    v.getF2CoResidencyRisk(),
                    v.getF3Cost(),
                    v.getF4UnusedCapacity()
            });
        }
        out.sort((p, q) -> {
            for (int k = 0; k < 4; k++) {
                int c = Double.compare(p[k], q[k]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        return out;
    }

    /**
     * Exact equality of two front-1 vector sets (deterministic evaluation
     * makes bitwise comparison safe).
     */
    public static boolean vectorSetsEqual(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            for (int k = 0; k < 4; k++) {
                if (Double.compare(a.get(i)[k], b.get(i)[k]) != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
