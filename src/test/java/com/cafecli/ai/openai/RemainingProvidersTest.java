package com.cafecli.ai.openai;

import com.cafecli.ai.*;
import com.cafecli.ai.message.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RemainingProvidersTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void glmChoosesItsEndpointAndRawBase64FromEachRequestsModel() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requests.add(JSON.readTree(exchange.getRequestBody()));
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"reasoning\":\"plan\",\"content\":\"answer\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"prompt_tokens_details\":{\"cached_tokens\":8}}}\n\ndata: [DONE]\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var provider = new GlmProvider("key", base + "/coding", base + "/vision", HttpTimeouts.DEFAULT);
            Context context = new Context("", List.of(new UserMessage(List.of(new ImageBlock("AQID", "image/png")))), List.of());
            var registry = new ModelRegistry();
            var answer = provider.stream(registry.resolve("glm", "glm-5v-turbo"), context, StreamOptions.defaults()).complete();
            assertEquals("answer", answer.text());
            assertEquals(new Usage(12, 3, 8), answer.usage());
            assertEquals(new ThinkingBlock("plan"), answer.content().getFirst());
            provider.stream(registry.resolve("glm", "glm-5.1"), context, StreamOptions.defaults()).complete();
            assertEquals(List.of("/vision/chat/completions", "/coding/chat/completions"), paths);
            assertEquals("AQID", requests.getFirst().path("messages").get(0).path("content").get(0).path("image_url").path("url").asText());
            assertEquals("data:image/png;base64,AQID", requests.getLast().path("messages").get(0).path("content").get(0).path("image_url").path("url").asText());
        } finally { server.stop(0); }
    }
    @Test void remainingAdaptersKeepTheirRequestQuirksAndThinkingHistory() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var authorizations = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(JSON.readTree(exchange.getRequestBody()));
            String delta = "{\"reasoning_content\":\"plan\",\"content\":\"checking\",\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}";
            byte[] bytes = ("data: {\"choices\":[{\"delta\":" + delta + ",\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":9,\"input_tokens_details\":{\"cached_tokens\":21}}}\n\ndata: [DONE]\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1///";
            List<Provider> providers = List.of(new StepProvider("fixture-key", base, HttpTimeouts.DEFAULT),
                    new KimiProvider("fixture-key", base, HttpTimeouts.DEFAULT),
                    new FreeLlmApiProvider("fixture-key", base, HttpTimeouts.DEFAULT));
            List<String> names = List.of("step", "kimi", "freellmapi");
            List<String> models = List.of("step-3.5-flash-2603", "kimi-k2.6", "auto");
            for (int i = 0; i < providers.size(); i++) {
                Model model = new ModelRegistry().resolve(names.get(i), models.get(i));
                var user = new UserMessage(List.of(new TextBlock("look"), new ImageBlock("AQID", "image/png")));
                var tool = new ToolDefinition("read_file", "Read", JSON.readTree("{\"type\":\"object\"}"));
                List<StreamEvent> events = new ArrayList<>();
                try (var stream = providers.get(i).stream(model, new Context("system", List.of(user), List.of(tool)), StreamOptions.defaults())) {
                    stream.forEach(events::add);
                }
                var answer = assertInstanceOf(StreamEvent.Done.class, events.getLast()).message();
                assertEquals(List.of(new ThinkingBlock("plan"), new TextBlock("checking"), new ToolCall("c1", "read_file", "{}")), answer.content());
                assertEquals(new Usage(40, 9, 21), answer.usage());
                assertEquals(StopReason.TOOL_USE, answer.stopReason());
                providers.get(i).stream(model, new Context("system", List.of(user, answer,
                        new ToolResultMessage("c1", "read_file", List.of(new TextBlock("result")), false)), List.of(tool)), StreamOptions.defaults()).complete();
                var first = requests.get(i * 2);
                assertEquals(models.get(i), first.path("model").asText());
                assertEquals("read_file", first.path("tools").get(0).path("function").path("name").asText());
                assertEquals("data:image/png;base64,AQID", first.path("messages").get(1).path("content").get(1).path("image_url").path("url").asText());
                var history = requests.get(i * 2 + 1).path("messages").get(2);
                assertEquals(i == 1, history.has("reasoning_content"));
                if (i == 1) assertEquals("plan", history.path("reasoning_content").asText());
                if (i == 0) {
                    assertEquals("deepseek-style", first.path("reasoning_format").asText());
                    assertEquals("high", first.path("reasoning_effort").asText());
                }
            }
            new StepProvider("fixture-key", base, HttpTimeouts.DEFAULT).stream(new ModelRegistry().defaultModel("step"),
                    new Context("", List.of(), List.of()), StreamOptions.defaults()).complete();
            assertFalse(requests.getLast().has("reasoning_effort"));
            assertEquals(java.util.Collections.nCopies(7, "Bearer fixture-key"), authorizations);
        } finally { server.stop(0); }
    }

    @Test void explicitModelRouteSelectsCapturedWireConfigurationWithCanonicalProviderMetadata() throws Exception {
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath()); exchange.getRequestBody().readAllBytes();
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var router = new RoutingProvider(java.util.Map.of("kimi", new KimiProvider("canonical-key", base + "/canonical", HttpTimeouts.DEFAULT),
                    "moonshot", new KimiProvider("alias-key", base + "/alias", HttpTimeouts.DEFAULT)));
            var canonical = new ModelRegistry().defaultModel("kimi");
            var startup = new Model(canonical.id(), canonical.provider(), canonical.api(), canonical.contextWindow(),
                    canonical.maxOutputTokens(), canonical.features(), "moonshot");
            var context = new Context("", List.of(), List.of());
            var answer = router.stream(startup, context, StreamOptions.defaults()).complete();
            assertEquals("kimi", answer.provider());
            assertEquals("kimi-k2.6", answer.model());
            router.stream(canonical, context, StreamOptions.defaults()).complete();
            router.stream(startup, context, StreamOptions.defaults()).complete(); // A captured child still uses startup wire settings.
            assertEquals(List.of("/alias/chat/completions", "/canonical/chat/completions", "/alias/chat/completions"), paths);
        } finally { server.stop(0); }
    }

}
