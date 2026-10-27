package com.cafecli.snapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SnapshotConfig(
        boolean enabled,
        Path snapshotsRoot,
        int maxSnapshots,
        List<String> excludes
) {
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git",
            ".cafecli/snapshots",
            ".paicli/snapshots",
            "target",
            "node_modules",
            "dist",
            ".idea",
            "*.class",
            "*.jar"
    );

    public static SnapshotConfig fromEnvironment() {
        return fromValues(readNullable("paicli.snapshot.enabled", "PAICLI_SNAPSHOT_ENABLED"),
                readNullable("paicli.snapshot.dir", "PAICLI_SNAPSHOT_DIR"),
                readNullable("paicli.snapshot.max", "PAICLI_SNAPSHOT_MAX"),
                readNullable("paicli.snapshot.excludes", "PAICLI_SNAPSHOT_EXCLUDES"),
                com.cafecli.config.ConfigHome.userConfigDir().resolve("snapshots"));
    }

    /** Same parsing/defaults as the legacy loader, with all external values supplied by the caller. */
    public static SnapshotConfig fromValues(String enabled, String directory, String max, String excludes,
                                            Path defaultRoot) {
        return new SnapshotConfig(parseBoolean(enabled, true), Path.of(stringValue(directory, defaultRoot.toString())),
                Math.max(1, parseInt(max, 50)), mergeExcludes(stringValue(excludes, "")));
    }

    public SnapshotConfig withEnabled(boolean enabled) {
        return new SnapshotConfig(enabled, snapshotsRoot, maxSnapshots, excludes);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String stringValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String readNullable(String property, String env) {
        String value = System.getProperty(property);
        if (value != null) {
            return value;
        }
        return com.cafecli.config.ConfigHome.envLegacy(env);
    }

    private static List<String> mergeExcludes(String configured) {
        Set<String> merged = new LinkedHashSet<>(DEFAULT_EXCLUDES);
        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        return new ArrayList<>(merged);
    }
}
