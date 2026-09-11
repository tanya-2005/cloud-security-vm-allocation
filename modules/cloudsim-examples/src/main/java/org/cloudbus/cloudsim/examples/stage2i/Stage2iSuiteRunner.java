package org.cloudbus.cloudsim.examples.stage2i;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stage 2I.3 — execute the APPROVED experiment suite from
 * {@code project/docs/stage_2i1_experiment_design.md} (§4.4, §6):
 *
 * <pre>
 *   12 cells  (A,B,C,D,E) x {azure, alibaba} at scale M   (10 cells)
 *           + scale-F cells B-azure-L and B-alibaba-L      ( 2 cells)
 *   x 4 methods (proposed_nssao, B1, B2, B3)
 *   x 5 seeds  (1..5, the approved design seeds)
 *   = 240 runs
 * </pre>
 *
 * Design decisions (documented in stage_2i3_experiment_run.md):
 * <ul>
 *   <li>Seeds are exactly the five approved values 1..5 (the concrete seed
 *       list of Stage 2I.1 §6; the 2I.2 implementation records the seed per
 *       run, which is what the CSV schema carries).</li>
 *   <li>Raw results go to a dedicated Stage 2I.3 directory
 *       ({@code project/results/stage2i3/}); Stage 2I.2 smoke outputs are
 *       never touched.</li>
 *   <li>A run that throws is recorded verbatim (run key + full stack trace)
 *       in {@code suite_failures.log} and the suite CONTINUES — every run is
 *       independent (fresh engine/state per run), so continuation is safe.
 *       Failures are never replaced with synthetic results.</li>
 *   <li>Resume-safe: run keys already present in the CSV are skipped, so an
 *       interrupted suite can be re-invoked without duplicating or silently
 *       overwriting rows (the runner itself refuses duplicate keys).</li>
 *   <li>After execution the driver validates: row count, duplicate keys,
 *       full cell coverage, metric sanity (finite non-negative objectives,
 *       B1 zero migrations), and a same-seed reproducibility re-run on a
 *       representative subset.</li>
 * </ul>
 *
 * NO graphs, NO thesis conclusions, NO significance claims are produced
 * here — raw results and validation only (HARD STOP after 2I.3).
 */
public final class Stage2iSuiteRunner {

    // === Approved suite definition (Stage 2I.1 §4.4) ===
    private static final long[] APPROVED_SEEDS = {1L, 2L, 3L, 4L, 5L};

    private static final Path RESULTS_DIR = Paths.get("project/results/stage2i3");
    private static final Path CSV_PATH = RESULTS_DIR.resolve("suite_runs.csv");
    private static final Path FAILURES_LOG = RESULTS_DIR.resolve("suite_failures.log");
    private static final Path MANIFEST_PATH = RESULTS_DIR.resolve("suite_manifest.csv");

    private Stage2iSuiteRunner() {
    }

    /**
     * Run key in the CSV/record format (dataset|cell|method|seed) — the
     * format used by {@link Stage2iRunRecord} storage and parsed rows.
     * ({@code RunConfig.runKey()} is the shorter cell|method|seed display
     * form; both identify the same run.)
     */
    private static String csvKey(Stage2iRunner.RunConfig cfg) {
        return cfg.spec.dataset.label + "|" + cfg.spec.cellId()
                + "|" + cfg.method.label + "|" + cfg.seed;
    }

