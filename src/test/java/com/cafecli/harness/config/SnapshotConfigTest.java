package com.cafecli.harness.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotConfigTest {
    @TempDir Path root;
    private static final List<String> DEFAULT_EXCLUDES = List.of(".git", ".cafecli/snapshots", ".paicli/snapshots",
            "target", "node_modules", "dist", ".idea", "*.class", "*.jar");

    @Test void capturedBrandedPropertiesWinAndRelativeDirectoriesBindToTheConfiguredProject() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "off",
                "paicli.snapshot.enabled", "true", "cafecli.snapshot.dir", " side ",
                "cafecli.snapshot.max", "-5", "cafecli.snapshot.excludes", " custom, .git, custom, other "),
                Map.of("CAFECLI_SNAPSHOT_ENABLED", "on", "CAFECLI_SNAPSHOT_DIR", "wrong"), root.resolve("home"), root.resolve("project")));
        assertFalse(config.snapshots().enabled());
        assertEquals(root.resolve("project/side"), config.snapshots().snapshotsRoot());
        assertEquals(1, config.snapshots().maxSnapshots());
        assertEquals(List.of(".git", ".cafecli/snapshots", ".paicli/snapshots", "target", "node_modules", "dist",
                ".idea", "*.class", "*.jar", "custom", "other"), config.snapshots().excludes());
    }
    @Test void presentBlankPropertiesSuppressEnvironmentBeforeLeafFallbacksApply() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", " ",
                "cafecli.snapshot.dir", " ", "cafecli.snapshot.max", "", "cafecli.snapshot.excludes", ""),
                Map.of("CAFECLI_SNAPSHOT_ENABLED", "false", "CAFECLI_SNAPSHOT_DIR", "wrong",
                        "CAFECLI_SNAPSHOT_MAX", "1", "CAFECLI_SNAPSHOT_EXCLUDES", "wrong"),
                root.resolve("home"), root.resolve("project")));
        assertTrue(config.snapshots().enabled());
        assertEquals(root.resolve("home/.cafecli/snapshots"), config.snapshots().snapshotsRoot());
        assertEquals(50, config.snapshots().maxSnapshots());
        assertEquals(DEFAULT_EXCLUDES, config.snapshots().excludes());
    }

    @Test void legacyPropertiesAndBrandedEnvironmentRetainTheirOwnPrecedence() {
        var legacy = ConfigLoader.load(new ConfigSources(Map.of("paicli.snapshot.enabled", "no", "paicli.snapshot.max", " 7 "),
                Map.of("CAFECLI_SNAPSHOT_ENABLED", "yes", "CAFECLI_SNAPSHOT_MAX", "2"), root.resolve("home"), root.resolve("project")));
        assertFalse(legacy.snapshots().enabled());
        assertEquals(7, legacy.snapshots().maxSnapshots());
        var environment = ConfigLoader.load(new ConfigSources(Map.of(), Map.of("CAFECLI_SNAPSHOT_ENABLED", "off",
                "PAICLI_SNAPSHOT_ENABLED", "on", "PAICLI_SNAPSHOT_DIR", "legacy", "CAFECLI_SNAPSHOT_MAX", "bad"),
                root.resolve("home"), root.resolve("project")));
        assertFalse(environment.snapshots().enabled());
        assertEquals(root.resolve("project/legacy"), environment.snapshots().snapshotsRoot());
        assertEquals(50, environment.snapshots().maxSnapshots());
    }

}
