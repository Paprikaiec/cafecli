package com.cafecli.app;

import com.cafecli.harness.config.ConfigSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessReasoningTest {
    @TempDir Path root;

    @Test void actualCliTrimsWhitespaceAndSupportsReasoningWithoutAnAnswer() throws Exception {
        assertEquals("🧠 思考过程:\nreasoning only\n", invoke("""
                {"responses":[{"thinking":"  reasoning only  ","text":"  "}]}
                """, Map.of()));
        assertEquals("plain answer\n", invoke("""
                {"responses":[{"text":"  plain answer  "}]}
                """, Map.of()));
    }

    @Test void actualCliDoesNotPrintEarlierOrFailedReasoningWhenTheRunFailsOrExhaustsItsBudget() throws Exception {
        String script = """
                {"responses":[
                  {"thinking":"completed reasoning","toolCalls":[{"id":"r","name":"read_file","arguments":{"path":"missing"}}]},
                  {"thinking":"failed partial reasoning","text":"partial text","error":{"kind":"fatal","message":"fixture failure"}}
                ]}
                """;
        assertEquals("❌ 调用 LLM 失败: fixture failure\n", invoke(script, Map.of()));
        assertEquals("❌ 达到硬轮数上限（1），已强制收尾\n", invoke(script, Map.of("cafecli.react.hard.max.iterations", "1")));
    }

    private String invoke(String content, Map<String, String> overrides) throws Exception {
        Path script = root.resolve("simple-script.json"); Files.writeString(script, content);
        Files.writeString(root.resolve("issue.md"), "Check the fixture.");
        var properties = new java.util.HashMap<>(overrides);
        properties.put("cafecli.provider", "fake"); properties.put("cafecli.fake.script", script.toString());
        properties.put("cafecli.snapshot.dir", root.resolve("snapshots").toString());
        var stdout = new ByteArrayOutputStream(); var stderr = new ByteArrayOutputStream();
        assertEquals(0, Cli.run(new String[]{"run", "--file", "issue.md"},
                new ConfigSources(properties, Map.of(), root.resolve("home"), root), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)), stderr.toString(StandardCharsets.UTF_8));
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test void actualCliStdoutIncludesEveryCompletedMainReasoningIncludingTheBlockedStopAttempt() throws Exception {
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"thinking":"  plan  ","toolCalls":[{"id":"w","name":"write_file","arguments":{"path":"note","content":"ok"}}]},
                  {"thinking":"  premature reasoning  ","text":"premature"},
                  {"thinking":"verification reasoning","toolCalls":[{"id":"v","name":"execute_command","arguments":{"command":"true"}}]},
                  {"thinking":"  final reasoning  ","text":"  verified answer  "}
                ]}
                """);
        Files.writeString(root.resolve("issue.md"), "Write and verify.");
        var sources = new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.dir", root.resolve("snapshots").toString(), "cafecli.hook.stop.enabled", "true",
                "cafecli.hook.finish_gate.enabled", "true", "cafecli.hook.finish_gate.verify_regex", "true"), Map.of(), root.resolve("home"), root);
        var stdout = new ByteArrayOutputStream(); var stderr = new ByteArrayOutputStream();
        int code = Cli.run(new String[]{"run", "--file", "issue.md", "--trace-file", "trace.jsonl"}, sources,
                new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        assertEquals("🧠 思考过程:\nplan\n\npremature reasoning\n\nverification reasoning\n\nfinal reasoning\n\nverified answer\n", stdout.toString(StandardCharsets.UTF_8));
        var json = new ObjectMapper();
        var records = Files.readAllLines(root.resolve("trace.jsonl")).stream().map(line -> {
            try { return json.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        var assistants = records.stream().filter(record -> record.path("type").asText().equals("assistant")).toList();
        assertEquals(4, assistants.size());
        assertEquals("  verified answer  ", assistants.getLast().path("text").asText());
        assertEquals("  final reasoning  ", assistants.getLast().path("reasoning").asText());
        assertFalse(records.toString().contains("🧠 思考过程:"), "presentation never changes the compatible messages");
    }
}