    /**
     * Objective equality at CSV precision: stored values are parsed from
     * the 9-decimal formatted text, so exact double equality is not a
     * meaningful reproducibility criterion (wall-clock runtime is excluded
     * from comparison entirely).
     */
    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    /** The 12 approved scenario cells, in deterministic order. */
    static List<Stage2iScenario.ScenarioSpec> approvedCells() {
        List<Stage2iScenario.ScenarioSpec> cells = new ArrayList<>();
        for (Stage2iScenario.Dataset ds : new Stage2iScenario.Dataset[] {
                Stage2iScenario.Dataset.AZURE,
                Stage2iScenario.Dataset.ALIBABA}) {
            for (Stage2iScenario.Profile p : Stage2iScenario.Profile.values()) {
                cells.add(new Stage2iScenario.ScenarioSpec(ds, p,
                        Stage2iScenario.Scale.M));
            }
        }
        // Approved scale-F cells (profile B at scale L)
        cells.add(new Stage2iScenario.ScenarioSpec(
                Stage2iScenario.Dataset.AZURE,
                Stage2iScenario.Profile.B, Stage2iScenario.Scale.L));
        cells.add(new Stage2iScenario.ScenarioSpec(
                Stage2iScenario.Dataset.ALIBABA,
                Stage2iScenario.Profile.B, Stage2iScenario.Scale.L));
        return cells;
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        out.println("===================================================");
        out.println("STAGE 2I.3 — APPROVED EXPERIMENT SUITE (240 runs)");
        out.println("===================================================");

        Files.createDirectories(RESULTS_DIR);

        // === Suite manifest (scenario metadata for reproducibility) ===
        writeManifest(out);

        // === Build the full structured run plan ===
        List<Stage2iScenario.ScenarioSpec> cells = approvedCells();
        List<Stage2iRunner.RunConfig> plan = new ArrayList<>();
        for (Stage2iScenario.ScenarioSpec spec : cells) {
            for (Stage2iScenario.Method method : Stage2iScenario.Method.values()) {
                for (long seed : APPROVED_SEEDS) {
                    plan.add(new Stage2iRunner.RunConfig(spec, method, seed));
                }
            }
        }
        int expected = plan.size();
        out.println("Planned runs: " + expected
                + " (" + cells.size() + " cells x 4 methods x 5 seeds)");

        // === Resume support: collect already-recorded run keys ===
        Set<String> done = new LinkedHashSet<>();
        if (Files.exists(CSV_PATH)) {
            for (Stage2iSummarizer.Run r : Stage2iSummarizer.loadRuns(CSV_PATH)) {
                done.add(r.runKey);
            }
        }
        if (!done.isEmpty()) {
            out.println("Resuming: " + done.size()
                    + " run(s) already recorded in " + CSV_PATH);
        }

        if (Files.exists(FAILURES_LOG)) {
            // keep previous failures for the record; do not silently clear
            out.println("Existing failure log preserved: " + FAILURES_LOG);
        }

        // === Execute ===
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        long suiteStart = System.nanoTime();
        for (int i = 0; i < plan.size(); i++) {
            Stage2iRunner.RunConfig cfg = plan.get(i);
            if (done.contains(csvKey(cfg))) {
                skipped++;
                continue;
            }
            long t0 = System.nanoTime();
            try {
                Stage2iRunner.runOne(cfg, CSV_PATH, false);
                completed++;
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                out.printf("[%3d/%3d] OK   %-42s %-28s seed=%d (%d ms)%n",
                        i + 1, expected, cfg.spec.cellId(),
                        cfg.method.label, cfg.seed, ms);
            } catch (Exception e) {
                failed++;
                recordFailure(cfg, e);
                out.printf("[%3d/%3d] FAIL %-42s %-28s seed=%d : %s%n",
                        i + 1, expected, cfg.spec.cellId(),
                        cfg.method.label, cfg.seed, e);
            }
        }
        long suiteMs = (System.nanoTime() - suiteStart) / 1_000_000L;

        out.println();
        out.println("Execution summary: completed=" + completed
                + " failed=" + failed + " skippedAlreadyDone=" + skipped
                + " suiteRuntimeMs=" + suiteMs);

        // === Validation ===
        validateSuite(out, plan);

        out.println();
        out.println("Raw results: " + CSV_PATH.toAbsolutePath());
        out.println("Manifest:    " + MANIFEST_PATH.toAbsolutePath());
        out.println("Failures:    " + FAILURES_LOG.toAbsolutePath()
                + (Files.exists(FAILURES_LOG) ? "" : " (none recorded)"));
        out.println("HARD STOP: raw suite + validation only — no analysis,"
                + " no graphs, no conclusions (Stage 2I.4 is out of scope).");
    }

    // ==================================================================
    // Manifest
    // ==================================================================

