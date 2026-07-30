package com.cafecli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
