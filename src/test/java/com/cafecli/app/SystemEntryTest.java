package com.cafecli.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The process entry, run on a {@link Properties} of the test's own instead of the JVM's — so nothing global is read or
 * written, yet it is the same code the real entry runs on {@code System.getProperties()}.
 */
class SystemEntryTest {

    @TempDir
    Path root;

    @Test
    void cafecliPropertiesAreBridgedToTheirPaicliNamesForTheLeavesAndTheRunSeesThem() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("issue.md"), "What is 2 + 2?");
        Files.writeString(project.resolve("script.json"), "{\"responses\": [{\"text\": \"4\"}]}");
        Properties properties = new Properties();
        properties.setProperty("user.home", home.toString());
        properties.setProperty("user.dir", project.toString());
        properties.setProperty("cafecli.provider", "fake");
        properties.setProperty("cafecli.fake.script", "script.json");
        // A leaf v2 reuses reads only the paicli.* name (PathGuard.safeEditEnabled reads paicli.safe_edit.enabled).
        properties.setProperty("cafecli.safe_edit.enabled", "true");
        properties.setProperty("paicli.safe_edit.enabled", "false");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = SystemEntry.run(new String[]{"run", "--file", "issue.md"}, properties, Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));

        assertEquals("true", properties.getProperty("paicli.safe_edit.enabled"));
        assertEquals("fake", properties.getProperty("paicli.provider"));
        assertEquals("4" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
        assertEquals(0, exit);
    }
}
