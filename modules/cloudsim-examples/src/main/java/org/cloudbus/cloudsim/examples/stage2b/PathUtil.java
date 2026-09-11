package org.cloudbus.cloudsim.examples.stage2b;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility for resolving file paths in a cross-platform manner.
 *
 * <p>The project directory structure is:
 * <pre>
 * C:\cloudsim file\
 *   cloudsim\       (CloudSim source - where we run from)
 *   project\        (Python/ML + outputs - sibling of cloudsim)
 *     outputs\
 *       azure_to_azure_scores.csv
 * </pre>
 *
 * <p>When running from cloudsim/, the project is at ../project/.
 */
public class PathUtil {

    /**
     * Resolve the given path to an absolute Path.
     * Tries multiple candidate base directories.
     *
     * <p>The path argument can be:
     * - A relative path like "project/outputs/azure_to_azure_scores.csv"
     * - An absolute path
     *
     * <p>When running from cloudsim/, the project is at ../project/.
     * So "project/..." becomes "../project/project/..." which is wrong.
     * We need to handle this by checking both forms.
     */
    public static Path toPath(String relativePath) {
        // If it's already an absolute path, return it directly
        Path absPath = Paths.get(relativePath);
        if (absPath.isAbsolute() && absPath.toFile().exists()) {
            return absPath.toAbsolutePath().normalize();
        }
        
        // Try the path as-is from current directory
        if (absPath.toFile().exists()) {
            return absPath.toAbsolutePath().normalize();
        }

        // Path might be "project/outputs/..." but we're in cloudsim/
        // The project is at ../project/, so we need "../project/outputs/..."
        // Strip leading "project/" if present and prepend "../project/"
        String adjustedPath = relativePath;
        if (adjustedPath.startsWith("project/")) {
            adjustedPath = "../project/" + adjustedPath.substring("project/".length());
        }
        
        Path path = Paths.get(adjustedPath);
        if (path.toFile().exists()) {
            return path.toAbsolutePath().normalize();
        }

        // Also try without the project prefix
        path = Paths.get("../project/" + relativePath);
        if (path.toFile().exists()) {
            return path.toAbsolutePath().normalize();
        }

        // Return the original path (will fail later with clear error)
        return Paths.get(relativePath).toAbsolutePath().normalize();
    }

    /**
     * Verify that a path exists, throwing a clear error if not.
     */
    public static Path requireExists(Path path) {
        if (!path.toFile().exists()) {
            throw new IllegalArgumentException(
                    "File not found: " + path
                            + "\n"
                            + "Tried resolving from: " + Paths.get(".").toAbsolutePath() + "\n"
                            + "Project directory should be at: ../project/ relative to cloudsim/\n"
                            + "Expected file: project/outputs/azure_to_azure_scores.csv");
        }
        return path;
    }

    private PathUtil() {
        // Utility class
    }
}
