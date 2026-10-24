package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.harness.config.*;
import com.cafecli.harness.context.CompactionCapability;
import com.cafecli.harness.context.Summary;
import com.cafecli.harness.trace.TraceCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelSwitchSessionTest {
    @TempDir Path root;
    private static final Model LARGE = new Model("large", "deepseek", "openai-completions", 1_000_000, 8192, Set.of());
    private static final Model SMALL = new Model("small", "step", "openai-completions", 4_000, 8192, Set.of());

    @Test void switchPreservesLogAndUpdatesRequestsPromptCompactionAndTrace() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                FakeResponse.text("three"), FakeResponse.text("summary"), FakeResponse.text("four")));
        Path handoff = Files.createDirectories(root.resolve(".cafecli/prompts")).resolve("handoff.md");
        Files.writeString(handoff, "OLD PROMPT");
        Path traceFile = root.resolve("trace.jsonl");
        try (var session = AgentSession.builder().provider(provider).model(LARGE)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(new SessionCapability(), new CompactionCapability(provider), new TraceCapability(traceFile))).build()) {
            session.submit("a".repeat(20_000)); session.submit("two"); session.submit("three");
            var original = session.log();
            assertFalse(original.stream().anyMatch(Summary.class::isInstance));
            Files.writeString(handoff, "NEW PROMPT");
            session.switchModel(SMALL);
            session.submit("four");
            assertEquals(SMALL, provider.requests().getLast().model());
            assertEquals(SMALL, provider.requests().get(3).model()); // Internal compaction follows the actual model too.
            assertTrue(provider.requests().getLast().context().systemPrompt().contains("NEW PROMPT"));
            assertFalse(provider.requests().getLast().context().systemPrompt().contains("OLD PROMPT"));
            assertEquals(original, session.log().subList(0, original.size()));
            assertTrue(session.log().stream().anyMatch(Summary.class::isInstance));
            assertTrue(provider.requests().getLast().context().messages().toString().contains("summary"));
            assertFalse(provider.requests().getLast().context().messages().toString().contains("a".repeat(20_000)));
        }
        var json = new ObjectMapper();
        var lines = Files.readAllLines(traceFile).stream().map(line -> {
            try { return json.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        var result = lines.stream().filter(node -> node.path("type").asText().equals("result")).findFirst().orElseThrow();
        assertEquals("step", result.path("provider").asText());
        assertEquals("small", result.path("model").asText());
        assertTrue(lines.getFirst().path("text").asText().contains("NEW PROMPT"));
    }
    @Test void clearKeepsTheSelectedModelAndRejectsSwitchingWhileClosed() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one")));
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var session = AgentSession.builder().provider(provider).model(LARGE).config(config)
                .capabilities(List.of(new SessionCapability())).build();
        try (session) {
            session.switchModel(SMALL); session.clear(); session.submit("clear");
            assertEquals(SMALL, provider.requests().getLast().model());
        }
        assertThrows(IllegalStateException.class, () -> session.switchModel(LARGE));
    }

    @Test void recallBudgetFollowsTheNewWindowWithoutReattachingMemory() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two")));
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        String fact = "coffee stable project preference " + "x".repeat(4_000);
        try (var session = AgentSession.builder().provider(provider).model(LARGE).config(config)
                .capabilities(List.of(new com.cafecli.harness.memory.MemoryCapability(config.memory()))).build()) {
            session.dispatch("/save " + fact).orElseThrow();
            session.submit("coffee");
            assertTrue(provider.requests().getFirst().context().messages().getLast().toString().contains(fact));
            session.switchModel(SMALL);
            session.submit("coffee");
            assertFalse(provider.requests().getLast().context().messages().getLast().toString().contains(fact));
        }
    }

    @Test void webSelectsStepSearchFromTheCurrentModelAfterEachSwitch() {
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("a", "web_search", "{\"query\":\"coffee\"}").build(), FakeResponse.text("one"),
                FakeResponse.builder().toolCall("b", "web_search", "{\"query\":\"coffee\"}").build(), FakeResponse.text("two"),
                FakeResponse.builder().toolCall("c", "web_search", "{\"query\":\"coffee\"}").build(), FakeResponse.text("three")));
        var searches = new java.util.concurrent.atomic.AtomicInteger();
        var steps = new java.util.concurrent.atomic.AtomicInteger();
        var search = new com.cafecli.web.SearchProvider() {
            public String name() { return "fixture"; }
            public boolean isReady() { return true; }
            public String unavailableHint() { return "unavailable"; }
            public List<com.cafecli.web.SearchResult> search(String query, int topK) {
                searches.incrementAndGet(); return List.of();
            }
        };
        Capability step = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new ObjectMapper().createObjectNode(), args -> {
                    steps.incrementAndGet(); return com.cafecli.tool.ToolOutput.text("step result");
                })));
            }
        };
        var web = new com.cafecli.harness.web.WebCapability(search, new com.cafecli.web.WebFetcher(),
                new com.cafecli.web.HtmlExtractor(), new com.cafecli.web.NetworkPolicy(), ignored -> {});
        try (var session = AgentSession.builder().provider(provider).model(LARGE)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(step, web)).build()) {
            session.submit("coffee"); assertEquals(1, searches.get()); assertEquals(0, steps.get());
            session.switchModel(new com.cafecli.ai.ModelRegistry().resolve("step", "step-3.7-flash"));
            session.submit("coffee"); assertEquals(1, searches.get()); assertEquals(1, steps.get());
            session.switchModel(LARGE);
            session.submit("coffee"); assertEquals(2, searches.get()); assertEquals(1, steps.get());
        }
    }

}
