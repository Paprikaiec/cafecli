package com.cafecli.parity;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary parity at the actual Main entry, with both feature flags and isolated subprocess configuration. */
class DefaultCliParityTest {
    @TempDir Path root;
    @ParameterizedTest(name = "{0}") @ValueSource(strings = {"plain", "skill", "spawn", "mcp", "browser", "lsp", "read-edit"})
    void actualDefaultEntriesHaveOnlyExactApprovedDifferences(String name) throws Exception {
        JsonNode script = ParityScript.JSON.readTree(Path.of("src/test/resources/parity/default-cli", name + ".json").toFile());
        Path project = Files.createDirectories(root.resolve("project"));
        try (var server = new DefaultCliServer(script)) {
            var v1 = run(script, project, server, "v1"); ParityFiles.erase(project);
            var v2 = run(script, project, server, "v2"); server.assertHealthy();
            ParityEvidence.save(script.path("name").asText(), v1.trace(), v2.trace(), List.of(8, 13));
            ParityEvidence.result(script.path("name").asText(), "v1", v1.answer(), v1.files());
            ParityEvidence.result(script.path("name").asText(), "v2", v2.answer(), v2.files());
            assertEquals(script.path("answer").asText(), v1.answer()); assertEquals(v1.answer(), v2.answer());
            ParityFiles.compare(v1.files(), v2.files());
            var records1 = ParityComparison.trace(v1.trace()); var records2 = ParityComparison.trace(v2.trace());
            assertThrows(AssertionError.class, () -> DefaultCliComparison.traces(script, records1, records2, false), "actual default difference requires13");
            DefaultCliComparison.traces(script, records1, records2, true);
            DefaultCliComparison.wire(script, server.requests("v1", "main"), server.requests("v2", "main"));
            JsonNode summary1 = ParityEvidence.normalize(v1.trace()), summary2 = ParityEvidence.normalize(v2.trace());
            DefaultCliComparison.summaries(summary1, summary2);
            DefaultCliComparison.rejectsMutations(script, records1, records2, server.requests("v1", "main"), server.requests("v2", "main"), summary1, summary2);
            int mainResponses = script.path("responses").path("main").size();
            assertEquals(ParityScript.JSON.readTree("{\"in\":" + (mainResponses * 3) + ",\"out\":" + (mainResponses * 2) + ",\"cache\":" + mainResponses + "}"), records2.getLast().path("tokens"));
            assertEquals(mainResponses, records2.getLast().path("turns").asInt(), "children never become compatible MAIN assistants");
            assertEquals(name.equals("lsp") ? 2 : 1, records2.stream().filter(record -> record.path("type").asText().equals("user")).count());
            String finalText = script.path("responses").path("main").get(mainResponses - 1).path("text").asText();
            assertEquals(finalText, records1.get(records1.size()-2).path("text").asText());
            assertEquals(finalText, records2.get(records2.size()-2).path("text").asText());
            childRequests(script, server);
            if (name.equals("browser")) {
                var typed = com.cafecli.app.DefaultCliTypedObservation.browserDisconnect(root.resolve("typed-observation"));
                assertEquals(com.cafecli.tool.ToolOutput.Status.ERROR, typed.v1()); assertEquals(com.cafecli.core.ToolResult.Status.OK, typed.v2());
                assertEquals(script.path("result13").path("v1").asText(), typed.v1Text()); assertEquals(script.path("result13").path("v2").asText(), typed.v2Text());
                Files.writeString(ParityEvidence.DIRECTORY.resolve(script.path("name").asText()).resolve("typed-outcomes.json"), ParityScript.JSON.writeValueAsString(typed));
            }
            assertEquals(script.path("responses").path("main").size(), server.requests("v1", "main").size());
            assertEquals(script.path("responses").path("main").size(), server.requests("v2", "main").size());
            assertEquals(0, server.requests("v1", "child").size());
            assertEquals(script.path("responses").path("child").size(), server.requests("v2", "child").size());
            assertEquals(0, server.rpc("v1").size());
            if (script.path("mcp").asBoolean()) assertTrue(server.rpc("v2").stream().anyMatch(rpc -> rpc.path("method").asText().equals("resources/read")));
            else assertEquals(0, server.rpc("v2").size());
            if (name.equals("lsp")) assertTrue(records1.stream().anyMatch(record -> record.path("type").asText().equals("user")
                    && record.path("text").asText().startsWith("[LSP 诊断注入]")), "LSP is present in BOTH, and remains unwaived");
        }
    }

