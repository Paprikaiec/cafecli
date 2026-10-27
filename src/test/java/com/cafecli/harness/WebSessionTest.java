package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.web.WebCapability;
import com.cafecli.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebSessionTest {
    @TempDir Path root;
    static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel-empty", "interrupt-empty", "interrupt-error"})
    @org.junit.jupiter.api.Timeout(10)
    void cancellingANestedSearchCannotReleaseALateProviderFallback(String abandonment) throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var searches = new AtomicInteger();
        Capability step = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), args -> {
                    started.countDown();
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return abandonment.endsWith("error")
                            ? com.cafecli.tool.ToolOutput.failure(com.cafecli.tool.ToolOutput.Status.ERROR, "late failure")
                            : com.cafecli.tool.ToolOutput.text("");
                })));
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "fast", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("done"))));
                agent.toolSnapshotStage(tools -> tools.stream().map(tool -> !tool.name().equals("web_search") ? tool : new com.cafecli.core.AgentTool() {
                    public String name() { return tool.name(); }
                    public String description() { return tool.description(); }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return tool.parameters(); }
                    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input,
                            com.cafecli.ai.CancellationToken token, java.util.function.Consumer<ToolResult> progress) { throw new AssertionError(); }
                    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input,
                            com.cafecli.ai.CancellationToken token, java.util.function.Consumer<ToolResult> progress, com.cafecli.core.ToolInvoker invoker) throws Exception {
                        try { return tool.execute(id, input, token, progress, invoker); }
                        finally { finished.countDown(); }
                    }
                }).toList());
            }
        };
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("web", "web_search", "{\"query\":\"coffee\"}")
                .toolCall("fast", "fast", "{}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().provider(provider)
                .model(new Model("step-3.7-flash", "step", "fake", 128000, 8192, Set.of())).config(config)
                .capabilities(List.of(step, new WebCapability(search(searches), new WebFetcher(), new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))).build()) {
            var run = new java.util.concurrent.FutureTask<>(() -> session.submit("coffee"));
            Thread owner = Thread.ofVirtual().start(run);
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            if (abandonment.startsWith("cancel")) session.cancel(); else owner.interrupt();
            run.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(finished.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, searches.get(), "cancellation must not start provider fallback");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ToolResult.Status.class, names = {"OK", "CANCELLED"})
    void anEmptyStepSearchResultFallsBackUnlessCancelledAndWebClosureRemovesBothLeaves(ToolResult.Status status) {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("web", "web_search", "{\"query\":\"coffee\"}").build(), FakeResponse.text("done"), FakeResponse.text("closed")));
        Capability step = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("  ").withStatus(com.cafecli.tool.ToolOutput.Status.valueOf(status.name())))));
            }
        };
        var searches = new AtomicInteger();
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        try (var session = AgentSession.builder().provider(provider)
                .model(new Model("step-3.7-flash", "step", "fake", 128000, 8192, Set.of())).config(config)
                .capabilities(List.of(step, new WebCapability(search(searches), new WebFetcher(),
                        new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))).build()) {
            session.submit("find coffee");
            assertEquals(status == ToolResult.Status.CANCELLED ? 0 : 1, searches.get());
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(status, result.status());
            assertTrue(provider.requests().getFirst().context().tools().stream().anyMatch(tool -> tool.name().equals("web_search")));
            assertTrue(provider.requests().getFirst().context().tools().stream().anyMatch(tool -> tool.name().equals("web_fetch")));
        }
        try (var session = session(provider)) {
            session.submit("closed");
            assertTrue(provider.requests().getLast().context().tools().isEmpty());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ToolResult.Status.class)
    void nestedStepSearchFallbackUsesTypedStatusRatherThanFailureLookingText(ToolResult.Status status) {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("web", "web_search", "{\"query\":\"coffee\"}").build(), FakeResponse.text("done")));
        Capability step = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("工具执行失败: fixture").withStatus(
                                com.cafecli.tool.ToolOutput.Status.valueOf(status.name())))));
            }
        };
        var searches = new AtomicInteger();
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        try (var session = AgentSession.builder().provider(provider)
                .model(new Model("step-3.7-flash", "step", "fake", 128000, 8192, Set.of())).config(config)
                .capabilities(List.of(step, new WebCapability(search(searches), new WebFetcher(),
                        new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))).build()) {
            session.submit("find coffee");
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            boolean fallback = status == ToolResult.Status.ERROR || status == ToolResult.Status.DENIED;
            assertEquals(fallback ? 1 : 0, searches.get());
            assertEquals(fallback ? ToolResult.Status.OK : status, result.status());
            if (!fallback) assertEquals(List.of(new TextBlock("🔍 [StepSearch] coffee\n\n工具执行失败: fixture")), result.message().content());
        }
    }
    @Test void webLeavesKeepExactSearchAndPolicyFailureText() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("search", "web_search", "{\"query\":\" coffee \",\"top_k\":2}")
                .toolCall("fetch", "web_fetch", "{\"url\":\"file:///etc/passwd\"}").build(), FakeResponse.text("done")));
        var searches = new AtomicInteger();
        try (var session = session(provider, new WebCapability(search(searches), new WebFetcher(),
                new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))) {
            session.submit("find coffee");
            var results = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
            assertEquals(List.of(new TextBlock("🔍 [fixture]  coffee \n\n1. Coffee guide\n   A useful guide\n   🔗 https://example.test/coffee  (official)")), results.get(0).message().content());
            assertEquals(ToolResult.Status.DENIED, results.get(1).status());
            assertEquals(List.of(new TextBlock("❌ 网络访问被拒绝: 禁止的 scheme: file（仅允许 http、https）")), results.get(1).message().content());
            assertEquals(1, searches.get());
        }
    }
    @Test void freshnessAppearsAfterTheUserInTheFirstRequestAndLeavesThePromptStable() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("fresh"), FakeResponse.text("ordinary")));
        var searches = new AtomicInteger();
        var notes = new java.util.ArrayList<String>();
        try (var session = session(provider, new WebCapability(search(searches), new WebFetcher(),
                new HtmlExtractor(), new NetworkPolicy(), notes::add))) {
            session.submit("latest coffee");
            assertEquals(2, provider.requests().getFirst().context().messages().size());
            assertEquals(UserMessage.text("latest coffee"), provider.requests().getFirst().context().messages().getFirst());
            assertEquals(UserMessage.text("""
                    ## 联网预检结果

                    用户问题包含最新/当前/2026/趋势等时效性需求。以下是本轮进入模型回答前自动执行 `web_search` 得到的结果；回答必须优先基于这些联网结果和用户提供的图片，不要声称无法实时搜索。

                    查询：latest coffee

                    🔍 [fixture] latest coffee

                    1. Coffee guide
                       A useful guide
                       🔗 https://example.test/coffee  (official)
                    """), provider.requests().getFirst().context().messages().get(1));
            session.submit("offline latest coffee");
            assertEquals(UserMessage.text("offline latest coffee"), provider.requests().get(1).context().messages().getLast());
            assertEquals(provider.requests().getFirst().context().systemPrompt(), provider.requests().get(1).context().systemPrompt());
            assertEquals(1, searches.get());
            assertEquals(List.of("  → 检测到时效性问题，已先联网搜索 \"latest coffee\""), notes);
        }
    }

    @Test void aHookBlockedNestedStepSearchNeverFallsBackToTheNetwork() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.pre_tool_use.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("web", "web_search", "{\"query\":\"coffee\"}").build(), FakeResponse.text("done")));
        var hooks = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), List.of(
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.PRE_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "block-step"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                return payload.toolName().equals("mcp__step_search__web_search")
                                        ? com.cafecli.hook.HookDecision.block("Search blocked")
                                        : com.cafecli.hook.HookDecision.cont();
                            }
                        })), null);
        Capability step = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> { throw new AssertionError("blocked StepSearch must never run"); })));
            }
        };
        var searches = new AtomicInteger();
        var model = new Model("step-3.7-flash", "step", "fake", 128000, 8192, Set.of());
        try (var session = AgentSession.builder().provider(provider).model(model).config(config).capabilities(List.of(
                hooks, step, new WebCapability(search(searches), new WebFetcher(), new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))).build()) {
            session.submit("find coffee");
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(ToolResult.Status.BLOCKED, result.status());
            assertEquals(List.of(new TextBlock("🔍 [StepSearch] coffee\n\n[Hook 拦截 / PreToolUse]\nSearch blocked")), result.message().content());
            assertEquals(0, searches.get());
        }
    }

    @org.junit.jupiter.api.Timeout(10)
    @Test void fourParallelWebCallsDelegateInlineAndKeepCallbacksOnTheOwner() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.post_tool_use.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var owner = Thread.currentThread();
        var post = new AtomicInteger();
        var hooks = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), List.of(
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.POST_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "post"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                assertSame(owner, Thread.currentThread());
                                if (payload.toolName().equals("mcp__step_search__web_search")) post.incrementAndGet();
                                return com.cafecli.hook.HookDecision.cont();
                            }
                        })), null);
        Capability step = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.beforeToolCall((call, token) -> { assertSame(owner, Thread.currentThread()); return com.cafecli.core.ToolPreflight.allow(call); });
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("result " + args.path("query").asText()))));
            }
        };
        var response = FakeResponse.builder();
        for (int i = 0; i < 4; i++) response.toolCall("web" + i, "web_search", "{\"query\":\"coffee" + i + "\"}");
        var provider = FakeProvider.scripted(List.of(response.build(), FakeResponse.text("done")));
        var searches = new AtomicInteger();
        var model = new Model("step-3.7-flash", "step", "fake", 128000, 8192, Set.of());
        var permissions = com.cafecli.harness.permission.PermissionCapability.headless();
        var audit = new com.cafecli.harness.permission.AuditCapability(permissions, new com.cafecli.policy.AuditLog(root.resolve("audit")));
        try (var session = AgentSession.builder().provider(provider).model(model).config(config).capabilities(List.of(
                permissions, audit, hooks, step, new WebCapability(search(searches), new WebFetcher(), new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))).build()) {
            var events = new java.util.ArrayList<com.cafecli.core.AgentEvent>(); session.subscribe(events::add);
            session.submit("find coffee");
            audit.flush();
            try (var files = java.nio.file.Files.list(root.resolve("audit"))) {
                var rows = files.filter(path -> path.toString().endsWith(".jsonl")).flatMap(path -> {
                    try { return java.nio.file.Files.readAllLines(path).stream(); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }).toList();
                assertEquals(4, rows.size(), "each nested MCP normal return must retain its audit record");
                assertTrue(rows.stream().allMatch(row -> row.contains("mcp__step_search__web_search") && row.contains("allow")));
            }
            assertEquals(4, post.get(), "auxiliary MCP calls must receive PostToolUse");
            assertEquals(0, searches.get());
            assertEquals(4, events.stream().filter(com.cafecli.core.AgentEvent.ToolEnd.class::isInstance).count());
            assertEquals(4, session.log().stream().filter(ToolResultEntry.class::isInstance).count());
        }
    }

    @Test void freshnessStartsHooksBeforeItsNestedSearchAndRetainsPostContextAndAudit() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.pre_tool_use.enabled", "true",
                "cafecli.hook.post_tool_use.enabled", "true", "cafecli.hook.stop.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var pre = new java.util.ArrayList<String>();
        var post = new java.util.ArrayList<String>();
        var hooks = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), List.of(
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.PRE_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "pre"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                pre.add(payload.toolName()); return com.cafecli.hook.HookDecision.cont();
                            }
                        }),
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.POST_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "post"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                post.add(payload.toolName());
                                return com.cafecli.hook.HookDecision.cont().withAdditionalContext("post " + payload.toolName());
                            }
                        })), null);
        Capability step = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__step_search__web_search", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("fresh fixture"))));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("done")));
        var searches = new AtomicInteger();
        var permissions = com.cafecli.harness.permission.PermissionCapability.headless();
        var audit = new com.cafecli.harness.permission.AuditCapability(permissions, new com.cafecli.policy.AuditLog(root.resolve("audit")));
        try (var session = AgentSession.builder().provider(provider)
                .model(new Model("step-3.7-flash", "step", "fake", 128000, 8192, Set.of())).config(config)
                .capabilities(List.of(permissions, audit, hooks, step, new WebCapability(search(searches),
                        new WebFetcher(), new HtmlExtractor(), new NetworkPolicy(), ignored -> {}))).build()) {
            var events = new java.util.ArrayList<com.cafecli.core.AgentEvent>(); session.subscribe(events::add);
            session.submit("latest coffee");
            assertEquals(List.of("web_search", "mcp__step_search__web_search"), pre);
            assertEquals(List.of("mcp__step_search__web_search", "web_search"), post);
            assertEquals(0, searches.get());
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.web.Freshness.class::isInstance).count());
            assertEquals(2, session.log().stream().filter(com.cafecli.harness.hooks.HookContext.class::isInstance).count());
            assertTrue(provider.requests().getFirst().context().messages().toString().contains("fresh fixture"));
            assertTrue(provider.requests().getFirst().context().messages().toString().contains("post mcp__step_search__web_search"));
            assertEquals(0, events.stream().filter(com.cafecli.core.AgentEvent.ToolEnd.class::isInstance).count());
            assertEquals(0, session.log().stream().filter(ToolResultEntry.class::isInstance).count());
            audit.flush();
            try (var files = java.nio.file.Files.list(root.resolve("audit"))) {
                var rows = files.filter(path -> path.toString().endsWith(".jsonl")).flatMap(path -> {
                    try { return java.nio.file.Files.readAllLines(path).stream(); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }).toList();
                assertEquals(1, rows.size());
                assertTrue(rows.getFirst().contains("mcp__step_search__web_search"));
            }
        }
    }

    private SearchProvider search(AtomicInteger searches) {
        return new SearchProvider() {
            public String name() { return "fixture"; }
            public boolean isReady() { return true; }
            public String unavailableHint() { return "unavailable"; }
            public List<SearchResult> search(String query, int topK) {
                searches.incrementAndGet();
                return List.of(new SearchResult(1, "Coffee guide", "https://example.test/coffee", "A useful guide", "official"));
            }
        };
    }
    private AgentSession session(FakeProvider provider, Capability... capabilities) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        return AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(capabilities)).build();
    }
}
