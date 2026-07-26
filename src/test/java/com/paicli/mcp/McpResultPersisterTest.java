package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResultPersisterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesStructuredJsonUnderSessionScopeAndReturnsRelativePath(@TempDir Path projectRoot) throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {"network":[{"requestId":"req-1","status":500}]}
                """);
        McpResultPersister persister = new McpResultPersister(projectRoot, "session-abc", "main");

        McpResultPersister.StoredResult stored = persister.persistStructuredJson("call_1", payload);

        Path expectedRelativePath = Path.of(".cafecli", "tool-results", "session-abc", "main", "call_1.json");
        assertEquals(expectedRelativePath, stored.relativePath());
        assertEquals(".cafecli/tool-results/session-abc/main/call_1.json", stored.relativePathForToolRegistry());
        assertEquals(projectRoot.resolve(expectedRelativePath), stored.absolutePath());
        assertTrue(Files.isRegularFile(stored.absolutePath()));
        String written = Files.readString(stored.absolutePath());
        assertTrue(written.contains(System.lineSeparator() + "  \"network\""), written);
        assertEquals(payload, MAPPER.readTree(written));
    }

    @Test
    void keepsExistingFileWhenSameToolCallIdIsPersistedAgain(@TempDir Path projectRoot) throws Exception {
        McpResultPersister persister = new McpResultPersister(projectRoot, "session-abc", "task_1");
        JsonNode firstPayload = MAPPER.readTree("""
                {"value":"first"}
                """);
        JsonNode replayPayload = MAPPER.readTree("""
                {"value":"replay"}
                """);

        McpResultPersister.StoredResult first = persister.persistStructuredJson("call_1", firstPayload);
        String originalContent = Files.readString(first.absolutePath());
        McpResultPersister.StoredResult replay = persister.persistStructuredJson("call_1", replayPayload);

        assertEquals(first.relativePath(), replay.relativePath());
        assertEquals(first.absolutePath(), replay.absolutePath());
        assertEquals(originalContent, Files.readString(first.absolutePath()));
        assertEquals(firstPayload, MAPPER.readTree(first.absolutePath().toFile()));
    }

    @Test
    void writesTextUnderSessionScopeAndKeepsExistingReplay(@TempDir Path projectRoot) throws Exception {
        McpResultPersister persister = new McpResultPersister(projectRoot, "session-abc", "main");

        McpResultPersister.StoredResult first = persister.persistText("call_1", "first text");
        McpResultPersister.StoredResult replay = persister.persistText("call_1", "replay text");

        Path expectedRelativePath = Path.of(".cafecli", "tool-results", "session-abc", "main", "call_1.txt");
        assertEquals(expectedRelativePath, first.relativePath());
        assertEquals(first, replay);
        assertTrue(Files.isRegularFile(first.absolutePath()));
        assertEquals("first text", Files.readString(first.absolutePath()));
    }

    @Test
    void rejectsPathTraversalComponents(@TempDir Path projectRoot) throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {"value":"safe"}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> new McpResultPersister(projectRoot, "../session", "main"));
        assertThrows(IllegalArgumentException.class,
                () -> new McpResultPersister(projectRoot, "session-abc", "task/1"));
        McpResultPersister persister = new McpResultPersister(projectRoot, "session-abc", "main");
        assertThrows(IllegalArgumentException.class,
                () -> persister.persistStructuredJson("../call_1", payload));
    }

    @Test
    void readHintIncludesJqWhenProbeSucceedsAndCachesResult(@TempDir Path projectRoot) {
        AtomicInteger probes = new AtomicInteger();
        McpResultPersister persister = new McpResultPersister(projectRoot, "session-abc", "main",
                () -> {
                    probes.incrementAndGet();
                    return true;
                });

        String first = persister.readHint(".cafecli/tool-results/session-abc/main/call_1.json");
        String second = persister.readHint(".cafecli/tool-results/session-abc/main/call_1.json");

        assertTrue(first.contains("jq"), first);
        assertTrue(first.contains("grep_code"), first);
        assertTrue(first.contains("read_file"), first);
        assertEquals(first, second);
        assertEquals(1, probes.get());
    }

    @Test
    void readHintOmitsJqWhenProbeFails(@TempDir Path projectRoot) {
        McpResultPersister persister = new McpResultPersister(projectRoot, "session-abc", "main", () -> false);

        String hint = persister.readHint(".cafecli/tool-results/session-abc/main/call_1.json");

        assertTrue(!hint.contains("jq"), hint);
        assertTrue(hint.contains("grep_code"), hint);
        assertTrue(hint.contains("read_file"), hint);
    }
}