    private Arm run(JsonNode script, Path project, DefaultCliServer server, String arm) throws Exception {
        server.activate(arm);
        ParityFiles.seed(project, script.path("files")); Files.writeString(project.resolve("issue.md"), script.path("input").asText());
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) (script.has("expectedFiles") ? script.path("expectedFiles") : script.path("files")).deepCopy();
        expected.put("issue.md", script.path("input").asText());
        if (script.path("mcp").asBoolean()) {
            String config = ParityScript.JSON.writeValueAsString(Map.of("mcpServers", Map.of("demo", Map.of("url", server.mcpUrl()))));
            Path mcp = project.resolve(".cafecli/mcp.json"); Files.createDirectories(mcp.getParent()); Files.writeString(mcp, config); expected.put(".cafecli/mcp.json", config);
        }
        Path home = Files.createDirectories(root.resolve(arm + "-home/.cafecli"));
        String providerConfig = ParityScript.JSON.writeValueAsString(Map.of("defaultProvider", "step", "providers",
                Map.of("step", Map.of("apiKey", "local-fixture-key", "model", "step-3.5-flash", "baseUrl", server.baseUrl(arm)))));
        Files.writeString(home.resolve("config.json"), providerConfig);
        Path trace = root.resolve(arm + ".jsonl"), stdout = root.resolve(arm + "-stdout.txt"), stderr = root.resolve(arm + "-stderr.txt");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Dfile.encoding=UTF-8", "-Duser.home=" + home.getParent(),
                "-Dcafecli.v2=" + arm.equals("v2"), "-Dcafecli.snapshot.enabled=false", "-Dcafecli.audit.dir=" + root.resolve(arm + "-audit"),
                "-Dcafecli.memory.dir=" + root.resolve(arm + "-memory"), "-Dpaicli.render.synchronized-output=false", "-Dpaicli.render.osc8=false",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "com.cafecli.cli.Main",
                "run", "--file", "issue.md", "--trace-file", trace.toString());
        var builder = new ProcessBuilder(command).directory(project.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("CAFECLI_") || key.startsWith("PAICLI_") || key.endsWith("_API_KEY") || Set.of("NO_COLOR", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "GLM_API_KEY", "DEEPSEEK_API_KEY", "STEP_API_KEY", "KIMI_API_KEY", "FREELLMAPI_API_KEY", "MOONSHOT_API_KEY", "SEARCH_PROVIDER", "SERPAPI_KEY", "SEARXNG_URL").contains(key));
        builder.environment().put("TERM", "xterm");
        assertTrue(builder.environment().keySet().stream().noneMatch(key -> key.startsWith("CAFECLI_") || key.startsWith("PAICLI_") || key.endsWith("_API_KEY")),
                "child provider configuration must be isolated from real credentials");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "actual Main subprocess exits");
            assertEquals(0, process.exitValue(), Files.readString(stderr)); assertEquals("", Files.readString(stderr));
        } finally { if (process.isAlive()) process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        String output = Files.readString(stdout); int start = output.lastIndexOf("CAFECLI PARITY FINAL BEGIN\n"), end = output.lastIndexOf("\nCAFECLI PARITY FINAL END");
        assertTrue(start >= 0 && end > start, output);
        String answer = output.substring(start + "CAFECLI PARITY FINAL BEGIN\n".length(), end);
        Path evidence = Files.createDirectories(ParityEvidence.DIRECTORY.resolve(script.path("name").asText()));
        Files.copy(stdout, evidence.resolve(arm + "-stdout.txt")); Files.copy(stderr, evidence.resolve(arm + "-stderr.txt"));
        Files.writeString(evidence.resolve(arm + "-wire.json"), ParityScript.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(server.requests(arm, "main")));
        Files.writeString(evidence.resolve(arm + "-child-wire.json"), ParityScript.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(server.requests(arm, "child")));
        Files.writeString(evidence.resolve(arm + "-mcp-wire.json"), ParityScript.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(server.rpc(arm)));
        Files.writeString(evidence.resolve(arm + "-expected-files.json"), expected.toPrettyString());
        Files.writeString(evidence.resolve("fixture.json"), script.toPrettyString());
        Files.writeString(evidence.resolve(arm + "-command.json"), ParityScript.JSON.writeValueAsString(command));
        assertEquals(providerConfig, Files.readString(home.resolve("config.json")), "startup never rewrites the configured provider generation");
        Files.writeString(evidence.resolve(arm + "-provider-config.json"), providerConfig);
        Files.copy(Path.of("src/test/resources/parity/default-cli/skill-section.txt"), evidence.resolve("oracle-skill-section.txt"), StandardCopyOption.REPLACE_EXISTING);
        if (script.path("mcp").asBoolean()) for (String oracle : List.of("mcp-section.txt", "mcp-tools.json"))
            Files.copy(Path.of("src/test/resources/parity/default-cli", oracle), evidence.resolve("oracle-" + oracle), StandardCopyOption.REPLACE_EXISTING);
        ParityFiles.compare(ParityFiles.expected(expected), ParityFiles.read(project));
        assertEquals(script.path("answer").asText(), answer);
        if (arm.equals("v2")) assertEquals(script.path("responses").path("main").get(script.path("responses").path("main").size()-1).path("text").asText() + "\n", output);
        cache(home.resolve("skills-cache"), arm, evidence);
        return new Arm(answer, ParityFiles.read(project), trace);
    }
    private void childRequests(JsonNode fixture, DefaultCliServer server) {
        var children = server.requests("v2", "child");
        for (int i = 0; i < children.size(); i++) {
            JsonNode request = children.get(i); assertEquals("step-3.5-flash", request.path("model").asText());
            var messages = new ArrayList<JsonNode>(); request.path("messages").forEach(messages::add);
            assertEquals(server.requests("v2", "main").getFirst().path("messages").get(0), messages.removeFirst());
            assertEquals(fixture.path("expectedChildMessages").get(i), ParityScript.JSON.valueToTree(messages));
            var expectedTools = new ArrayList<JsonNode>();
            server.requests("v2", "main").getFirst().path("tools").forEach(tool -> {
                if (!Set.of("spawn_agent", "send_message", "task_output").contains(tool.path("function").path("name").asText())) expectedTools.add(tool);
            });
            assertEquals(17, expectedTools.size()); assertEquals(ParityScript.JSON.valueToTree(expectedTools), request.path("tools"), "child has exactly the declared nonrecursive capabilities");
        }
    }

    private void cache(Path cache, String arm, Path evidence) throws Exception {
        var expected = new TreeMap<String, String>();
        if (arm.equals("v2")) {
            Path source = Path.of("src/main/resources/skills");
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) expected.put(source.relativize(file).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
            expected.put("web-access/.version", "MS4wLjA=");
        }
        Map<String, String> actual = Files.exists(cache) ? ParityFiles.read(cache) : Map.of();
        assertEquals(expected, actual, "exception13 covers exactly declared builtin cache files, not arbitrary home effects");
        Files.writeString(evidence.resolve(arm + "-expected-cache.json"), ParityScript.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected));
        Files.writeString(evidence.resolve(arm + "-cache.json"), ParityScript.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
    }
    private record Arm(String answer, Map<String, String> files, Path trace) { }
}
