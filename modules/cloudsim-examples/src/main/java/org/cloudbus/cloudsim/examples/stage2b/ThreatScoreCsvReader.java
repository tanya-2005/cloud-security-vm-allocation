package org.cloudbus.cloudsim.examples.stage2b;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Python ML pipeline threat-score CSV and validates it against
 * the Stage 2A integration contract.
 *
 * <p>Expected CSV schema (UTF-8, header row):
 * <pre>
 * dataset,entity_type,entity_id,...,threat_score
 * </pre>
 *
 * <p>Validation rules (FAIL CLEARLY on violation, no silent repair):
 * <ul>
 *   <li>Required headers must exist</li>
 *   <li>dataset must be non-empty</li>
 *   <li>entity_type must be non-empty</li>
 *   <li>entity_id must be non-empty</li>
 *   <li>threat_score must parse as numeric</li>
 *   <li>threat_score must be finite</li>
 *   <li>threat_score must be in [0,1]</li>
 *   <li>No duplicate (dataset, entity_type, entity_id) composite keys</li>
 * </ul>
 *
 * <p>Missing feature columns are acceptable (they are not ML inputs for
 * the integration). Missing threat_score is an error.
 */
public class ThreatScoreCsvReader {

    static final String HEADER_DATASET = "dataset";
    static final String HEADER_ENTITY_TYPE = "entity_type";
    static final String HEADER_ENTITY_ID = "entity_id";
    static final String HEADER_THREAT_SCORE = "threat_score";

    private final Path csvPath;

    public ThreatScoreCsvReader(Path csvPath) {
        this.csvPath = csvPath;
    }

    /**
     * Read and validate the threat-score CSV.
     *
     * @return list of validated ThreatRecord objects, in file order
     * @throws IOException on I/O failure
     * @throws IllegalArgumentException on validation failure with a
     *                                  descriptive message
     */
    public List<ThreatRecord> read() throws IOException {
        List<String[]> parsed = parseCsv(csvPath);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty: " + csvPath);
        }

        String[] header = parsed.get(0);
        int[] colIndices = locateColumns(header);

        List<ThreatRecord> records = new ArrayList<>();
        Map<ThreatRecord.CompositeKey, ThreatRecord> seenKeys =
                new LinkedHashMap<>();

        for (int i = 1; i < parsed.size(); i++) {
            String[] row = parsed.get(i);
            try {
                ThreatRecord record = buildRecord(row, colIndices, i + 1);
                ThreatRecord.CompositeKey key = record.toCompositeKey();
                if (seenKeys.containsKey(key)) {
                    throw new IllegalArgumentException(
                            String.format("Duplicate composite key at line %d: %s",
                                    i + 1, key));
                }
                seenKeys.put(key, record);
                records.add(record);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("Error at line %d: %s", i + 1, e.getMessage()), e);
            }
        }

        return records;
    }

    /**
     * Locate required column indices by header name.
     */
    private int[] locateColumns(String[] header) {
        int datasetIdx = indexOf(header, HEADER_DATASET);
        int entityTypeIdx = indexOf(header, HEADER_ENTITY_TYPE);
        int entityIdIdx = indexOf(header, HEADER_ENTITY_ID);
        int threatScoreIdx = indexOf(header, HEADER_THREAT_SCORE);

        if (datasetIdx < 0) {
            throw new IllegalArgumentException(
                    "Missing required header: " + HEADER_DATASET);
        }
        if (entityTypeIdx < 0) {
            throw new IllegalArgumentException(
                    "Missing required header: " + HEADER_ENTITY_TYPE);
        }
        if (entityIdIdx < 0) {
            throw new IllegalArgumentException(
                    "Missing required header: " + HEADER_ENTITY_ID);
        }
        if (threatScoreIdx < 0) {
            throw new IllegalArgumentException(
                    "Missing required header: " + HEADER_THREAT_SCORE);
        }

        return new int[]{datasetIdx, entityTypeIdx, entityIdIdx, threatScoreIdx};
    }

    private int indexOf(String[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Build a ThreatRecord from a CSV row, validating all fields.
     */
    private ThreatRecord buildRecord(String[] row, int[] colIndices, int lineNum) {
        String dataset = safeTrim(row, colIndices[0], lineNum, HEADER_DATASET);
        String entityType = safeTrim(row, colIndices[1], lineNum, HEADER_ENTITY_TYPE);
        String entityId = safeTrim(row, colIndices[2], lineNum, HEADER_ENTITY_ID);
        String threatScoreStr = safeTrim(row, colIndices[3], lineNum, HEADER_THREAT_SCORE);

        if (dataset.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s is empty", lineNum, HEADER_DATASET));
        }
        if (entityType.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s is empty", lineNum, HEADER_ENTITY_TYPE));
        }
        if (entityId.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s is empty", lineNum, HEADER_ENTITY_ID));
        }
        if (threatScoreStr.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s is missing", lineNum, HEADER_THREAT_SCORE));
        }

        double threatScore;
        try {
            threatScore = Double.parseDouble(threatScoreStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s is not numeric: %s",
                            lineNum, HEADER_THREAT_SCORE, threatScoreStr));
        }

        if (Double.isNaN(threatScore) || Double.isInfinite(threatScore)) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s is not finite: %s",
                            lineNum, HEADER_THREAT_SCORE, threatScore));
        }

        if (threatScore < 0.0 || threatScore > 1.0) {
            throw new IllegalArgumentException(
                    String.format("Line %d: %s out of range [0,1]: %s",
                            lineNum, HEADER_THREAT_SCORE, threatScore));
        }

        return new ThreatRecord(dataset, entityType, entityId, threatScore);
    }

    private String safeTrim(String[] row, int index, int lineNum, String fieldName) {
        if (index >= row.length) {
            return "";
        }
        String value = row[index];
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    /**
     * Simple CSV parser handling quoted fields with embedded commas and
     * escaped quotes (""). No external dependency required.
     *
     * <p>This is intentionally minimal. It handles the CSV format produced
     * by the Python pipeline (UTF-8, standard quoting). It does NOT claim
     * to handle every RFC 4180 edge case; it handles the formats we
     * actually encounter.
     */
    static List<String[]> parseCsv(Path path) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Preserve empty lines only if they're not trailing
                if (line.isEmpty() && rows.isEmpty()) {
                    continue;
                }
                if (line.isEmpty()) {
                    // Skip trailing empty lines but keep internal structure simple
                    continue;
                }
                rows.add(parseLine(line));
            }
        }
        return rows;
    }

    static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // Check for escaped quote ""
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++; // skip next quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
