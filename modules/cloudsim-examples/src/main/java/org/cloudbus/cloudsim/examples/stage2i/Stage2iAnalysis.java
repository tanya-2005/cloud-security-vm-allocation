package org.cloudbus.cloudsim.examples.stage2i;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stage 2I.4 — ANALYSIS-ONLY audit of the stored Stage 2I.3 results.
 *
 * Reads {@code project/results/stage2i3/suite_runs.csv} (the raw 240-run
 * suite output — never re-runs the suite) and writes:
 * <ul>
 *   <li>{@code project/results/stage2i4/validation_report.txt} —
 *       independent structural + metric validation of all rows</li>
 *   <li>{@code project/results/stage2i4/analysis_summary.csv} — per-cell
 *       per-method median/min/max/mean for every metric</li>
 *   <li>{@code project/results/stage2i4/cell_method_summary.csv} — the same
 *       summaries including seed-level values</li>
 *   <li>{@code project/results/stage2i4/paired_method_differences.csv} —
 *       paired per-seed differences (baseline − NSSAO; positive = NSSAO
 *       better on minimization objectives) + per-cell dominance verdicts</li>
 * </ul>
 *
 * No graphs, no significance tests, no weighted score, no thesis
 * conclusions. Observed numbers only.
 */
public final class Stage2iAnalysis {

    private static final Path IN_CSV =
            Paths.get("project/results/stage2i3/suite_runs.csv");
    private static final Path IN_MANIFEST =
            Paths.get("project/results/stage2i3/suite_manifest.csv");
    private static final Path IN_REPRO =
            Paths.get("project/results/stage2i3/repro_runs.csv");
    private static final Path OUT_DIR = Paths.get("project/results/stage2i4");

    private static final String[] METHODS = {
            "proposed_nssao", "b1_ffd_static",
            "b2_bestfit_f4_local_search", "b3_random_feasible_relocation"};
    private static final String[] METRIC_NAMES = {
            "f1", "f2", "f3", "f4",
            "migration_count", "migration_cost",
            "total_coresidency_duration", "highthreat_coresidency_duration",
            "coresidency_episode_count", "active_hosts",
            "cpu_utilization", "memory_utilization", "overall_utilization"};

    // Column indices in the raw CSV (verified against Stage2iRunRecord.HEADER)
    private static final int COL_PROFILE = 3;
    private static final int COL_SCALE = 4;
    private static final int COL_VM_COUNT = 7;
    private static final int COL_HOST_COUNT = 8;
    private static final int COL_THRESHOLD = 9;
    private static final int COL_F1 = 10;
    private static final int COL_F2 = 11;
    private static final int COL_F3 = 12;
    private static final int COL_F4 = 13;
    private static final int COL_MIGRATIONS = 14;
    private static final int COL_MIG_COST = 15;
    private static final int COL_ACTIVE = 16;
    private static final int COL_CPU = 17;
    private static final int COL_MEM = 18;
    private static final int COL_OVERALL = 19;
    private static final int COL_CORES_DUR = 20;
    private static final int COL_HT_DUR = 21;
    private static final int COL_EPISODES = 22;
    private static final int COL_NSSAO_ITERS = 23;
    private static final int COL_STALL = 24;
    private static final int COL_REJ_DEC = 25;
    private static final int COL_FRONT1 = 26;
    private static final int COL_UNIQUE = 27;
    private static final int COL_DUP_REMOVED = 28;
    private static final int COL_RELOC = 29;
    private static final int COL_SEARCH = 30;
    private static final int COL_INIT_FP = 31;
    private static final int COL_FINAL_FP = 32;

    private Stage2iAnalysis() {
    }

    /** One fully parsed stored row. */
    static final class Row {
        final String[] cols;
        final String dataset, cell, profile, scale, method;
        final long seed;
        final int vmCount, hostCount;
        final double threshold;
        final double f1, f2, f3, f4;
        final int migrations;
        final double migCost;
        final int activeHosts;
        final double cpu, mem, overall;
        final double coresDur, htDur;
        final int episodes;
        final String initFp, finalFp;
        final String runKey;
        final boolean parseOk;
        final String parseError;