    private static void writeManifest(PrintStream out) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("cell_id,dataset,profile,scale,vm_count,host_count,"
                + "adaptive_threshold,entity_pool_size,pool_expanded,"
                + "fleet_mips,fleet_ram,threshold_method");
        for (Stage2iScenario.ScenarioSpec spec : approvedCells()) {
            Stage2iScenario.ScenarioData data = Stage2iScenario.build(spec);
            long fleetMips = 0;
            long fleetRam = 0;
            for (org.cloudbus.cloudsim.Host h : data.hosts) {
                for (org.cloudbus.cloudsim.Pe pe : h.getPeList()) {
                    fleetMips += (long) pe.getMips();
                }
                fleetRam += h.getRam();
            }
            lines.add(spec.cellId() + ","
                    + spec.dataset.label + ","
                    + spec.profile.name() + ","
                    + spec.scale.name() + ","
                    + data.vmCount() + ","
                    + data.hostCount() + ","
                    + String.format("%.9f", data.adaptiveThreshold) + ","
                    + data.entityPoolSize + ","
                    + data.poolExpanded + ","
                    + fleetMips + ","
                    + fleetRam + ","
                    + "AdaptiveThreatThreshold(Stage2E defaults; "
                    + "tailWeight=0.5, bounds=[0.5,0.9])");
        }
        Files.write(MANIFEST_PATH, lines, StandardCharsets.UTF_8);
        out.println("Suite manifest written: " + MANIFEST_PATH.toAbsolutePath()
                + " (" + (lines.size() - 1) + " cells)");
    }

    // ==================================================================
    // Failure recording
    // ==================================================================

    private static synchronized void recordFailure(
            Stage2iRunner.RunConfig cfg, Exception e) {
        try {
            List<String> lines = new ArrayList<>();
            if (!Files.exists(FAILURES_LOG)) {
                lines.add("Failure log — Stage 2I.3 approved suite");
                lines.add("One block per failed run; failures are recorded,"
                        + " never replaced by synthetic results.");
            }
            lines.add("-----------------------------------------------");
            lines.add("timestamp: "
                    + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            lines.add("runKey: " + cfg.runKey());
            lines.add("cell: " + cfg.spec.cellId()
                    + " method: " + cfg.method.label
                    + " seed: " + cfg.seed);
            lines.add("exception: " + e.getClass().getName());
            lines.add("message: " + e.getMessage());
            for (StackTraceElement ste : e.getStackTrace()) {
                lines.add("    at " + ste);
            }
            Files.write(FAILURES_LOG, lines, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException io) {
            System.err.println("Could not record failure: " + io);
        }
    }

    // ==================================================================
    // Validation
    // ==================================================================

    private static void validateSuite(PrintStream out,
                                      List<Stage2iRunner.RunConfig> plan)
            throws IOException {
        out.println();
        out.println("=== SUITE VALIDATION ===");

        if (!Files.exists(CSV_PATH)) {
            out.println("VALIDATION BLOCKER: no results CSV at " + CSV_PATH);
            return;
        }
        List<Stage2iSummarizer.Run> rows =
                Stage2iSummarizer.loadRuns(CSV_PATH);
        out.println("Total result rows: " + rows.size()
                + " (expected " + plan.size() + ")");

        // --- run-key uniqueness ---
        Map<String, Integer> keyCounts = new LinkedHashMap<>();
        for (Stage2iSummarizer.Run r : rows) {
            keyCounts.merge(r.runKey, 1, Integer::sum);
        }
        Set<String> duplicates = new TreeSet<>();
        for (Map.Entry<String, Integer> e : keyCounts.entrySet()) {
            if (e.getValue() > 1) {
                duplicates.add(e.getKey() + " (x" + e.getValue() + ")");
            }
        }
        out.println("Duplicate run keys: " + duplicates.size()
                + (duplicates.isEmpty() ? " (none)"
                : " -> " + duplicates));

        // --- expected-key coverage ---
        Set<String> expectedKeys = new TreeSet<>();
        for (Stage2iRunner.RunConfig cfg : plan) {
            expectedKeys.add(csvKey(cfg));
        }
        Set<String> presentKeys = new TreeSet<>(keyCounts.keySet());
        Set<String> missing = new TreeSet<>(expectedKeys);
        missing.removeAll(presentKeys);
        Set<String> unexpected = new TreeSet<>(presentKeys);
        unexpected.removeAll(expectedKeys);
        out.println("Missing expected keys: " + missing.size()
                + (missing.isEmpty() ? " (none)" : " -> " + missing));
        out.println("Unexpected extra keys: " + unexpected.size()
                + (unexpected.isEmpty() ? " (none)" : " -> " + unexpected));

        // --- per-dataset / per-method run counts ---
        out.println("Per-dataset run counts:");
        Map<String, Integer> byDataset = new LinkedHashMap<>();
        for (Stage2iSummarizer.Run r : rows) {
            byDataset.merge(r.dataset, 1, Integer::sum);
        }
        byDataset.forEach((k, v) -> out.println("  " + k + ": " + v));
        out.println("Per-method run counts:");
        Map<String, Integer> byMethod = new LinkedHashMap<>();
        for (Stage2iSummarizer.Run r : rows) {
            byMethod.merge(r.method, 1, Integer::sum);
        }
        byMethod.forEach((k, v) -> out.println("  " + k + ": " + v));
        out.println("Per-cell run counts:");
        Map<String, Integer> byCell = new LinkedHashMap<>();
        for (Stage2iSummarizer.Run r : rows) {
            byCell.merge(r.cell, 1, Integer::sum);
        }
        new ArrayList<>(byCell.keySet()).stream().sorted()
                .forEach(k -> out.println("  " + k + ": " + byCell.get(k)));

        // --- metric sanity on every row ---
        int sanityViolations = 0;
        List<String> violations = new ArrayList<>();
        for (Stage2iSummarizer.Run r : rows) {
            boolean ok = true;
            StringBuilder why = new StringBuilder();
            for (double v : r.obj) {
                if (!Double.isFinite(v) || v < 0) {
                    ok = false;
                    why.append(" nonFiniteOrNegativeObjective");
                    break;
                }
            }
            if (r.migrations < 0) {
                ok = false;
                why.append(" negativeMigrations");
            }
            if ("b1_ffd_static".equals(r.method) && r.migrations != 0) {
                ok = false;
                why.append(" B1_migrations=").append(r.migrations);
            }
            if (r.activeHosts < 1) {
                ok = false;
                why.append(" activeHosts=").append(r.activeHosts);
            }
            if (r.initialFingerprint == null || r.finalFingerprint == null
                    || r.initialFingerprint.isEmpty()
                    || r.finalFingerprint.isEmpty()) {
                ok = false;
                why.append(" missingFingerprint");
            }
            if (!ok) {
                sanityViolations++;
                if (violations.size() < 20) {
                    violations.add(r.runKey + why);
                }
            }
        }
        out.println("Metric sanity violations: " + sanityViolations
                + (violations.isEmpty() ? " (none)"
                : " -> " + violations));

        // --- same-seed reproducibility on a representative subset ---
        out.println("Reproducibility re-run (representative subset):");
        // Re-runs are appended to a separate DERIVED csv so the primary
        // suite CSV is never churned by validation (no duplicate-key
        // conflict with the stored rows).
        Path reproCsv = RESULTS_DIR.resolve("repro_runs.csv");
        try {
            Files.deleteIfExists(reproCsv);
        } catch (IOException io) {
            out.println("  (could not clear " + reproCsv + ": " + io + ")");
        }
        List<Stage2iRunner.RunConfig> reproSubset = reproSubset(plan);
        int reproChecked = 0;
        int reproMismatches = 0;
        Map<String, Stage2iSummarizer.Run> byKey =
                new LinkedHashMap<>();
        for (Stage2iSummarizer.Run r : rows) {
            byKey.putIfAbsent(r.runKey, r);
        }
        for (Stage2iRunner.RunConfig cfg : reproSubset) {
            Stage2iSummarizer.Run stored = byKey.get(csvKey(cfg));
            if (stored == null) {
                out.println("  SKIP (no stored row) " + csvKey(cfg));
                continue;
            }
            try {
                Stage2iRunRecord again =
                        Stage2iRunner.runOne(cfg, reproCsv, false);
                reproChecked++;
                boolean same =
                        near(stored.obj[0], again.getF1())
                                && near(stored.obj[1], again.getF2())
                                && near(stored.obj[2], again.getF3())
                                && near(stored.obj[3], again.getF4())
                                && stored.migrations == again.getMigrationCount()
                                && stored.initialFingerprint
                                .equals(again.getInitialFingerprint())
                                && stored.finalFingerprint
                                .equals(again.getFinalFingerprint());
                if (same) {
                    out.println("  MATCH " + csvKey(cfg));
                } else {
                    reproMismatches++;
                    out.println("  MISMATCH " + csvKey(cfg));
                }
            } catch (Exception e) {
                reproMismatches++;
                out.println("  ERROR re-running " + csvKey(cfg)
                        + " : " + e);
            }
        }
        out.println("Reproducibility: checked=" + reproChecked
                + " mismatches=" + reproMismatches);

        out.println("=== VALIDATION COMPLETE ===");
    }

    /**
     * Representative reproducibility subset: per dataset, the B-M cell and
     * the scale-F cell; within each, all 4 methods at seeds 1 and 3
     * (covering deterministic methods and both randomized ones).
     */    private static List<Stage2iRunner.RunConfig> reproSubset(
            List<Stage2iRunner.RunConfig> plan) {
        List<Stage2iRunner.RunConfig> subset = new ArrayList<>();
        for (Stage2iRunner.RunConfig cfg : plan) {
            boolean cellOfInterest =
                    (cfg.spec.profile == Stage2iScenario.Profile.B
                            && cfg.spec.scale == Stage2iScenario.Scale.M)
                            || cfg.spec.scale == Stage2iScenario.Scale.L;
            boolean seedOfInterest =
                    cfg.seed == 1L || cfg.seed == 3L;
            if (cellOfInterest && seedOfInterest) {
                subset.add(cfg);
            }
        }
        return subset;
    }
}
