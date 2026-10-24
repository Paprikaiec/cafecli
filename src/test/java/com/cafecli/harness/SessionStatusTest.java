package com.cafecli.harness;

import com.cafecli.ai.*;
import com.cafecli.ai.fake.*;
import com.cafecli.ai.message.*;
import com.cafecli.harness.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionStatusTest {
    @TempDir Path root;
    @Test void statusKeepsProjectedOccupancySeparateFromCumulativeRunUsageAndResetsOnlyRunUsage() throws Exception {
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("read","missing","{}").usage(new Usage(100,20,30)).build(),
                FakeResponse.builder().text("reply").usage(new Usage(200,40,50)).build(),
                FakeResponse.builder().text("again").usage(new Usage(7,3,2)).build()));
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var model = new Model("fake","fake","fake",10000,1000,Set.of());
        try (var session = AgentSession.builder().interactive(true).config(config).provider(provider).model(model).build()) {
            session.submit("source"); session.awaitIdle();
            var status = session.status();
            assertEquals(300,status.inputTokens()); assertEquals(60,status.outputTokens());
            assertEquals(80,status.cachedInputTokens()); assertEquals("¥0.0021",status.estimatedCost());
            assertTrue(status.contextTokens()>0); assertNotEquals(360,status.contextTokens());
            long previousContext = status.contextTokens();
            session.submit("next source"); session.awaitIdle();
            assertEquals(7,session.status().inputTokens()); assertEquals(3,session.status().outputTokens());
            assertEquals(2,session.status().cachedInputTokens());
            assertTrue(session.status().contextTokens()>previousContext);
        }
    }
    @Test void occupancyIncludesSystemPromptAndTheActualAdvertisedToolSchema() throws Exception {
        long base = contextWith("aaaa","bbbb");
        assertEquals(base+100,contextWith("a".repeat(404),"bbbb"));
        assertEquals(base+100,contextWith("aaaa","b".repeat(404)));
    }

    @Test void statusObservationDoesNotConsumePendingSystemPromptInvalidation() throws Exception {
        var modes = java.nio.file.Files.createDirectories(root.resolve("home/.cafecli/prompts/modes"));
        java.nio.file.Files.writeString(modes.resolve("agent.md"),"original prompt");
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("reply")));
        try (var session = AgentSession.builder().interactive(true).config(config).provider(provider)
                .model(new Model("fake","fake","fake",10000,1000,Set.of())).build()) {
            long initial = session.status().contextTokens();
            session.rebuildSystemPrompt();
            java.nio.file.Files.writeString(modes.resolve("agent.md"),"intermediate prompt must not be consumed");
            assertEquals(initial,session.status().contextTokens());
            java.nio.file.Files.writeString(modes.resolve("agent.md"),"actual next request prompt");
            session.submit("source");session.awaitIdle();
            assertTrue(provider.requests().getFirst().context().systemPrompt().contains("actual next request prompt"));
            assertFalse(provider.requests().getFirst().context().systemPrompt().contains("intermediate prompt"));
        }
    }

    @Test void clearCapturesTheCurrentDynamicToolSchemaAndResetsTheView() throws Exception {
        var description = new java.util.concurrent.atomic.AtomicReference<>("bbbb");
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        Capability tools = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.toolSource(() -> List.of(tool(description::get)));
            }
        };
        try (var session = AgentSession.builder().interactive(true).config(config)
                .provider(FakeProvider.scripted(List.of(FakeResponse.text("reply"))))
                .model(new Model("fake","fake","fake",10000,1000,Set.of())).capabilities(List.of(tools)).build()) {
            long initial = session.status().contextTokens();
            session.submit("source");session.awaitIdle();
            description.set("b".repeat(404));
            session.clear();
            assertEquals(initial+100,session.status().contextTokens());
            assertEquals(0,session.status().inputTokens());
            assertTrue(session.log().isEmpty());
        }
    }

    @Test void occupancyUsesTheProjectedViewWhileSourceUsageRemainsRetained() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        Capability hidden = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.projectionStage(log -> log.stream().filter(entry -> !(entry instanceof com.cafecli.core.log.UserEntry)).toList());
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().text("reply").usage(new Usage(100,20,30)).build()));
        try (var session = AgentSession.builder().interactive(true).config(config).provider(provider)
                .model(new Model("fake","fake","fake",10000,1000,Set.of())).capabilities(List.of(hidden)).build()) {
            long initial = session.status().contextTokens();
            session.submit("a".repeat(400));session.awaitIdle();
            assertEquals(initial+6,session.status().contextTokens()); // reply: two text tokens + four message framing.
            assertEquals(100,session.status().inputTokens());
            assertEquals(2,session.log().size());
        }
    }

    @Test void failedStatusObserversCannotStrandClearCleanupOrMisreportAnInstalledModelSwitch() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        Capability owned = new Capability() {
            @Override public void attachAgent(AgentScope scope) { scope.onClose(closes::incrementAndGet); }
        };
        var model = new Model("fake","fake","fake",10000,1000,Set.of());
        var observationFailure = new IllegalStateException("failed observer");
        var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        try (var session = AgentSession.builder().interactive(true).config(config)
                .provider(FakeProvider.scripted(List.of(FakeResponse.text("reply")))).model(model).capabilities(List.of(owned)).build()) {
            session.subscribeFailure(failures::add);
            session.subscribeStatus(status -> { throw observationFailure; });
            assertDoesNotThrow(session::clear);
            assertEquals(1,closes.get());
            assertEquals(List.of(observationFailure),failures);
            var replacement = new Model("replacement","fake","fake",20000,2000,Set.of());
            assertDoesNotThrow(() -> session.switchModel(replacement));
            assertEquals(replacement,session.model());
            assertEquals(replacement,session.status().model());
            assertEquals(List.of(observationFailure,observationFailure),failures);
        }
        assertEquals(2,closes.get());
    }

    @Test void clearPreservesOriginalAndSuppressedResourceFailuresWhenStatusObservationAlsoFails() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var primary = new IllegalStateException("primary cleanup failure");
        var secondary = new IllegalStateException("secondary cleanup failure");
        var observer = new IllegalStateException("status observation failure");
        var attached = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var reported = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        Capability owned = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                boolean original = attached.incrementAndGet()==1;
                scope.onClose(() -> { closed.incrementAndGet(); if(original) throw secondary; });
                scope.onClose(() -> { closed.incrementAndGet(); if(original) throw primary; });
            }
        };
        try (var session = AgentSession.builder().interactive(true).config(config)
                .provider(FakeProvider.scripted(List.of(FakeResponse.text("reply"))))
                .model(new Model("fake","fake","fake",10000,1000,Set.of())).capabilities(List.of(owned)).build()) {
            session.subscribeFailure(reported::add);
            session.subscribeStatus(status -> {throw observer;});
            assertSame(primary,assertThrows(IllegalStateException.class,session::clear));
            assertEquals(List.of(secondary),List.of(primary.getSuppressed()));
            assertEquals(2,closed.get()); assertEquals(List.of(observer),reported);
            assertFalse(session.isBusy());
        }
        assertEquals(4,closed.get());
    }

    private long contextWith(String mode,String description) throws Exception {
        var modes = java.nio.file.Files.createDirectories(root.resolve("home/.cafecli/prompts/modes"));
        java.nio.file.Files.writeString(modes.resolve("agent.md"),mode);
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var tool = tool(() -> description);
        Capability capability = new Capability() {
            @Override public void attachAgent(AgentScope scope) { scope.toolSource(() -> List.of(tool)); }
        };
        try (var session = AgentSession.builder().interactive(true).config(config)
                .provider(FakeProvider.scripted(List.of(FakeResponse.text("reply"))))
                .model(new Model("fake","fake","fake",10000,1000,Set.of())).capabilities(List.of(capability)).build()) {
            long initial = session.status().contextTokens();
            session.submit("source");session.awaitIdle();
            // Two literal six/five character messages add 2+4 + 2+4 tokens to the initial view.
            assertEquals(initial+12,session.status().contextTokens());
            return session.status().contextTokens();
        }
    }
    private static com.cafecli.core.AgentTool tool(java.util.function.Supplier<String> description) {
        return new com.cafecli.core.AgentTool() {
            public String name(){return "sample";}
            public String description(){return description.get();}
            public com.fasterxml.jackson.databind.JsonNode parameters(){return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();}
            public com.cafecli.core.ToolResult execute(String id,com.fasterxml.jackson.databind.JsonNode input,
                    CancellationToken cancellation,java.util.function.Consumer<com.cafecli.core.ToolResult> progress) {
                throw new AssertionError("the scripted response never calls a tool");
            }
        };
    }

}