        Row(String[] cols) {
            this.cols = cols;
            String err = null;
            String ds = null;
            String cell = null;
            String profile = null;
            String scale = null;
            String method = null;
            long seed = -1;
            int vm = -1;
            int hc = -1;
            double thr = Double.NaN;
            double a = Double.NaN, b = Double.NaN, c = Double.NaN,
                    d = Double.NaN;
            int mig = -1;
            double mc = Double.NaN;
            int act = -1;
            double cpu = Double.NaN, mem = Double.NaN, ovr = Double.NaN;
            double cd = Double.NaN, hd = Double.NaN;
            int ep = -1;
            String ifp = null, ffp = null;
            String key = null;
            try {
                ds = cols[1];
                cell = cols[2];
                profile = cols[COL_PROFILE];
                scale = cols[COL_SCALE];
                method = cols[5];
                seed = Long.parseLong(cols[6].trim());
                vm = (int) Double.parseDouble(cols[COL_VM_COUNT]);
                hc = (int) Double.parseDouble(cols[COL_HOST_COUNT]);
                thr = Double.parseDouble(cols[COL_THRESHOLD]);
                a = Double.parseDouble(cols[COL_F1]);
                b = Double.parseDouble(cols[COL_F2]);
                c = Double.parseDouble(cols[COL_F3]);
                d = Double.parseDouble(cols[COL_F4]);
                mig = (int) Double.parseDouble(cols[COL_MIGRATIONS]);
                mc = Double.parseDouble(cols[COL_MIG_COST]);
                act = (int) Double.parseDouble(cols[COL_ACTIVE]);
                cpu = Double.parseDouble(cols[COL_CPU]);
                mem = Double.parseDouble(cols[COL_MEM]);
                ovr = Double.parseDouble(cols[COL_OVERALL]);
                cd = Double.parseDouble(cols[COL_CORES_DUR]);
                hd = Double.parseDouble(cols[COL_HT_DUR]);
                ep = (int) Double.parseDouble(cols[COL_EPISODES]);
                ifp = cols[COL_INIT_FP];
                ffp = cols[COL_FINAL_FP];
                key = ds + "|" + cell + "|" + method + "|" + seed;
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            this.dataset = ds;
            this.cell = cell;
            this.profile = profile;
            this.scale = scale;
            this.method = method;
            this.seed = seed;
            this.vmCount = vm;
            this.hostCount = hc;
            this.threshold = thr;
            this.f1 = a;
            this.f2 = b;
            this.f3 = c;
            this.f4 = d;
            this.migrations = mig;
            this.migCost = mc;
            this.activeHosts = act;
            this.cpu = cpu;
            this.mem = mem;
            this.overall = ovr;
            this.coresDur = cd;
            this.htDur = hd;
            this.episodes = ep;
            this.initFp = ifp;
            this.finalFp = ffp;
            this.runKey = key;
            this.parseOk = err == null;
            this.parseError = err;
        }

        double metric(String name) {
            switch (name) {
                case "f1": return f1;
                case "f2": return f2;
                case "f3": return f3;
                case "f4": return f4;
                case "migration_count": return migrations;
                case "migration_cost": return migCost;
                case "total_coresidency_duration": return coresDur;
                case "highthreat_coresidency_duration": return htDur;
                case "coresidency_episode_count": return episodes;
                case "active_hosts": return activeHosts;
                case "cpu_utilization": return cpu;
                case "memory_utilization": return mem;
                case "overall_utilization": return overall;
                default: throw new IllegalArgumentException(name);
            }
        }

        double obj(int i) {
            switch (i) {
                case 0: return f1;
                case 1: return f2;
                case 2: return f3;
                default: return f4;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        Files.createDirectories(OUT_DIR);

        // ============ load ============
        List<String> lines = Files.readAllLines(IN_CSV);
        List<Row> rows = new ArrayList<>();
        List<String> parseFailures = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (first) {
                first = false; // header
                continue;
            }
            Row r = new Row(Stage2iRunRecord.splitCsv(line));
            if (r.parseOk) {
                rows.add(r);
            } else {
                parseFailures.add(line + " -> " + r.parseError);
            }
        }
        out.println("Loaded rows: " + rows.size()
                + " (parse failures: " + parseFailures.size() + ")");

        // ============ validation ============
        String validation = validate(rows, parseFailures);
        Files.write(OUT_DIR.resolve("validation_report.txt"),
                validation.getBytes(StandardCharsets.UTF_8));
        out.println("Validation report written.");
        out.println(validation.split("\n=== SECTION 5")[0].lines()
                .filter(l -> l.contains("FAIL")).limit(20)
                .reduce((x, y) -> x + "\n" + y).orElse("  (no FAIL lines)"));

        // ============ summaries ============
        writeAnalysisSummary(rows);
        writeCellMethodSummary(rows);
        writePairedDifferences(rows);
        out.println("Analysis outputs written to " + OUT_DIR.toAbsolutePath());
    }

    // ==================================================================
    // Validation (independent re-derivation from stored rows)
    // ==================================================================

    private static String validate(List<Row> rows,
                                   List<String> parseFailures)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("STAGE 2I.4 INDEPENDENT VALIDATION REPORT\n");
        sb.append("=========================================\n");
        sb.append("Input: ").append(IN_CSV).append("\n");
        sb.append("(Analysis-only: the suite was NOT re-run.)\n\n");

        sb.append("=== SECTION 1: structure ===\n");
        sb.append("Rows parsed: ").append(rows.size())
                .append(" / expected 240 -> ")
                .append(rows.size() == 240 ? "OK" : "FAIL").append("\n");
        sb.append("Parse failures: ").append(parseFailures.size());
        for (String pf : parseFailures) {
            sb.append("\n  ").append(pf);
        }
        sb.append("\n");

        Map<String, Integer> keyCounts = new LinkedHashMap<>();
        Set<String> cells = new TreeSet<>();
        Map<String, Set<String>> cellMethods = new TreeMap<>();
        Map<String, Set<Long>> cellMethodSeeds = new TreeMap<>();
        for (Row r : rows) {
            keyCounts.merge(r.runKey, 1, Integer::sum);
            cells.add(r.cell);
            cellMethods.computeIfAbsent(r.cell, k -> new TreeSet<>())
                    .add(r.method);
            cellMethodSeeds
                    .computeIfAbsent(r.cell + "|" + r.method,
                            k -> new TreeSet<>())
                    .add(r.seed);
        }
        long dups = keyCounts.values().stream().filter(v -> v > 1).count();
        sb.append("Unique run keys: ")
                .append(keyCounts.size())
                .append(" / 240; duplicate keys: ").append(dups)
                .append(dups == 0 && keyCounts.size() == 240 ? " -> OK"
                        : " -> FAIL").append("\n");
        sb.append("Distinct cells: ").append(cells.size())
                .append(" / 12 -> ")
                .append(cells.size() == 12 ? "OK" : "FAIL").append("\n");
        sb.append("Cells: ").append(cells).append("\n");

        boolean methodsOk = true;
        boolean seedsOk = true;
        for (String cell : cells) {
            Set<String> ms = cellMethods.getOrDefault(cell,
                    new TreeSet<>());
            if (ms.size() != 4
                    || !ms.containsAll(java.util.Arrays.asList(METHODS))) {
                methodsOk = false;
                sb.append("  FAIL methods in cell ").append(cell)
                        .append(": ").append(ms).append("\n");
            }
            for (String m : METHODS) {
                Set<Long> ss = cellMethodSeeds.get(cell + "|" + m);
                if (ss == null || ss.size() != 5
                        || !ss.equals(new TreeSet<>(java.util.Arrays
                        .asList(1L, 2L, 3L, 4L, 5L)))) {
                    seedsOk = false;
                    sb.append("  FAIL seeds in ").append(cell)
                            .append("/").append(m).append(": ")
                            .append(ss).append("\n");
                }
            }
        }
        sb.append("All 4 methods in every cell: ")
                .append(methodsOk ? "OK" : "FAIL").append("\n");
        sb.append("All 5 seeds (1-5) for every cell x method: ")
                .append(seedsOk ? "OK" : "FAIL").append("\n\n");

        sb.append("=== SECTION 2: metric validity ===\n");
        int badFinite = 0;
        int badB1 = 0;
        int badMigPair = 0;
        int badUtil = 0;
        int badFp = 0;
        int badB3Var = 0;
        List<String> examples = new ArrayList<>();
        Map<String, Set<String>> initFpsByCell = new TreeMap<>();
        for (Row r : rows) {
            double[] objs = {r.f1, r.f2, r.f3, r.f4};
            for (double v : objs) {
                if (!Double.isFinite(v) || v < 0) {
                    badFinite++;
                    if (examples.size() < 10) {
                        examples.add("non-finite/negative objective "
                                + r.runKey);
                    }
                }
            }
            if ("b1_ffd_static".equals(r.method) && r.migrations != 0) {
                badB1++;
                if (examples.size() < 10) {
                    examples.add("B1 migrations=" + r.migrations
                            + " " + r.runKey);
                }
            }
            if (Math.abs(r.migCost - r.migrations) > 1e-9) {
                badMigPair++;
                if (examples.size() < 10) {
                    examples.add("migCost!=count " + r.runKey);
                }
            }
            if (r.cpu < 0 || r.cpu > 1 || r.mem < 0 || r.mem > 1
                    || r.overall < 0 || r.overall > 1) {
                badUtil++;
                if (examples.size() < 10) {
                    examples.add("utilization out of [0,1] " + r.runKey);
                }
            }
            if (r.initFp == null || r.finalFp == null
                    || r.initFp.isEmpty() || r.finalFp.isEmpty()) {
                badFp++;
            }
            initFpsByCell
                    .computeIfAbsent(r.cell, k -> new TreeSet<>())
                    .add(r.initFp);
        }
        sb.append("Objectives finite & non-negative: ")
                .append(badFinite == 0 ? "OK" : "FAIL x" + badFinite)
                .append("\n");
        sb.append("B1 migration count == 0 (all rows): ")
                .append(badB1 == 0 ? "OK" : "FAIL x" + badB1).append("\n");
        sb.append("migration_cost == migration_count (perMigration=1): ")
                .append(badMigPair == 0 ? "OK" : "FAIL x" + badMigPair)
                .append("\n");
        sb.append("Utilizations within [0,1]: ")
                .append(badUtil == 0 ? "OK" : "FAIL x" + badUtil)
                .append("\n");
        sb.append("Fingerprints present on all rows: ")
                .append(badFp == 0 ? "OK" : "FAIL x" + badFp).append("\n");
        for (String e : examples) {
            sb.append("  example: ").append(e).append("\n");
        }

        sb.append("\n=== SECTION 3: fairness fingerprints ===\n");
        // Common initial placement: exactly ONE initial fingerprint per cell
        int mixedInit = 0;
        for (Map.Entry<String, Set<String>> e : initFpsByCell.entrySet()) {
            if (e.getValue().size() != 1) {
                mixedInit++;
                sb.append("  FAIL multiple initial fingerprints in ")
                        .append(e.getKey()).append(": ")
                        .append(e.getValue()).append("\n");
            }
        }
        sb.append("Exactly one initial-placement fingerprint per cell ")
                .append("(all methods share the FFD start): ")
                .append(mixedInit == 0 ? "OK" : "FAIL x" + mixedInit)
                .append("\n");

        // B3 seed sensitivity: different seeds -> different final fingerprints
        Map<String, Set<String>> b3Fps = new TreeMap<>();
        for (Row r : rows) {
            if ("b3_random_feasible_relocation".equals(r.method)) {
                b3Fps.computeIfAbsent(r.cell, k -> new TreeSet<>())
                        .add(r.finalFp);
            }
        }
        int b3Flat = 0;
        for (Map.Entry<String, Set<String>> e : b3Fps.entrySet()) {
            if (e.getValue().size() < 2) {
                b3Flat++;
                sb.append("  NOTE B3 final fingerprints identical across ")
                        .append("seeds in ").append(e.getKey())
                        .append(" (seed stream had no feasible effect?)\n");
            }
        }
        sb.append("B3 final placement varies across seeds: ")
                .append(b3Flat == 0 ? "OK (all cells)" : b3Flat
                        + " cell(s) flat -> see NOTEs").append("\n");

        // Utilization identity check: overall == (cpu+mem)/2
        int badIdentity = 0;
        for (Row r : rows) {
            if (Math.abs(r.overall - (r.cpu + r.mem) / 2.0) > 1e-6) {
                badIdentity++;
            }
        }
        sb.append("overall_utilization == (cpu+mem)/2 identity: ")
                .append(badIdentity == 0 ? "OK" : "FAIL x" + badIdentity)
                .append("\n");

        sb.append("\n=== SECTION 4: NSSAO search telemetry ===\n");
        int propRows = 0;
        int stallTrue = 0;
        int frontMissing = 0;
        double itersMin = Double.MAX_VALUE;
        double itersMax = -1;
        for (Row r : rows) {
            if (!"proposed_nssao".equals(r.method)) {
                continue;
            }
            propRows++;
            try {
                double iters = Double.parseDouble(
                        r.cols[COL_NSSAO_ITERS]);
                if (iters < itersMin) {
                    itersMin = iters;
                }
                if (iters > itersMax) {
                    itersMax = iters;
                }
                if ("true".equalsIgnoreCase(r.cols[COL_STALL].trim())) {
                    stallTrue++;
                }
                Double.parseDouble(r.cols[COL_FRONT1]);
            } catch (Exception e) {
                frontMissing++;
            }
        }
        sb.append("Proposed rows: ").append(propRows).append("\n");
        sb.append("NSSAO iterations range: ")
                .append(propRows == 0 ? "n/a"
                        : itersMin + ".." + itersMax).append("\n");
        sb.append("stopped_by_stall=true: ").append(stallTrue)
                .append(" of ").append(propRows).append("\n");
        sb.append("Front/telemetry fields missing: ")
                .append(frontMissing).append("\n");

        sb.append("\n=== SECTION 5: reproducibility artifacts ===\n");
        if (Files.exists(IN_REPRO)) {
            List<Row> repro = new ArrayList<>();
            List<String> rl = Files.readAllLines(IN_REPRO);
            for (String line : rl) {
                if (!line.isBlank()) {
                    Row r = new Row(Stage2iRunRecord.splitCsv(line));
                    if (r.parseOk) {
                        repro.add(r);
                    }
                }
            }
            Map<String, Row> primary = new LinkedHashMap<>();
            for (Row r : rows) {
                primary.putIfAbsent(r.runKey, r);
            }
            int match = 0;
            int mismatch = 0;
            for (Row rr : repro) {
                Row pr = primary.get(rr.runKey);
                if (pr == null) {
                    mismatch++;
                    continue;
                }
                boolean same = Math.abs(pr.f1 - rr.f1) < 1e-9
                        && Math.abs(pr.f2 - rr.f2) < 1e-9
                        && Math.abs(pr.f3 - rr.f3) < 1e-9
                        && Math.abs(pr.f4 - rr.f4) < 1e-9
                        && pr.migrations == rr.migrations
                        && pr.initFp.equals(rr.initFp)
                        && pr.finalFp.equals(rr.finalFp);
                if (same) {
                    match++;
                } else {
                    mismatch++;
                }
            }
            sb.append("repro_runs.csv rows: ").append(repro.size()).append("\n");
            sb.append("Match vs primary: ").append(match)
                    .append("; mismatch: ").append(mismatch)
                    .append(mismatch == 0 ? " -> OK" : " -> FAIL").append("\n");
        } else {
            sb.append("repro_runs.csv not found — skipped\n");
        }
        return sb.toString();
    }

