package com.cafecli.app;

import com.cafecli.harness.config.ConfigSources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceCompositionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void cliWritesCompatibleMessagesAndMainUsageBeforeReturningItsAnswer() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("issue.md"), "read fixture");
        Files.writeString(project.resolve("note.txt"), "fixture bytes");
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"thinking":"read reasoning","toolCalls":[{"id":"read","name":"read_file","arguments":{"path":"note.txt"}}],"usage":{"input":4,"output":2,"cached":1}},
                  {"text":"complete","usage":{"input":8,"output":3,"cached":2}}
                ]}
                """);
        var sources = new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.dir", root.resolve("snapshots").toString()), Map.of(), root.resolve("home"), project);
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"run", "--file", "issue.md", "--trace-file", "trace.jsonl"}, sources,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        assertEquals("🧠 思考过程:\nread reasoning\n\ncomplete\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        List<JsonNode> records = Files.readAllLines(project.resolve("trace.jsonl")).stream().map(line -> {
            try { return JSON.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        assertEquals(List.of("system", "user", "assistant", "tool_result", "assistant", "result"),
                records.stream().map(node -> node.path("type").asText()).toList());
        assertEquals("read fixture", records.get(1).path("text").asText());
        assertEquals(JSON.readTree("""
                {"type":"assistant","reasoning":"read reasoning","tool_calls":[{"id":"read","name":"read_file","arguments":"{\\"path\\":\\"note.txt\\"}"}]}
                """), records.get(2));
        assertEquals("read", records.get(3).path("tool_call_id").asText());
        assertTrue(records.get(3).path("content").asText().contains("fixture bytes"));
        assertEquals(JSON.readTree("{\"type\":\"assistant\",\"text\":\"complete\"}"), records.get(4));
        JsonNode result = records.getLast();
        assertEquals("fake", result.path("provider").asText());
        assertEquals("fake-model", result.path("model").asText());
        assertEquals(JSON.readTree("{\"in\":12,\"out\":5,\"cache\":3}"), result.path("tokens"));
        assertEquals(2, result.path("turns").asInt());
        assertTrue(result.path("wall_ms").asLong(-1) >= 0);
    }
    @Test void anUnwritableTraceFailsAfterTheModelWorkAndBeforePrintingItsAnswer() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("issue.md"), "write fixture");
        Files.createDirectory(project.resolve("trace-directory"));
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"toolCalls":[{"id":"write","name":"write_file","arguments":{"path":"marker.txt","content":"model ran"}}]},
                  {"text":"must not print"}
                ]}
                """);
        var result = invoke(project, script, "trace-directory", Map.of());
        assertEquals(1, result.exit());
        assertEquals("", result.out());
        assertTrue(result.err().startsWith("❌ 运行失败: "), result.err());
        assertFalse(result.err().contains("Exception"));
        assertEquals("model ran", Files.readString(project.resolve("marker.txt")), "trace I/O must not prevent model work");
    }

    @Test void anUnreadableIssueNeverTruncatesAnExistingTrace() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path trace = project.resolve("trace.jsonl"); Files.writeString(trace, "existing trace bytes");
        Path script = root.resolve("script.json"); Files.writeString(script, "{\"responses\":[{\"text\":\"unused\"}]}");
        var result = invoke(project, script, "trace.jsonl", Map.of());
        assertEquals(1, result.exit());
        assertEquals("", result.out());
        assertTrue(result.err().startsWith("❌ 运行失败: "));
        assertEquals("existing trace bytes", Files.readString(trace));
    }

    @Test void aBlockedUserPromptNeverTruncatesAnExistingTrace() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("issue.md"), "blocked fixture");
        Path trace = project.resolve("trace.jsonl"); Files.writeString(trace, "existing trace bytes");
        Path hooks = Files.createDirectories(root.resolve("home/.cafecli")).resolve("hooks.json");
        Files.writeString(hooks, JSON.writeValueAsString(Map.of("UserPromptSubmit", List.of(Map.of("hooks",
                List.of(Map.of("type", "command", "command", "printf '%s' '{\"decision\":\"block\",\"reason\":\"fixture block\"}'")))))));
        Path script = root.resolve("script.json"); Files.writeString(script, "{\"responses\":[{\"text\":\"unused\"}]}");
        var result = invoke(project, script, "trace.jsonl", Map.of("cafecli.hook.user_prompt_submit.enabled", "true"));
        assertEquals(1, result.exit());
        assertEquals("", result.out());
        assertTrue(result.err().contains("UserPromptSubmit hook 拦截"), result.err());
        assertEquals("existing trace bytes", Files.readString(trace));
    }

    private Result invoke(Path project, Path script, String trace, Map<String, String> overrides) {
        var properties = new java.util.HashMap<String, String>();
        properties.put("cafecli.provider", "fake"); properties.put("cafecli.fake.script", script.toString());
        properties.put("cafecli.snapshot.dir", root.resolve("snapshots").toString()); properties.putAll(overrides);
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"run", "--file", "issue.md", "--trace-file", trace},
                new ConfigSources(properties, Map.of(), root.resolve("home"), project),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) { }

}
