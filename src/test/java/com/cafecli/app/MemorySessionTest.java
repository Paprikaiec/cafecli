package com.cafecli.app;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.BoundaryInbox;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionCapability;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.SubmitResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.harness.context.Steering;
import com.cafecli.harness.hooks.HookContext;
import com.cafecli.harness.memory.MemoryCapability;
import com.cafecli.harness.permission.ApprovalResult;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.memory.LongTermMemory;
import com.cafecli.memory.MemoryEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MemorySessionTest {
    @TempDir Path root;

    @Test void longTermRecallFollowsEachUserWhileTheSystemPromptStaysByteIdentical() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var store = new LongTermMemory(config.homes().user().resolve("memory").toFile());
        store.store(new MemoryEntry("a", "Apricot uses Java", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 5));
        store.store(new MemoryEntry("b", "Blueberry uses Maven", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 5));
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two")));
        try (var session = session(provider)) {
            session.submit("Apricot");
            session.submit("Blueberry");
        }
        var first = provider.requests().get(0).context();
        var second = provider.requests().get(1).context();
        assertEquals(first.systemPrompt(), second.systemPrompt());
        assertEquals(UserMessage.text("Apricot"), first.messages().get(0));
        assertTrue(first.messages().size() > 1, "recall must follow the user message");
        assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot uses Java\n\n"), first.messages().get(1));
        assertEquals(UserMessage.text("Blueberry"), second.messages().get(3));
        assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Blueberry uses Maven\n\n"), second.messages().get(4));
        assertFalse(first.systemPrompt().contains("Apricot"));
    }

    @Test void saveRecallsInANewSessionAndOrdinaryRunsWriteNothing() throws Exception {
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("temporary"))))) {
            session.submit("TemporaryApricotFact");
            assertFalse(Files.exists(root.resolve("home/.cafecli/memory/long_term_memory.json")),
                    "ordinary user and assistant messages must not persist any facts");
            assertEquals("📋 长期记忆列表：\n📭 没有匹配的长期记忆。\n\n",
                    session.dispatch("/memory list").orElse("missing command"));
            assertEquals("💾 已保存到长期记忆(project): Apricot project uses Java\n\n",
                    session.dispatch("/save Apricot project uses Java").orElseThrow());
        }
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("remembered")));
        try (var session = session(provider)) {
            session.submit("Apricot");
            assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot project uses Java\n\n"),
                    provider.requests().get(0).context().messages().get(1));
        }
    }

    @Test void explicitSaveMemoryUsesTheLeafToolAndPersistsItsFact() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("save", "save_memory", "{\"fact\":\"Apricot tool fact\",\"scope\":\"global\"}").build(),
                FakeResponse.text("saved")));
        try (var session = session(provider)) {
            session.submit("Remember Apricot tool fact globally");
            var result = assertInstanceOf(ToolResultEntry.class, session.log().get(2));
            assertEquals(ToolResult.Status.OK, result.status());
            assertEquals(List.of(new TextBlock("💾 已保存到长期记忆(global): Apricot tool fact")), result.message().content());
        }
        var later = FakeProvider.scripted(List.of(FakeResponse.text("yes")));
        try (var session = session(later)) {
            session.submit("Apricot");
            assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot tool fact\n\n"),
                    later.requests().get(0).context().messages().get(1));
        }
    }

    @Test void explicitBrowserHintUsesAssistantAndToolHistoryAndIsGlobal() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("Visit https://www.example.com/docs"),
                FakeResponse.builder().toolCall("history", "save_memory", "{\"fact\":\"https://www.yuque.com/path\"}").build(),
                FakeResponse.text("ready"), FakeResponse.text("remembered")));
        try (var session = session(provider)) {
            session.submit("Where is it?");
            session.submit("Remember this stable URL");
            session.submit("记住，以后复用浏览器已登录的 Chrome 登录态");
            String memory = session.dispatch("/memory list").orElseThrow();
            assertTrue(memory.contains("访问 yuque.com（语雀）时优先复用用户已登录的 Chrome 登录态。"), memory);
            assertTrue(memory.contains("[global]"), memory);
            assertTrue(provider.requests().get(3).context().messages().stream().anyMatch(message ->
                    message instanceof UserMessage user && user.content().toString().contains("访问 yuque.com")),
                    "the explicit hint is saved before this run's recall");
        }
    }

    @Test void clearEmptiesTheLogAndRebuildsThePromptWhileKeepingLongTermFacts() throws Exception {
        Path override = Files.createDirectories(root.resolve(".cafecli/prompts")).resolve("handoff.md");
        Files.writeString(override, "Old handoff");
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("first"), FakeResponse.text("second")));
        try (var session = session(provider)) {
            session.dispatch("/save Apricot survives clear");
            session.submit("hello");
            Files.writeString(override, "Fresh handoff");
            assertEquals("🗑️ 当前对话历史已清空，长期记忆保持不变\n\n",
                    session.dispatch("/clear").orElse("missing command"));
            assertEquals(List.of(), session.log());
            session.submit("Apricot");
            assertTrue(provider.requests().get(0).context().systemPrompt().endsWith("Old handoff"));
            assertTrue(provider.requests().get(1).context().systemPrompt().endsWith("Fresh handoff"));
            assertEquals(UserMessage.text("Apricot"), provider.requests().get(1).context().messages().get(0));
            assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot survives clear\n\n"),
                    provider.requests().get(1).context().messages().get(1));
        }
    }

    @Test void explicitPromptInvalidationTakesEffectAtTheNextRequestAndKeepsTheLog() throws Exception {
        Path override = Files.createDirectories(root.resolve(".cafecli/prompts")).resolve("handoff.md");
        Files.writeString(override, "Original handoff");
        var reference = new AtomicReference<AgentSession>();
        var calls = new AtomicInteger();
        var provider = FakeProvider.responding(request -> {
            if (calls.incrementAndGet() == 1) {
                try { Files.writeString(override, "Rebuilt handoff"); }
                catch (IOException failure) { throw new RuntimeException(failure); }
                reference.get().rebuildSystemPrompt();
                reference.get().rebuildSystemPrompt();
                return FakeResponse.builder().toolCall("save", "save_memory", "{\"fact\":\"stable\"}").build();
            }
            return FakeResponse.text("done");
        });
        try (var session = session(provider)) {
            reference.set(session);
            session.submit("first");
            assertTrue(provider.requests().get(0).context().systemPrompt().endsWith("Original handoff"));
            assertTrue(provider.requests().get(1).context().systemPrompt().endsWith("Rebuilt handoff"));
            Files.writeString(override, "Do not rebuild implicitly");
            session.submit("next");
            assertEquals(provider.requests().get(1).context().systemPrompt(), provider.requests().get(2).context().systemPrompt());
            assertEquals(UserMessage.text("first"), provider.requests().get(2).context().messages().get(0));
        }
    }

    @Test void projectFactsRecallThroughASymlinkToTheSameProject() throws Exception {
        Path alias = root.resolveSibling(root.getFileName() + "-alias");
        Files.createSymbolicLink(alias, root);
        try {
            try (var session = session(FakeProvider.scripted(List.of()))) {
                session.dispatch("/save Apricot canonical project");
            }
            var provider = FakeProvider.scripted(List.of(FakeResponse.text("yes")));
            var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), alias));
            try (var session = AgentSession.builder().config(config).provider(provider).model(Composition.FAKE_MODEL)
                    .capabilities(List.of(new MemoryCapability(config.memory()))).build()) {
                session.submit("Apricot");
                assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot canonical project\n\n"),
                        provider.requests().get(0).context().messages().get(1));
            }
        } finally { Files.deleteIfExists(alias); }
    }

    @Test void scopesAliasesDeleteAndWholeStoreClearKeepV1Semantics() throws Exception {
        try (var first = session(FakeProvider.scripted(List.of()))) {
            first.dispatch("/save Apricot project-only");
            first.dispatch("/save --global Apricot global");
        }
        Path other = Files.createDirectory(root.resolve("other"));
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), other));
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("done")));
        try (var session = AgentSession.builder().config(config).provider(provider).model(Composition.FAKE_MODEL)
                .capabilities(List.of(new MemoryCapability(config.memory()))).build()) {
            session.submit("Apricot");
            assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot global\n\n"),
                    provider.requests().get(0).context().messages().get(1));
            String listed = session.dispatch("/MEM LIST").orElseThrow();
            assertTrue(listed.contains("Apricot project-only"), "list deliberately includes other projects");
            String searched = session.dispatch("/mem search Apricot").orElseThrow();
            assertTrue(searched.contains("Apricot global"));
            assertFalse(searched.contains("Apricot project-only"));
            String id = listed.lines().filter(line -> line.contains("[project]")).findFirst().orElseThrow().split(" ")[1];
            assertEquals("🗑️ 已删除长期记忆: " + id + "\n\n", session.dispatch("/memory delete " + id).orElseThrow());
            assertFalse(session.dispatch("/memory list").orElseThrow().contains("Apricot project-only"));
            session.dispatch("/memory clear");
        }
        try (var session = session(FakeProvider.scripted(List.of()))) {
            assertEquals("📋 长期记忆列表：\n📭 没有匹配的长期记忆。\n\n", session.dispatch("/memory list").orElseThrow());
        }
    }

    @Test void clearCreatesFreshAgentStatePreservesSessionIdentityAndForgetsApprovalGrants() {
        var approvals = new AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> {
            approvals.incrementAndGet(); return ApprovalResult.approveAll();
        }, true);
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var state = new Capability() {
            String id;
            @Override public void attachSession(SessionRegistrar session) { id = session.sessionId(); }
            @Override public void attachAgent(AgentScope agent) {
                int[] runs = {0};
                agent.messageKind(MessageKind.tagged("state", State.class, "state", State::text));
                agent.runStartWriter((input, token) -> List.of(new State(id + ":" + ++runs[0])));
            }
        };
        // One write per run, followed by a normal response, independently of previous history.
        var iterations = new AtomicInteger();
        var provider = FakeProvider.responding(request -> iterations.incrementAndGet() % 2 == 1
                ? FakeResponse.builder().toolCall("write" + iterations.get(), "write_file",
                        "{\"path\":\"state.txt\",\"content\":\"kept\"}").build() : FakeResponse.text("done"));
        try (var session = AgentSession.builder().config(config).provider(provider).model(Composition.FAKE_MODEL)
                .capabilities(List.of(new SessionCapability(),
                        new WorkspaceCapability(root), permissions, state)).build()) {
            session.submit("first");
            var first = assertInstanceOf(State.class, session.log().get(1));
            session.submit("second");
            assertEquals(1, approvals.get(), "approval is remembered across ordinary runs");
            session.dispatch("/clear");
            session.submit("fresh");
            assertEquals(first, assertInstanceOf(State.class, session.log().get(1)),
                    "same session id, fresh agent-local run counter");
            assertEquals(2, approvals.get(), "clear forgets the remembered grant");
        }
    }

    record State(String text) implements CustomEntry { }

    @Test void configuredMemoryDirectoryIsLazyAndUsesExplicitSourcePrecedence() throws Exception {
        var cases = List.of(
                Map.of("cafecli.memory.dir", "current", "paicli.memory.dir", "legacy"),
                Map.of("paicli.memory.dir", "legacy"), Map.<String, String>of());
        var expected = List.of("current", "legacy", "environment");
        for (int index = 0; index < cases.size(); index++) {
            var config = ConfigLoader.load(new ConfigSources(cases.get(index),
                    Map.of("CAFECLI_MEMORY_DIR", "environment", "PAICLI_MEMORY_DIR", "old-environment"),
                    root.resolve("home"), root));
            Path directory = root.resolve(expected.get(index));
            try (var session = AgentSession.builder().config(config).provider(FakeProvider.scripted(List.of()))
                    .model(Composition.FAKE_MODEL).capabilities(List.of(
                            new MemoryCapability(config.memory()))).build()) {
                assertFalse(Files.exists(directory), "untouched capability must not open the store");
                session.dispatch("/save Apricot configured");
                assertTrue(Files.readString(directory.resolve("long_term_memory.json")).contains("Apricot configured"));
            }
        }
    }

    @Test void recallBudgetUsesTheInjectedAgentModelMetadata() {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var store = new LongTermMemory(config.memory().directory().toFile());
        store.store(new MemoryEntry("large", "Apricot large fact", MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 4000));
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("done")));
        try (var session = AgentSession.builder().config(config).provider(provider)
                .model(new Model("large", "fake", "fake", 1000000, 8192, Set.of()))
                .capabilities(List.of(new MemoryCapability(config.memory()))).build()) {
            session.submit("Apricot");
            assertEquals(UserMessage.text("## 相关长期记忆\n\n- [FACT] Apricot large fact\n\n"),
                    provider.requests().get(0).context().messages().get(1));
        }
    }

    enum HistorySource { STEERING, HOOK_CONTEXT, HIDDEN_CONTEXT }
    record HiddenContext(String text) implements CustomEntry { }

    @ParameterizedTest
    @EnumSource(HistorySource.class)
    void explicitBrowserHintUsesTheRegisteredHistoryPresentation(HistorySource source) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var inbox = new AtomicReference<BoundaryInbox>();
        Capability context = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.messageKind(HookContext.KIND);
                agent.messageKind(MessageKind.hidden("hidden-context", HiddenContext.class));
                inbox.set(agent.inbox());
            }
        };
        var reference = new AtomicReference<AgentSession>();
        var requests = new AtomicInteger();
        String url = "https://www.history.example.com/docs";
        var provider = FakeProvider.responding(request -> {
            if (requests.incrementAndGet() == 1) {
                switch (source) {
                    case STEERING -> assertInstanceOf(SubmitResult.SteeringAccepted.class,
                            reference.get().submit(url));
                    case HOOK_CONTEXT -> inbox.get().post(new HookContext(url));
                    case HIDDEN_CONTEXT -> inbox.get().post(new HiddenContext(url));
                }
                return FakeResponse.builder().toolCall("lookup", "read_file", "{}").build();
            }
            return FakeResponse.text("done");
        });
        try (var session = AgentSession.builder().config(config).provider(provider).model(Composition.FAKE_MODEL)
                .capabilities(List.of(new MemoryCapability(config.memory()), context)).build()) {
            reference.set(session);
            session.submit("Locate the page");
            var earlierLog = session.log();
            assertTrue(earlierLog.stream().anyMatch(entry -> switch (source) {
                case STEERING -> entry instanceof Steering;
                case HOOK_CONTEXT -> entry instanceof HookContext;
                case HIDDEN_CONTEXT -> entry instanceof HiddenContext;
            }), "the history entry must have landed before the explicit remember request");
            boolean visible = provider.requests().get(1).context().messages().stream().anyMatch(message ->
                    message instanceof UserMessage user && user.content().toString().contains(url));
            assertEquals(source != HistorySource.HIDDEN_CONTEXT, visible);
            var earlierJson = session.messageKinds().json().write(earlierLog);
            session.submit("记住，以后复用浏览器已登录的 Chrome 登录态");
            String remembered = session.dispatch("/memory list").orElseThrow();
            String expected = source == HistorySource.HIDDEN_CONTEXT
                    ? "用户明确允许在需要登录态的网站访问中复用已登录 Chrome。"
                    : "访问 history.example.com 时优先复用用户已登录的 Chrome 登录态。";
            assertTrue(remembered.contains(expected), remembered);
            assertTrue(remembered.contains("[global]"), remembered);
            assertEquals(earlierJson, session.messageKinds().json().write(session.log().subList(0, earlierLog.size())),
                    "reading registered history presentation must not rewrite the agent log");
        }
    }

    private AgentSession session(FakeProvider provider) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        return AgentSession.builder().config(config).provider(provider)
                .model(new Model("fake", "fake", "fake", 128000, 8192, Set.of()))
                .capabilities(List.of(new SessionCapability(),
                        new MemoryCapability(config.memory()))).build();
    }
}