    // ==================================================================
    // Outputs
    // ==================================================================

    private static final class Stat {
        double median, min, max, mean, sd;
        int n;
    }

    private static Stat stat(List<Double> vals) {
        Stat s = new Stat();
        s.n = vals.size();
        List<Double> sorted = new ArrayList<>(vals);
        sorted.sort(Comparator.naturalOrder());
        s.min = sorted.get(0);
        s.max = sorted.get(sorted.size() - 1);
        int m = sorted.size() / 2;
        s.median = sorted.size() % 2 == 1
                ? sorted.get(m)
                : (sorted.get(m - 1) + sorted.get(m)) / 2.0;
        double sum = 0;
        for (double v : vals) {
            sum += v;
        }
        s.mean = sum / vals.size();
        double ss = 0;
        for (double v : vals) {
            ss += (v - s.mean) * (v - s.mean);
        }
        s.sd = vals.size() > 1 ? Math.sqrt(ss / (vals.size() - 1)) : 0;
        return s;
    }

    private static List<String> sortedCells(List<Row> rows) {
        Set<String> cells = new TreeSet<>();
        for (Row r : rows) {
            cells.add(r.cell);
        }
        return new ArrayList<>(cells);
    }

    /** Per-cell per-method rows keyed cell|method. */
    private static Map<String, List<Row>> byCellMethod(List<Row> rows) {
        Map<String, List<Row>> map = new TreeMap<>();
        for (Row r : rows) {
            map.computeIfAbsent(r.cell + "|" + r.method,
                    k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    private static void writeAnalysisSummary(List<Row> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("cell,method,n");
        for (String m : METRIC_NAMES) {
            sb.append(",").append(m).append("_median")
                    .append(",").append(m).append("_min")
                    .append(",").append(m).append("_max")
                    .append(",").append(m).append("_mean")
                    .append(",").append(m).append("_sd");
        }
        sb.append("\n");
        for (String cell : sortedCells(rows)) {
            for (String method : METHODS) {
                List<Row> rs = new ArrayList<>();
                for (Row r : rows) {
                    if (r.cell.equals(cell) && r.method.equals(method)) {
                        rs.add(r);
                    }
                }
                if (rs.isEmpty()) {
                    continue;
                }
                sb.append(cell).append(",").append(method).append(",")
                        .append(rs.size());
                for (String m : METRIC_NAMES) {
                    List<Double> vals = new ArrayList<>();
                    for (Row r : rs) {
                        vals.add(r.metric(m));
                    }
                    Stat s = stat(vals);
                    sb.append(String.format(",%.9f,%.9f,%.9f,%.9f,%.9f",
                            s.median, s.min, s.max, s.mean, s.sd));
                }
                sb.append("\n");
            }
        }
        Files.write(OUT_DIR.resolve("analysis_summary.csv"),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeCellMethodSummary(List<Row> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("cell,profile,scale,vm_count,host_count,threshold,");
        sb.append("method,seed,f1,f2,f3,f4,migration_count,migration_cost,");
        sb.append("total_coresidency_duration,highthreat_coresidency_duration,");
        sb.append("coresidency_episode_count,active_hosts,cpu_utilization,");
        sb.append("memory_utilization,overall_utilization,init_fp,final_fp\n");
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparing((Row r) -> r.cell)
                .thenComparing(r -> r.method)
                .thenComparing(r -> r.seed));
        for (Row r : sorted) {
            sb.append(r.cell).append(",").append(r.profile).append(",")
                    .append(r.scale).append(",").append(r.vmCount)
                    .append(",").append(r.hostCount).append(",")
                    .append(String.format("%.9f", r.threshold)).append(",")
                    .append(r.method).append(",").append(r.seed)
                    .append(String.format(
                            ",%.9f,%.9f,%.9f,%.9f,%d,%.9f,%.9f,%.9f,%d,%d,"
                                    + "%.9f,%.9f,%.9f,%s,%s",
                            r.f1, r.f2, r.f3, r.f4, r.migrations,
                            r.migCost, r.coresDur, r.htDur, r.episodes,
                            r.activeHosts, r.cpu, r.mem, r.overall,
                            q(r.initFp), q(r.finalFp)))
                    .append("\n");
        }
        Files.write(OUT_DIR.resolve("cell_method_summary.csv"),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String q(String s) {
        return s == null ? "" : "\"" + s + "\"";
    }

    private static void writePairedDifferences(List<Row> rows)
            throws IOException {
        Map<String, List<Row>> cm = byCellMethod(rows);
        StringBuilder sb = new StringBuilder();
        sb.append("# Paired per-seed differences: baseline MINUS proposed_nssao");
        sb.append("\n");
        sb.append("# Positive value = proposed_nssao has the SMALLER value");
        sb.append(" (better for minimization objectives f1-f4, migration");
        sb.append(" count/cost, co-residency duration)");
        sb.append("\n");
        sb.append("# f4 note: f4 = 1 - overall utilization, so LOWER f4 =");
        sb.append(" HIGHER utilization = better packing");
        sb.append("\n");
        sb.append("cell,comparison,metric,n_pairs,median_diff,min_diff,");
        sb.append("max_diff,nssao_better,nssao_worse,tied\n");

        // Dominance section collected while iterating
        StringBuilder domSb = new StringBuilder();
        domSb.append("# Per-cell objective dominance on seed-matched medians");
        domSb.append(" (all objectives minimization)\n");
        domSb.append("# 'X dominates Y' = X <= Y on f1,f2,f3,f4 and < on >= 1\n");
        domSb.append("cell,pair,verdict\n");

        for (String cell : sortedCells(rows)) {
            List<Row> propRows = cm.get(cell + "|proposed_nssao");
            if (propRows == null) {
                continue;
            }
            Map<Long, Row> propBySeed = new TreeMap<>();
            for (Row r : propRows) {
                propBySeed.put(r.seed, r);
            }
            for (String method : METHODS) {
                if (method.equals("proposed_nssao")) {
                    continue;
                }
                List<Row> baseRows = cm.get(cell + "|" + method);
                if (baseRows == null) {
                    continue;
                }
                Map<Long, Row> baseBySeed = new TreeMap<>();
                for (Row r : baseRows) {
                    baseBySeed.put(r.seed, r);
                }
                // Paired per-seed differences per metric
                String[] diffMetrics = {"f1", "f2", "f3", "f4",
                        "migration_count", "migration_cost",
                        "total_coresidency_duration",
                        "highthreat_coresidency_duration",
                        "coresidency_episode_count"};
                for (String metric : diffMetrics) {
                    List<Double> diffs = new ArrayList<>();
                    int better = 0;
                    int worse = 0;
                    int tied = 0;
                    for (Long seed : propBySeed.keySet()) {
                        Row pb = baseBySeed.get(seed);
                        if (pb == null) {
                            continue;
                        }
                        double d = pb.metric(metric)
                                - propBySeed.get(seed).metric(metric);
                        diffs.add(d);
                        if (d > 1e-12) {
                            better++;
                        } else if (d < -1e-12) {
                            worse++;
                        } else {
                            tied++;
                        }
                    }
                    if (diffs.isEmpty()) {
                        continue;
                    }
                    Stat s = stat(diffs);
                    sb.append(cell).append(",")
                            .append(method).append("-minus-nssao,")
                            .append(metric).append(",").append(s.n)
                            .append(String.format(
                                    ",%.9f,%.9f,%.9f,%d,%d,%d",
                                    s.median, s.min, s.max,
                                    better, worse, tied))
                            .append("\n");
                }
            }
            // Dominance on medians (raw objective support only)
            Map<String, double[]> medians = new TreeMap<>();
            for (String method : METHODS) {
                List<Row> rs = cm.get(cell + "|" + method);
                if (rs == null) {
                    continue;
                }
                double[] med = new double[4];
                for (int i = 0; i < 4; i++) {
                    List<Double> vals = new ArrayList<>();
                    for (Row r : rs) {
                        vals.add(r.obj(i));
                    }
                    med[i] = stat(vals).median;
                }
                medians.put(method, med);
            }
            for (String a : METHODS) {
                for (String b : METHODS) {
                    if (a.equals(b) || !medians.containsKey(a)
                            || !medians.containsKey(b)) {
                        continue;
                    }
                    double[] ma = medians.get(a);
                    double[] mb = medians.get(b);
                    boolean le = true;
                    boolean lt = false;
                    for (int i = 0; i < 4; i++) {
                        // NO break: le and lt must be evaluated over ALL
                        // objectives independently
                        if (ma[i] > mb[i] + 1e-12) {
                            le = false;
                        }
                        if (ma[i] < mb[i] - 1e-12) {
                            lt = true;
                        }
                    }
                    String verdict;
                    if (le && lt) {
                        verdict = "DOMINATES";
                    } else if (le) {
                        verdict = "EQUAL-OR-BETTER";
                    } else if (lt) {
                        verdict = "TRADE-OFF";
                    } else {
                        verdict = "DOMINATED-BY";
                    }
                    domSb.append(cell).append(",").append(a)
                            .append("-vs-").append(b).append(",")
                            .append(verdict).append("\n");
                }
            }
        }
        sb.append("\n").append(domSb);
        Files.write(OUT_DIR.resolve("paired_method_differences.csv"),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
