package org.cloudbus.cloudsim.examples.stage2i;

import org.cloudbus.cloudsim.examples.stage2d.MigrationEvent;
import org.cloudbus.cloudsim.examples.stage2f.ObjectiveValues;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stage 2I.2 — per-run record + raw results CSV storage.
 *
 * <p>Schema (one row per run) distinguishes NSSAO-optimization metrics
 * (proposed method only) from post-hoc CloudSim metrics (all methods).
 * Unavailable metrics are recorded as the literal token
 * {@link #UNAVAILABLE} — never fabricated.
 *
 * <p>Storage policy: the writer APPENDS to the results CSV and refuses to
 * overwrite an existing run row (same dataset/scenario/method/seed) unless
 * {@code allowReplace} is set; previous results are never silently lost.
 */
public final class Stage2iRunRecord {

    public static final String UNAVAILABLE = "NA";

    // === Row schema (v1) ===
    public static final String HEADER = String.join(",",
            "schema_version",
            "dataset", "scenario_cell", "profile", "scale",
            "method", "seed",
            "vm_count", "host_count", "adaptive_threshold",
            // Primary objectives (evaluated on the FINAL placement)
            "f1", "f2", "f3", "f4",
            // Post-hoc CloudSim metrics
            "migration_count", "migration_cost", "active_hosts",
            "cpu_utilization", "memory_utilization", "overall_utilization",
            "total_coresidency_duration", "highthreat_coresidency_duration",
            "coresidency_episode_count",
            // NSSAO optimization metrics (proposed only; NA for baselines)
            "nssao_iterations", "stopped_by_stall", "rejected_decodes",
            "front1_raw_size", "unique_placements", "duplicates_removed",
            "relocations", "search_attempts",
            // Provenance
            "initial_placement_fingerprint", "final_placement_fingerprint",
            "runtime_ms",
            "repair_count", "repair_rate",
            "notes");

    public static final int SCHEMA_VERSION = 1;

    private final String dataset;
    private final String scenarioCell;
    private final String profile;
    private final String scale;
    private final String method;
    private final long seed;
    private final int vmCount;
    private final int hostCount;
    private final double adaptiveThreshold;
    private final double f1;
    private final double f2;
    private final double f3;
    private final double f4;
    private final int migrationCount;
    private final double migrationCost;
    private final int activeHosts;
    private final double cpuUtilization;
    private final double memoryUtilization;
    private final double overallUtilization;
    private final double totalCoResidencyDuration;
    private final double highThreatCoResidencyDuration;
    private final int coResidencyEpisodeCount;
    private final Integer nssaoIterations;
    private final Boolean stoppedByStall;
    private final Integer rejectedDecodes;
    private final Integer front1RawSize;
    private final Integer uniquePlacements;
    private final Integer duplicatesRemoved;
    private final Integer relocations;
    private final Integer searchAttempts;
    private final String initialFingerprint;
    private final String finalFingerprint;
    private final long runtimeMs;
    private final String notes;

    private Stage2iRunRecord(Builder b) {
        this.dataset = b.dataset;
        this.scenarioCell = b.scenarioCell;
        this.profile = b.profile;
        this.scale = b.scale;
        this.method = b.method;
        this.seed = b.seed;
        this.vmCount = b.vmCount;
        this.hostCount = b.hostCount;
        this.adaptiveThreshold = b.adaptiveThreshold;
        this.f1 = b.f1;
        this.f2 = b.f2;
        this.f3 = b.f3;
        this.f4 = b.f4;
        this.migrationCount = b.migrationCount;
        this.migrationCost = b.migrationCost;
        this.activeHosts = b.activeHosts;
        this.cpuUtilization = b.cpuUtilization;
        this.memoryUtilization = b.memoryUtilization;
        this.overallUtilization = b.overallUtilization;
        this.totalCoResidencyDuration = b.totalCoResidencyDuration;
        this.highThreatCoResidencyDuration = b.highThreatCoResidencyDuration;
        this.coResidencyEpisodeCount = b.coResidencyEpisodeCount;
        this.nssaoIterations = b.nssaoIterations;
        this.stoppedByStall = b.stoppedByStall;
        this.rejectedDecodes = b.rejectedDecodes;
        this.front1RawSize = b.front1RawSize;
        this.uniquePlacements = b.uniquePlacements;
        this.duplicatesRemoved = b.duplicatesRemoved;
        this.relocations = b.relocations;
        this.searchAttempts = b.searchAttempts;
        this.initialFingerprint = b.initialFingerprint;
        this.finalFingerprint = b.finalFingerprint;
        this.runtimeMs = b.runtimeMs;
        this.notes = b.notes == null ? "" : b.notes;
    }

    private static String fmt(double d) {
        return String.format("%.9f", d);
    }

    private static String orNa(Object value) {
        return value == null ? UNAVAILABLE : String.valueOf(value);
    }

    /** CSV-escape a string (quotes, commas, newlines). */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public String toCsvRow() {
        return String.join(",",
                String.valueOf(SCHEMA_VERSION),
                esc(dataset), esc(scenarioCell), esc(profile), esc(scale),
                esc(method), String.valueOf(seed),
                String.valueOf(vmCount), String.valueOf(hostCount),
                fmt(adaptiveThreshold),
                fmt(f1), fmt(f2), fmt(f3), fmt(f4),
                String.valueOf(migrationCount), fmt(migrationCost),
                String.valueOf(activeHosts),
                fmt(cpuUtilization), fmt(memoryUtilization),
                fmt(overallUtilization),
                fmt(totalCoResidencyDuration),
                fmt(highThreatCoResidencyDuration),
                String.valueOf(coResidencyEpisodeCount),
                orNa(nssaoIterations), orNa(stoppedByStall),
                orNa(rejectedDecodes), orNa(front1RawSize),
                orNa(uniquePlacements), orNa(duplicatesRemoved),
                orNa(relocations), orNa(searchAttempts),
                esc(initialFingerprint), esc(finalFingerprint),
                String.valueOf(runtimeMs),
                // repair instrumentation lives in Stage 2G internals and is
                // intentionally NOT fabricated: recorded as unavailable
                UNAVAILABLE, UNAVAILABLE,
                esc(notes));
    }

    // === Key for duplicate detection ===
    public String runKey() {
        return dataset + "|" + scenarioCell + "|" + method + "|" + seed;
    }

    // === Read accessors (used by smoke test / summarizer tooling) ===
    public String getMethod() { return method; }
    public long getSeed() { return seed; }
    public double getF1() { return f1; }
    public double getF2() { return f2; }
    public double getF3() { return f3; }
    public double getF4() { return f4; }
    public int getMigrationCount() { return migrationCount; }
    public int getActiveHosts() { return activeHosts; }
    public double getCpuUtilization() { return cpuUtilization; }
    public double getMemoryUtilization() { return memoryUtilization; }
    public double getOverallUtilization() { return overallUtilization; }
    public Integer getRelocations() { return relocations; }
    public Integer getSearchAttempts() { return searchAttempts; }
    public String getInitialFingerprint() { return initialFingerprint; }
    public String getFinalFingerprint() { return finalFingerprint; }
    public String getScenarioCell() { return scenarioCell; }
    public String getDataset() { return dataset; }
    public double getAdaptiveThreshold() { return adaptiveThreshold; }
    public int getVmCount() { return vmCount; }
    public int getHostCount() { return hostCount; }

    // === CSV storage ===

    /**
     * Append a run row to the results CSV, creating the file (with header)
     * if needed. Existing rows for the same runKey cause an
     * {@code IllegalStateException} unless {@code allowReplace} removes them
     * first (replacement is explicit, never silent).
     */
    public static void appendTo(Path csvPath, Stage2iRunRecord record,
                                boolean allowReplace) throws IOException {
        List<String> lines;
        if (Files.exists(csvPath)) {
            lines = new ArrayList<>(Files.readAllLines(csvPath));
        } else {
            lines = new ArrayList<>();
            lines.add(HEADER);
        }

        String newRow = record.toCsvRow();
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.equals(HEADER)) {
                kept.add(line);
                continue;
            }
            String existingKey = extractRunKey(line);
            if (existingKey != null && existingKey.equals(record.runKey())) {
                if (!allowReplace) {
                    throw new IllegalStateException(
                            "Run already recorded (refusing to overwrite"
                                    + " silently): " + record.runKey());
                }
                continue; // drop old row; new row appended below
            }
            kept.add(line);
        }
        kept.add(newRow);
        Files.createDirectories(csvPath.getParent());
        Files.write(csvPath, kept);
    }

    /** Extract the runKey from a stored CSV data row. */
    static String extractRunKey(String csvLine) {
        String[] cols = splitCsv(csvLine);
        if (cols.length < 7) {
            return null;
        }
        return cols[1] + "|" + cols[2] + "|" + cols[5] + "|" + cols[6];
    }

    /** Minimal CSV splitter honoring quoted fields. */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length()
                            && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // === Builder ===

    public static final class Builder {
        private String dataset;
        private String scenarioCell;
        private String profile;
        private String scale;
        private String method;
        private long seed;
        private int vmCount;
        private int hostCount;
        private double adaptiveThreshold;
        private double f1;
        private double f2;
        private double f3;
        private double f4;
        private int migrationCount;
        private double migrationCost;
        private int activeHosts;
        private double cpuUtilization;
        private double memoryUtilization;
        private double overallUtilization;
        private double totalCoResidencyDuration;
        private double highThreatCoResidencyDuration;
        private int coResidencyEpisodeCount;
        private Integer nssaoIterations;
        private Boolean stoppedByStall;
        private Integer rejectedDecodes;
        private Integer front1RawSize;
        private Integer uniquePlacements;
        private Integer duplicatesRemoved;
        private Integer relocations;
        private Integer searchAttempts;
        private String initialFingerprint;
        private String finalFingerprint;
        private long runtimeMs;
        private String notes;

        public Builder dataset(String v) { this.dataset = v; return this; }
        public Builder scenarioCell(String v) { this.scenarioCell = v; return this; }
        public Builder profile(String v) { this.profile = v; return this; }
        public Builder scale(String v) { this.scale = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder seed(long v) { this.seed = v; return this; }
        public Builder vmCount(int v) { this.vmCount = v; return this; }
        public Builder hostCount(int v) { this.hostCount = v; return this; }
        public Builder adaptiveThreshold(double v) { this.adaptiveThreshold = v; return this; }
        public Builder objectives(ObjectiveValues o) {
            this.f1 = o.getF1ThreatServerRisk();
            this.f2 = o.getF2CoResidencyRisk();
            this.f3 = o.getF3Cost();
            this.f4 = o.getF4UnusedCapacity();
            return this;
        }
        public Builder migrationCount(int v) { this.migrationCount = v; return this; }
        public Builder migrationCost(double v) { this.migrationCost = v; return this; }
        public Builder activeHosts(int v) { this.activeHosts = v; return this; }
        public Builder cpuUtilization(double v) { this.cpuUtilization = v; return this; }
        public Builder memoryUtilization(double v) { this.memoryUtilization = v; return this; }
        public Builder overallUtilization(double v) { this.overallUtilization = v; return this; }
        public Builder totalCoResidencyDuration(double v) { this.totalCoResidencyDuration = v; return this; }
        public Builder highThreatCoResidencyDuration(double v) { this.highThreatCoResidencyDuration = v; return this; }
        public Builder coResidencyEpisodeCount(int v) { this.coResidencyEpisodeCount = v; return this; }
        public Builder nssaoIterations(Integer v) { this.nssaoIterations = v; return this; }
        public Builder stoppedByStall(Boolean v) { this.stoppedByStall = v; return this; }
        public Builder rejectedDecodes(Integer v) { this.rejectedDecodes = v; return this; }
        public Builder front1RawSize(Integer v) { this.front1RawSize = v; return this; }
        public Builder uniquePlacements(Integer v) { this.uniquePlacements = v; return this; }
        public Builder duplicatesRemoved(Integer v) { this.duplicatesRemoved = v; return this; }
        public Builder relocations(Integer v) { this.relocations = v; return this; }
        public Builder searchAttempts(Integer v) { this.searchAttempts = v; return this; }
        public Builder initialFingerprint(String v) { this.initialFingerprint = v; return this; }
        public Builder finalFingerprint(String v) { this.finalFingerprint = v; return this; }
        public Builder runtimeMs(long v) { this.runtimeMs = v; return this; }
        public Builder notes(String v) { this.notes = v; return this; }

        public Stage2iRunRecord build() {
            return new Stage2iRunRecord(this);
        }
    }
}
