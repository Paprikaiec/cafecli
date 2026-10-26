package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.core.ToolResult;
import com.cafecli.core.ToolPreflight;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.lsp.LspCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LspSessionTest {
    @TempDir Path root;
    private static final Model MODEL = new Model("fake", "fake", "fake", 128_000, 8192, Set.of());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void postWriteDiagnosticsHookContextAndBusySteeringReachTheFirstEligibleRequestInLegacyOrder(boolean summarizeSources) {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.post_tool_use.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var hooks = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), List.of(
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.POST_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "context"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                return com.cafecli.hook.HookDecision.cont().withAdditionalContext("HOOK_CONTEXT");
                            }
                        })), null);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}").build(), FakeResponse.text("done")));
        Capability sourceSummary = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                if (!summarizeSources) return;
                scope.messageKind(com.cafecli.harness.context.Summary.KIND);
                scope.sourceProjectionStage(com.cafecli.harness.context.SummaryProjection::apply);
                scope.iterationBoundaryWriter((log,token) -> log.size()==6
                        ? List.of(new com.cafecli.harness.context.Summary(3,5,"covered steering and hook")) : List.of());
            }
        };
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), new WorkspaceCapability(root), hooks,
                        new LspCapability(config.lsp()),sourceSummary)).build()) {
            session.subscribe(event -> {
                if (event instanceof com.cafecli.core.AgentEvent.ToolStart)
                    assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("STEER_NEXT"));
            });
            session.submit("write it");
            var next = provider.requests().get(1).context().messages();
            var contexts = next.subList(3, next.size()).stream().map(message ->
                    ((TextBlock) ((UserMessage) message).content().getFirst()).text()).toList();
            if (summarizeSources) {
                assertEquals(2,contexts.size());
                assertEquals("[已压缩的历史对话摘要]\ncovered steering and hook",contexts.get(0));
                assertTrue(contexts.get(1).startsWith("[LSP 诊断注入]\n"),contexts.toString());
            } else {
                assertEquals(3, contexts.size());
                assertTrue(contexts.get(0).startsWith("[LSP 诊断注入]\n"), contexts.toString());
                assertEquals("[Hook 上下文 / PostToolUse]\nHOOK_CONTEXT", contexts.get(1));
                assertEquals("[用户转向 / 回合中提交]\nSTEER_NEXT", contexts.get(2));
            }
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.context.Steering.class::isInstance).count());
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.hooks.HookContext.class::isInstance).count());
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.lsp.LspDiagnostics.class::isInstance).count());
            assertInstanceOf(com.cafecli.harness.context.Steering.class, session.log().get(3),
                    "the source keeps arrival order even though the request uses legacy presentation order");
        }
    }

    @Test void aWrittenJavaDiagnosticArrivesTaggedBeforeTheVeryNextModelRequest() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}").build(), FakeResponse.text("fix it")));
        try (var session = session(provider)) {
            session.submit("write it");
            assertEquals("class Broken {", Files.readString(root.resolve("Broken.java")));
            var messages = provider.requests().get(1).context().messages();
            assertEquals(4, messages.size(), "diagnostics must reach iteration two, after the write result");
            var text = assertInstanceOf(TextBlock.class,
                    assertInstanceOf(UserMessage.class, messages.getLast()).content().getFirst()).text();
            assertTrue(text.startsWith("[LSP 诊断注入]\n"));
            assertTrue(text.contains("[error] Broken.java:"));
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.lsp.LspDiagnostics.class::isInstance).count());
            assertEquals(provider.requests().get(0).context().systemPrompt(), provider.requests().get(1).context().systemPrompt());
        }
    }

    @Test void capturedDisabledConfigurationSuppressesPostWriteDiagnostics() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}").build(), FakeResponse.text("done")));
        try (var session = session(provider, Map.of("cafecli.lsp.enabled", "false", "paicli.lsp.enabled", "true"))) {
            session.submit("write it");
            assertEquals(3, provider.requests().get(1).context().messages().size());
            assertEquals("class Broken {", Files.readString(root.resolve("Broken.java")));
        }
    }

    @Test void aPreflightSuppliedResultIsNotAWriteEvenWhenItCarriesDiffShapedDetails() throws Exception {
        Files.writeString(root.resolve("Broken.java"), "class Broken {");
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"Broken.java\",\"content\":\"class Broken {}\"}").build(), FakeResponse.text("done")));
        Capability supplied = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> ToolPreflight.complete(call,
                        new ToolResult(List.of(new TextBlock("supplied")),
                                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                        .set("diff", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                                .put("path", "Broken.java")), ToolResult.Status.OK)));
            }
        };
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new WorkspaceCapability(root), supplied,
                        new LspCapability(config.lsp()))).build()) {
            session.submit("write it");
            assertEquals(3, provider.requests().get(1).context().messages().size());
            assertEquals("class Broken {", Files.readString(root.resolve("Broken.java")));
        }
    }

    @Test void capturedDiagnosticLimitKeepsOneLeafFormattedItemForParallelWrites() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("one", "write_file", "{\"path\":\"A.java\",\"content\":\"class A {\"}")
                .toolCall("two", "write_file", "{\"path\":\"B.java\",\"content\":\"class B {\"}").build(),
                FakeResponse.text("done")));
        try (var session = session(provider, Map.of("cafecli.lsp.max.diagnostics", "1"))) {
            session.submit("write both");
            var text = assertInstanceOf(TextBlock.class, assertInstanceOf(UserMessage.class,
                    provider.requests().get(1).context().messages().getLast()).content().getFirst()).text();
            assertEquals(1, text.lines().filter(line -> line.startsWith("- [error]")).count());
            assertTrue(text.contains("还有 1 条诊断未注入（已达到上限 1）"));
        }
    }

    @Test void anUndrainedReportStaysWithItsAgentAndClearDiscardsIt() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.react.hard.max.iterations", "1"),
                Map.of(), root.resolve("home"), root));
        var lsp = new LspCapability(config.lsp());
        var first = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}").build(), FakeResponse.text("fresh")));
        var second = FakeProvider.scripted(List.of(FakeResponse.text("other")));
        try (var one = AgentSession.builder().provider(first).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), new WorkspaceCapability(root), lsp)).build();
             var two = AgentSession.builder().provider(second).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), new WorkspaceCapability(root), lsp)).build()) {
            assertInstanceOf(RunOutcome.BudgetExhausted.class,
                    assertInstanceOf(SubmitResult.Completed.class, one.submit("write it")).outcome());
            two.submit("other input");
            assertEquals(List.of(UserMessage.text("other input")), second.requests().getFirst().context().messages());
            one.dispatch("/clear");
            one.submit("fresh input");
            assertEquals(List.of(UserMessage.text("fresh input")), first.requests().get(1).context().messages());
        }
    }

    private AgentSession session(FakeProvider provider) {
        return session(provider, Map.of());
    }

    private AgentSession session(FakeProvider provider, Map<String, String> properties) {
        var config = ConfigLoader.load(new ConfigSources(properties, Map.of(), root.resolve("home"), root));
        return AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), new WorkspaceCapability(root), new LspCapability(
                        config.lsp()))).build();
    }
}
