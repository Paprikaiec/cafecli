package com.cafecli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigHomeTest {

    @Test
    void freshBasePrefersNewCafecliDir(@TempDir Path base) {
        Path dir = ConfigHome.configDirUnder(base);
        assertEquals(base.resolve(".cafecli"), dir);
    }

    @Test
    void fallsBackToLegacyPaicliWhenOnlyLegacyExists(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".paicli"));
        Path dir = ConfigHome.configDirUnder(base);
        assertEquals(base.resolve(".paicli"), dir);
    }

    @Test
    void prefersNewCafecliWhenBothExist(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".paicli"));
        Files.createDirectories(base.resolve(".cafecli"));
        Path dir = ConfigHome.configDirUnder(base);
        assertEquals(base.resolve(".cafecli"), dir);
    }

    @Test
    void envOverAGivenEnvironmentPrefersTheNewNameAndFallsBackToTheLegacyOne() {
        assertEquals("new", ConfigHome.env(Map.of("CAFECLI_X", "new", "PAICLI_X", "old"), "CAFECLI_X", "PAICLI_X"));
        assertEquals("old", ConfigHome.env(Map.of("PAICLI_X", "old"), "CAFECLI_X", "PAICLI_X"));
        assertEquals("", ConfigHome.env(Map.of("CAFECLI_X", "", "PAICLI_X", "old"), "CAFECLI_X", "PAICLI_X"));
        assertNull(ConfigHome.env(Map.of(), "CAFECLI_X", "PAICLI_X"));
    }

    @Test
    void propertyOverGivenPropertiesReadsWhatThePropertyBridgeLeavesInThePaicliKey() {
        assertEquals("new", ConfigHome.property(Map.of("cafecli.x", "new", "paicli.x", "old"), "x"));
        assertEquals("old", ConfigHome.property(Map.of("paicli.x", "old"), "x"));
        // The bridge copies a present cafecli.* value over its twin even when blank.
        assertEquals("", ConfigHome.property(Map.of("cafecli.x", "", "paicli.x", "old"), "x"));
        assertNull(ConfigHome.property(Map.of(), "x"));
    }

    @Test
    void installPropertyAliasesOverGivenPropertiesBridgesOnlyThose() {
        Properties props = new Properties();
        props.setProperty("cafecli.a", "new-a");
        props.setProperty("paicli.a", "old-a");
        props.setProperty("cafecli.b", "");
        props.setProperty("paicli.c", "old-c");

        ConfigHome.installPropertyAliases(props);

        assertEquals("new-a", props.getProperty("paicli.a"));
        assertEquals("", props.getProperty("paicli.b"));
        assertEquals("old-c", props.getProperty("paicli.c"));
        assertNull(props.getProperty("cafecli.c"));
    }

    @Test
    void installPropertyAliasesBridgesCafecliToLegacyPaicliKey() {
        String cafecliKey = "cafecli.__configHomeTest__.a";
        String legacyKey = "paicli.__configHomeTest__.a";
        try {
            System.clearProperty(legacyKey);
            System.setProperty(cafecliKey, "value-a");
            ConfigHome.installPropertyAliases();
            assertEquals("value-a", System.getProperty(legacyKey));
        } finally {
            System.clearProperty(cafecliKey);
            System.clearProperty(legacyKey);
        }
    }

    @Test
    void installPropertyAliasesLetsNewKeyWinOverLegacy() {
        String cafecliKey = "cafecli.__configHomeTest__.b";
        String legacyKey = "paicli.__configHomeTest__.b";
        try {
            System.setProperty(legacyKey, "old-b");
            System.setProperty(cafecliKey, "new-b");
            ConfigHome.installPropertyAliases();
            assertEquals("new-b", System.getProperty(legacyKey));
        } finally {
            System.clearProperty(cafecliKey);
            System.clearProperty(legacyKey);
        }
    }
}
