package com.cafecli.app;

import com.cafecli.harness.config.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemainingProvidersCompositionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void capturedProviderConfigurationDrivesRealCompositionAtEachLocalEndpoint() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requests.add(JSON.readTree(exchange.getRequestBody()));
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Path home = Files.createDirectories(root.resolve("home/.cafecli"));
            Path configFile = home.resolve("config.json");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String name : List.of("step", "kimi", "freellmapi")) {
                Files.writeString(configFile, "{\"defaultProvider\":\"" + name + "\",\"providers\":{\"" + name
                        + "\":{\"apiKey\":\"fixture-key\",\"baseUrl\":\"" + base + "/" + name + "/v1///\",\"model\":\"wire-model\"}}}");
                var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false", "cafecli.lsp.enabled", "false"),
                        Map.of(), root.resolve("home"), root));
                try (var session = Composition.headlessSession(config)) { session.submit("hello"); }
                assertEquals("/" + name + "/v1/chat/completions", paths.getLast());
                assertEquals("wire-model", requests.getLast().path("model").asText());
            }
        } finally { server.stop(0); }
    }
    @Test void modelCommandRoutesCapturedProvidersKeepsHistoryAndPersistsOnlyConfigValues() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requests.add(JSON.readTree(exchange.getRequestBody()));
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Path file = Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Files.writeString(file, "{\"defaultProvider\":\"stepfun\",\"keep\":{\"nested\":42},\"providers\":{\"step\":{\"apiKey\":\"file-key\",\"baseUrl\":\"" + base + "/step\",\"model\":\"step-router-v1\"}}}");
            var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false", "cafecli.lsp.enabled", "false"),
                    Map.of("KIMI_API_KEY", "environment-secret", "KIMI_BASE_URL", base + "/kimi", "KIMI_MODEL", "kimi-k2.6",
                            "FREELLMAPI_API_KEY", "free-secret", "FREELLMAPI_BASE_URL", base + "/free"), root.resolve("home"), root));
            try (var session = Composition.headlessSession(config)) {
                assertTrue(session.dispatch("/model").orElseThrow().contains("step-router-v1 (step)"));
                session.submit("first");
                var original = session.log();
                assertTrue(session.dispatch("/model Step-Custom").orElseThrow().contains("Step-Custom (step)"));
                session.submit("second");
                assertTrue(session.dispatch("/model moonshot").orElseThrow().contains("kimi-k2.6 (kimi)"));
                session.submit("third");
                assertEquals(original, session.log().subList(0, original.size()));
                assertTrue(session.dispatch("/model step").orElseThrow().contains("Step-Custom (step)"));
                session.submit("fourth");
                var beforeFailure = session.model();
                assertTrue(session.dispatch("/model glm-5v-turbo").orElseThrow().contains("未配置 glm"));
                assertEquals(beforeFailure, session.model());
                assertTrue(session.dispatch("/model free-llm-api").orElseThrow().contains("auto (freellmapi)"));
                session.submit("fifth");
                assertEquals(List.of("/step/chat/completions", "/step/chat/completions", "/kimi/chat/completions",
                        "/step/chat/completions", "/free/chat/completions"), paths);
                assertEquals(List.of("step-router-v1", "Step-Custom", "kimi-k2.6", "Step-Custom", "auto"),
                        requests.stream().map(node -> node.path("model").asText()).toList());
                assertTrue(requests.getLast().path("messages").toString().contains("first"));
                var saved = JSON.readTree(file.toFile());
                assertEquals("freellmapi", saved.path("defaultProvider").asText());
                assertEquals("Step-Custom", saved.path("providers").path("step").path("model").asText());
                assertEquals("glm-5v-turbo", saved.path("providers").path("glm").path("model").asText());
                assertEquals(42, saved.path("keep").path("nested").asInt());
                assertFalse(Files.readString(file).contains("environment-secret"));
                assertFalse(Files.readString(file).contains("free-secret"));
                assertFalse(saved.path("providers").has("kimi"));
            }
        } finally { server.stop(0); }
    }

    @Test void startupAliasCredentialsAreNotMergedIntoLaterCanonicalSelection() throws Exception {
        Path file = Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"moonshot\",\"providers\":{\"moonshot\":{\"apiKey\":\"alias-key\",\"model\":\"alias-model\"}}}");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false", "cafecli.lsp.enabled", "false"),
                Map.of(), root.resolve("home"), root));
        try (var session = Composition.headlessSession(config)) {
            assertEquals("alias-model", session.model().id());
            String before = Files.readString(file);
            assertTrue(session.dispatch("/model kimi").orElseThrow().contains("未配置 kimi"));
            assertEquals("alias-model", session.model().id());
            assertEquals(before, Files.readString(file));
        }
    }

    @Test void canonicalSelectionLeavesTheCapturedStartupAliasRouteEvenWhenModelIdsMatch() throws Exception {
        Path file = Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"moonshot\",\"providers\":{\"kimi\":{\"apiKey\":\"canonical-key\"},\"moonshot\":{\"baseUrl\":\"http://127.0.0.1:9/alias\",\"model\":\"kimi-k2.6\"}}}");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false", "cafecli.lsp.enabled", "false"),
                Map.of(), root.resolve("home"), root));
        try (var session = Composition.headlessSession(config)) {
            assertEquals("moonshot", session.model().providerRoute());
            assertEquals("kimi", session.model().provider());
            assertEquals("kimi-k2.6", session.model().id());
            assertTrue(session.dispatch("/model kimi").orElseThrow().contains("moonshot-context-cache"));
            assertEquals("kimi", session.model().providerRoute());
            assertEquals("kimi-k2.6", session.model().id());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"bad\"", "[]", "{\"glm\":\"bad\",\"other\":{\"keep\":42}}", "{\"glm\":[],\"other\":{\"keep\":42}}"})
    void malformedProviderContainersCanUseCapturedEnvironmentKeysAndPersistAnExplicitModel(String providers) throws Exception {
        Path file = Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
        Files.writeString(file, "{\"keep\":17,\"providers\":" + providers + "}");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false", "cafecli.lsp.enabled", "false"),
                Map.of("GLM_API_KEY", "environment-secret"), root.resolve("home"), root));
        try (var session = Composition.headlessSession(config)) {
            assertTrue(session.dispatch("/model glm-5v-turbo").orElseThrow().contains("glm-5v-turbo (glm)"));
            assertEquals("glm-5v-turbo", session.model().id());
            var saved = JSON.readTree(file.toFile());
            assertEquals("glm-5v-turbo", saved.path("providers").path("glm").path("model").asText());
            assertEquals(17, saved.path("keep").asInt());
            if (providers.startsWith("{")) assertEquals(42, saved.path("providers").path("other").path("keep").asInt());
            assertFalse(Files.readString(file).contains("environment-secret"));
        }
    }

    @Test void modelCommandReportsTheActualConfiguredWindowAndThresholdBelowV1sMinimum() throws Exception {
        Path file = Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"step\",\"providers\":{\"step\":{\"apiKey\":\"fixture-key\"}},"
                + "\"models\":[{\"provider\":\"step\",\"id\":\"step-tiny\",\"contextWindow\":4000}]}");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false", "cafecli.lsp.enabled", "false"),
                Map.of(), root.resolve("home"), root));
        try (var session = Composition.headlessSession(config)) {
            String output = session.dispatch("/model step-tiny").orElseThrow();
            assertEquals(4000, session.model().contextWindow());
            assertTrue(output.contains("window: 4000 | 压缩阈值: 90% (3600 tokens)"), output);
            assertTrue(output.contains("MCP resource 索引: off | prompt cache: step-prefix-cache"), output);
            assertFalse(output.contains("短期记忆预算"), output);
        }
    }

}
