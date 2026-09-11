package org.cloudbus.cloudsim.examples.stage2i;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stage 2I.2 — deterministic summarizer (Stage 2I.1 §8).
 *
 * <p>Computes ONLY descriptive statistics from the raw runs CSV:
 * <ul>
 *   <li>Per (cell, method): median, min, max, mean, sd of f1–f4.</li>
 *   <li>Paired per-seed differences (proposed − baseline) of f1–f4.</li>
 *   <li>Method-level dominance per cell: method X dominates Y iff X is
 *       better-or-equal on all four objectives and strictly better on ≥ 1
 *       (comparison on per-cell MEDIANS).</li>
 * </ul>
 *
 * <p>NO p-values, NO significance claims, NO thesis conclusions — by design
 * (Stage 2I.1 §8).
 */
public final class Stage2iSummarizer {

    private static final String[] OBJECTIVES =
            {"f1", "f2", "f3", "f4"};

    private Stage2iSummarizer() {
    }

    /** One raw run row (parsed). */
    static final class Run {
        final String dataset;
        final String cell;
        final String method;
        final long seed;
        final double[] obj; // f1..f4
        final int migrations;
        final int activeHosts;
        final String initialFingerprint;
        final String finalFingerprint;
        final String runKey;

        Run(String[] cols) {
            this.dataset = cols[1];
            this.cell = cols[2];
            this.method = cols[5];
            this.seed = Long.parseLong(cols[6]);
            // f1..f4 are columns 10..13 in the raw schema (0-based):
            // 0 schema_version, 1 dataset, 2 cell, 3 profile, 4 scale,
            // 5 method, 6 seed, 7 vm_count, 8 host_count, 9 threshold,
            // 10 f1, 11 f2, 12 f3, 13 f4
            this.obj = new double[]{
                    Double.parseDouble(cols[10]),
                    Double.parseDouble(cols[11]),
                    Double.parseDouble(cols[12]),
                    Double.parseDouble(cols[13])
            };
            this.migrations = (int) Double.parseDouble(cols[14]);
            this.activeHosts = (int) Double.parseDouble(cols[16]);
            this.initialFingerprint = cols[31];
            this.finalFingerprint = cols[32];
            this.runKey = cols[1] + "|" + cols[2] + "|" + cols[5]
                    + "|" + cols[6];
        }
    }

