package com.cafecli.parity;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

/** Filesystem observations at seam 4, including dotfiles; only a validated fixture-owned endpoint may be omitted. */
final class ParityFiles {
    private ParityFiles() { }

    static Map<String, String> read(Path project) throws Exception { return read(project, null); }

    static Map<String, String> read(Path project, byte[] fixtureMcpConfiguration) throws Exception {
        if (fixtureMcpConfiguration != null) {
            Path config = project.resolve(".cafecli/mcp.json");
            assertTrue(Files.isRegularFile(config), "the fixture-owned MCP configuration must survive the product run");
            assertArrayEquals(fixtureMcpConfiguration, Files.readAllBytes(config), "the product must preserve exact fixture-owned MCP config bytes");
        }
        var bytes = new TreeMap<String, String>();
        try (var files = Files.walk(project)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = project.relativize(file).toString();
                if (fixtureMcpConfiguration != null && relative.equals(".cafecli/mcp.json")) continue;
                bytes.put(relative, Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        return bytes;
    }

    static Map<String, String> expected(JsonNode files) {
        var bytes = new TreeMap<String, String>();
        files.properties().forEach(entry -> bytes.put(entry.getKey(), Base64.getEncoder().encodeToString(entry.getValue().asText().getBytes(StandardCharsets.UTF_8))));
        return bytes;
    }

    static void seed(Path project, JsonNode files) throws Exception {
        for (var entry : files.properties()) {
            Path file = project.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue().asText());
        }
    }

    static void erase(Path project) throws Exception {
        try (var paths = Files.walk(project)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(project)).toList()) Files.delete(path);
        }
    }

    static void compare(Map<String, String> expected, Map<String, String> actual) { assertEquals(expected, actual, "complete project file bytes"); }
}
