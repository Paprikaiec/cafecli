package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.UserEntry;
import com.cafecli.harness.context.Summary;
import com.cafecli.harness.context.SummaryProjection;
import com.cafecli.harness.context.Steering;
import com.cafecli.harness.skills.SkillsCapability;
import com.cafecli.harness.config.SkillsConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillsSessionTest {
    @TempDir Path root;
    private static final Model MODEL = new Model("fake", "fake", "fake", 128_000, 8192, Set.of());

    @Test void loadedBodyWaitsForTheNextRealUserAndKeepsExactV1Bytes() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("loaded"), FakeResponse.text("used"), FakeResponse.text("again")));
        try (var session = session(provider)) {
            session.submit("load it");
            assertEquals(List.of(new TextBlock("已加载 skill 'apricot' 的完整指引（19 bytes），将在下一轮上下文中以 \"## 已加载 Skill：apricot\" 段出现。")),
                    session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                            .findFirst().orElseThrow().message().content());
            assertFalse(provider.requests().get(1).context().messages().toString().contains("Use apricot rules."));
            session.submit("apply it");
            assertEquals(UserMessage.text("## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\napply it"),
                    provider.requests().get(2).context().messages().get(4));
            session.submit("later");
            assertEquals(UserMessage.text("later"), provider.requests().get(3).context().messages().getLast());
        }
    }

    @Test void onlySkillSetChangesRebuildTheIndexAndCommandsKeepTheLeafText() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        var provider = FakeProvider.scripted(java.util.Collections.nCopies(8, FakeResponse.text("done")));
        try (var session = session(provider)) {
            session.submit("one");
            String initial = provider.requests().getFirst().context().systemPrompt();
            assertTrue(initial.contains("- **apricot**：Test apricot"));
            Path system = root.resolve(".cafecli/prompts/personalities/calm.md");
            Files.createDirectories(system.getParent());
            Files.writeString(system, "Changed template while the cached prompt lives.");
            assertTrue(session.dispatch("/skill list").orElseThrow().contains("apricot"));
            assertTrue(session.dispatch("/skill show apricot").orElseThrow().endsWith("Use apricot rules.\n"));
            session.dispatch("/skill reload");
            session.submit("two");
            assertEquals(initial, provider.requests().get(1).context().systemPrompt());
            assertEquals("⏸️ 已禁用 skill: apricot（已写入 ~/.cafecli/skills.json，下一轮 LLM 调用生效）",
                    session.dispatch("/skill off apricot").orElseThrow());
            session.submit("three");
            String changed = provider.requests().get(2).context().systemPrompt();
            assertFalse(changed.contains("- **apricot**"));
            assertNotEquals(initial, changed);
            Files.writeString(system, "Another template change.");
            session.dispatch("/skill off apricot");
            session.submit("four");
            assertEquals(changed, provider.requests().get(3).context().systemPrompt());
            session.dispatch("/skill on apricot");
            session.submit("five");
            assertTrue(provider.requests().get(4).context().systemPrompt().contains("- **apricot**：Test apricot"));
            skill("berry", "Use berry rules.\n");
            session.dispatch("/skill reload");
            session.submit("six");
            assertTrue(provider.requests().get(5).context().systemPrompt().contains("- **berry**：Test berry"));
        }
    }

    @Test void builtinExtractionFailureStillAllowsProjectSkills() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        Path cache = root.resolve("home/.cafecli/skills-cache");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "not a directory");
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(), FakeResponse.text("loaded")));
        try (var session = session(provider)) {
            session.submit("load it");
            assertEquals(ToolResult.Status.OK, session.log().stream()
                    .filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                    .findFirst().orElseThrow().status());
            assertTrue(provider.requests().getFirst().context().systemPrompt().contains("- **apricot**"));
        }
    }

    @Test void bodyTargetsTheRealUserAcrossVisibleRunStartEntriesAndSummaryNormalization() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("loaded"), FakeResponse.text("used")));
        Capability context = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.messageKind(Summary.KIND);
                agent.sourceProjectionStage(SummaryProjection::apply);
                agent.runStartWriter((input, token) -> List.of(new Steering("visible hint")));
                agent.iterationBoundaryWriter((log, token) -> log.stream()
                        .filter(UserEntry.class::isInstance).count() == 2
                        ? List.of(new Summary(0, 5, "previous turn")) : List.of());
            }
        };
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), context,
                        new SkillsCapability(config.skills()))).build()) {
            session.submit("load it");
            var firstRun = session.log();
            String historicalBytes = session.messageKinds().json().write(firstRun);
            session.submit("apply it");
            var messages = provider.requests().get(2).context().messages();
            assertEquals(3, messages.size());
            assertEquals(UserMessage.text("[已压缩的历史对话摘要]\nprevious turn"), messages.get(0));
            assertEquals(UserMessage.text("## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\napply it"),
                    messages.get(1));
            assertEquals(UserMessage.text("[用户转向 / 回合中提交]\nvisible hint"), messages.get(2));
            assertEquals(historicalBytes, session.messageKinds().json().write(session.log().subList(0, firstRun.size())));
            assertEquals(UserMessage.text("apply it"), session.log().stream()
                    .filter(UserEntry.class::isInstance)
                    .map(UserEntry.class::cast).toList().getLast().message());
        }
    }

    @Test void pendingBodiesBelongToOneAgentAndClearDropsThem() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var skills = new SkillsCapability(config.skills());
        var first = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("loaded"), FakeResponse.text("after clear")));
        var second = FakeProvider.scripted(List.of(FakeResponse.text("other agent")));
        try (var one = AgentSession.builder().provider(first).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), skills)).build();
             var two = AgentSession.builder().provider(second).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), skills)).build()) {
            one.submit("load it");
            two.submit("other input");
            assertEquals(List.of(UserMessage.text("other input")), second.requests().getFirst().context().messages());
            one.dispatch("/clear");
            one.submit("fresh input");
            assertEquals(List.of(UserMessage.text("fresh input")), first.requests().get(2).context().messages());
            assertTrue(first.requests().get(2).context().systemPrompt().contains("- **apricot**"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"show", "on", "off", "list extra", "reload extra"})
    void malformedSubcommandsKeepTheV1UnknownCommandBehavior(String arguments) {
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("unused"))))) {
            assertTrue(session.dispatch("/skill " + arguments).isEmpty(), "the registry rejects malformed legacy syntax before execution");
        }
    }

    @Test void reloadNoticesExternallyEditedEnableStateAndTheToolAgreesWithTheIndex() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("initial"), FakeResponse.builder()
                .toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("disabled"), FakeResponse.text("enabled")));
        try (var session = session(provider)) {
            session.submit("one");
            Path state = root.resolve("home/.cafecli/skills.json");
            Files.writeString(state, "{\"disabled\":[\"apricot\"]}");
            session.dispatch("/skill reload");
            session.submit("two");
            assertFalse(provider.requests().get(1).context().systemPrompt().contains("- **apricot**"));
            assertEquals(List.of(new TextBlock("Skill 'apricot' 已被禁用，可用 /skill on apricot 启用")), session.log().stream()
                    .filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow()
                    .message().content());
            Files.writeString(state, "{\"disabled\":[]}");
            session.dispatch("/skill reload");
            session.submit("three");
            assertTrue(provider.requests().get(3).context().systemPrompt().contains("- **apricot**：Test apricot"));
        }
    }

    @Test void aNewAgentsIndexCannotHideTheSkillSetChangeFromAnOlderCachedPrompt() throws Exception {
        skill("apricot", "Use apricot rules.\n");
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var skills = new SkillsCapability(config.skills());
        var oldProvider = FakeProvider.scripted(List.of(FakeResponse.text("old")));
        var newProvider = FakeProvider.scripted(List.of(FakeResponse.text("new")));
        try (var oldAgent = AgentSession.builder().provider(oldProvider).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), skills)).build()) {
            Files.writeString(config.skills().stateFile(), "{\"disabled\":[\"apricot\"]}");
            try (var newAgent = AgentSession.builder().provider(newProvider).model(MODEL).config(config)
                    .capabilities(List.of(new SessionCapability(), skills)).build()) {
                newAgent.submit("new agent");
                assertFalse(newProvider.requests().getFirst().context().systemPrompt().contains("- **apricot**"));
                oldAgent.dispatch("/skill reload");
                oldAgent.submit("old agent");
                assertFalse(oldProvider.requests().getFirst().context().systemPrompt().contains("- **apricot**"));
            }
        }
    }

    private void skill(String name, String body) throws Exception {
        Path file = root.resolve(".cafecli/skills/" + name + "/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: Test " + name + "\n---\n" + body);
    }

    private AgentSession session(FakeProvider provider) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        return AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new SessionCapability(), new SkillsCapability(
                        SkillsConfig.under(config.homes())))).build();
    }
}