    public static List<Run> loadRuns(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        List<Run> runs = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (first) {
                first = false;
                continue; // header
            }
            runs.add(new Run(Stage2iRunRecord.splitCsv(line)));
        }
        return runs;
    }

    /** Write all summary tables as text. */
    public static void writeSummaries(List<Run> runs, Path outPath)
            throws IOException {
        List<String> out = new ArrayList<>();

        out.add("STAGE 2I DESCRIPTIVE SUMMARY (no significance claims)");
        out.add("====================================================");
        out.add("");

        // === Table 1: per-cell per-method objective summaries ===
        out.add("TABLE 1 — per-cell per-method summaries (median [min, max] / mean (sd))");
        Map<String, List<Run>> byCellMethod = group(runs,
                r -> r.cell + " :: " + r.method);
        List<String> cellMethodKeys = new ArrayList<>(byCellMethod.keySet());
        cellMethodKeys.sort(Comparator.naturalOrder());
        for (String key : cellMethodKeys) {
            List<Run> rs = byCellMethod.get(key);
            out.add("  " + key);
            for (int o = 0; o < 4; o++) {
                double[] vals = new double[rs.size()];
                for (int i = 0; i < rs.size(); i++) {
                    vals[i] = rs.get(i).obj[o];
                }
                out.add(String.format(
                        "    %s: median=%.9f  min=%.9f  max=%.9f  mean=%.9f  sd=%.9f",
                        OBJECTIVES[o],
                        median(vals), min(vals), max(vals),
                        mean(vals), sd(vals)));
            }
        }
        out.add("");

        // === Table 2: paired per-seed differences (proposed − baseline) ===
        out.add("TABLE 2 — paired per-seed differences (proposed − baseline)");
        Map<String, List<Run>> byCell = group(runs, r -> r.cell);
        List<String> cells = new ArrayList<>(byCell.keySet());
        cells.sort(Comparator.naturalOrder());
        for (String cell : cells) {
            List<Run> cellRuns = byCell.get(cell);
            Map<Long, Run> proposedBySeed = byMethodSeed(cellRuns,
                    Stage2iScenario.Method.PROPOSED.label);
            if (proposedBySeed.isEmpty()) {
                continue;
            }
            for (String base : new String[]{
                    Stage2iScenario.Method.B1_FFD_STATIC.label,
                    Stage2iScenario.Method.B2_BESTFIT_F4LS.label,
                    Stage2iScenario.Method.B3_RANDOM_RELOC.label}) {
                Map<Long, Run> baseBySeed = byMethodSeed(cellRuns, base);
                if (baseBySeed.isEmpty()) {
                    continue;
                }
                List<Long> seeds = new ArrayList<>(proposedBySeed.keySet());
                seeds.retainAll(baseBySeed.keySet());
                seeds.sort(Comparator.naturalOrder());
                if (seeds.isEmpty()) {
                    continue;
                }
                out.add("  " + cell + " : " + base);
                for (int o = 0; o < 4; o++) {
                    double[] diffs = new double[seeds.size()];
                    StringBuilder perSeed = new StringBuilder();
                    for (int i = 0; i < seeds.size(); i++) {
                        diffs[i] = proposedBySeed.get(seeds.get(i)).obj[o]
                                - baseBySeed.get(seeds.get(i)).obj[o];
                        if (i > 0) {
                            perSeed.append(", ");
                        }
                        perSeed.append(String.format("s%d:%.6f",
                                seeds.get(i), diffs[i]));
                    }
                    out.add(String.format(
                            "    d%s: median=%.9f  min=%.9f  max=%.9f  [%s]",
                            OBJECTIVES[o],
                            median(diffs), min(diffs), max(diffs),
                            perSeed));
                }
            }
        }
        out.add("");

        // === Table 3: method-level dominance per cell (medians) ===
        out.add("TABLE 3 — method-level dominance per cell (on medians;"
                        + " better-or-equal on all f1–f4 and strictly better on ≥1)");
        String[] methods = {
                Stage2iScenario.Method.PROPOSED.label,
                Stage2iScenario.Method.B1_FFD_STATIC.label,
                Stage2iScenario.Method.B2_BESTFIT_F4LS.label,
                Stage2iScenario.Method.B3_RANDOM_RELOC.label
        };
        for (String cell : cells) {
            List<Run> cellRuns = byCell.get(cell);
            Map<String, double[]> medians = new TreeMap<>();
            for (String m : methods) {
                List<Run> rs = new ArrayList<>();
                for (Run r : cellRuns) {
                    if (r.method.equals(m)) {
                        rs.add(r);
                    }
                }
                if (rs.isEmpty()) {
                    continue;
                }
                double[] med = new double[4];
                for (int o = 0; o < 4; o++) {
                    double[] vals = new double[rs.size()];
                    for (int i = 0; i < rs.size(); i++) {
                        vals[i] = rs.get(i).obj[o];
                    }
                    med[o] = median(vals);
                }
                medians.put(m, med);
            }
            out.add("  " + cell);
            for (String a : medians.keySet()) {
                for (String b : medians.keySet()) {
                    if (a.equals(b)) {
                        continue;
                    }
                    String rel = dominance(medians.get(a), medians.get(b));
                    if (rel != null) {
                        out.add("    " + a + " dominates " + b);
                    }
                }
            }
        }
        out.add("");
        out.add("END OF SUMMARY (descriptive only; no p-values;"
                + " no significance claims)");

        Files.createDirectories(outPath.getParent());
        Files.write(outPath, out);
    }

    /** Dominance of a over b: null if not dominating. */
    static String dominance(double[] a, double[] b) {
        boolean strictlyBetter = false;
        for (int o = 0; o < 4; o++) {
            if (a[o] > b[o] + 1e-12) {
                return null; // worse on one objective: no dominance
            }
            if (a[o] < b[o] - 1e-12) {
                strictlyBetter = true;
            }
        }
        return strictlyBetter ? "dominates" : null;
    }

    // === helpers ===

    private interface KeyFn {
        String key(Run r);
    }

    private static Map<String, List<Run>> group(List<Run> runs, KeyFn fn) {
        Map<String, List<Run>> out = new LinkedHashMap<>();
        for (Run r : runs) {
            out.computeIfAbsent(fn.key(r), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static Map<Long, Run> byMethodSeed(List<Run> runs,
                                               String method) {
        Map<Long, Run> out = new LinkedHashMap<>();
        for (Run r : runs) {
            if (r.method.equals(method)) {
                out.put(r.seed, r);
            }
        }
        return out;
    }

    static double median(double[] vals) {
        double[] sorted = vals.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n == 0) {
            return Double.NaN;
        }
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    static double min(double[] vals) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : vals) {
            m = Math.min(m, v);
        }
        return m;
    }

    static double max(double[] vals) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : vals) {
            m = Math.max(m, v);
        }
        return m;
    }

    static double mean(double[] vals) {
        double sum = 0;
        for (double v : vals) {
            sum += v;
        }
        return sum / vals.length;
    }

    static double sd(double[] vals) {
        if (vals.length < 2) {
            return 0;
        }
        double mu = mean(vals);
        double acc = 0;
        for (double v : vals) {
            acc += (v - mu) * (v - mu);
        }
        return Math.sqrt(acc / (vals.length - 1));
    }
}
