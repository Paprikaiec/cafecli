package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.UserEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.image.ImagePart;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImagesSessionTest {
    private static final Model MODEL = new Model("fake", "fake", "fake", 128_000, 8_192, Set.of());
    @TempDir Path root;

    @Test void userImageReferenceReachesTheRequestAndSourceLogWithoutACapabilityGate() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("seen")));
        try (var session = session(provider)) {
            session.submit("Analyze @image:shot.png");
            var user = assertInstanceOf(UserMessage.class, provider.requests().getFirst().context().messages().getFirst());
            assertEquals(new ImageBlock("AQID", "image/png"), user.content().getLast());
            String text = assertInstanceOf(TextBlock.class, user.content().getFirst()).text();
            assertTrue(text.startsWith("Analyze\n\n[图片已作为图片附件附加。"));
            assertTrue(text.contains("请直接观察本轮图片内容"));
            assertTrue(text.contains("不要调用 MCP、文件系统或浏览器工具重新读取 Image source"));
            var source = assertInstanceOf(UserEntry.class, session.log().getFirst());
            assertEquals(new UserMessage(List.of(new TextBlock("Analyze @image:shot.png"), new ImageBlock("AQID", "image/png"))), source.message());
            assertEquals(user, source.presentation());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void toolImagesFollowAllToolResultsInSeparateSourceOrderedUserMessages() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("first", "camera", "{}")
                .toolCall("second", "diagram", "{}").build(), FakeResponse.text("seen")));
        Capability cameras = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("camera", "fixture", new ObjectMapper().createObjectNode(),
                        args -> new ToolOutput("camera text", List.of(new ImagePart("AQID", "image/png"))))));
                agent.tool(new LeafToolAdapter(new Tool("diagram", "fixture", new ObjectMapper().createObjectNode(),
                        args -> new ToolOutput("diagram text", List.of(new ImagePart("BAUG", "image/jpeg"))))));
            }
        };
        try (var session = session(provider, cameras)) {
            session.submit("Take pictures");
            var messages = provider.requests().get(1).context().messages();
            assertEquals(6, messages.size());
            assertEquals(new ToolResultMessage("first", "camera", List.of(new TextBlock("camera text")), false), messages.get(2));
            assertEquals(new ToolResultMessage("second", "diagram", List.of(new TextBlock("diagram text")), false), messages.get(3));
            assertEquals(new UserMessage(List.of(new TextBlock("工具 camera 返回了图片内容，请结合上面的工具文本结果分析。"),
                    new ImageBlock("AQID", "image/png"))), messages.get(4));
            assertEquals(new UserMessage(List.of(new TextBlock("工具 diagram 返回了图片内容，请结合上面的工具文本结果分析。"),
                    new ImageBlock("BAUG", "image/jpeg"))), messages.get(5));
            var results = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
            assertEquals(new ImageBlock("AQID", "image/png"), results.getFirst().message().content().getLast());
            assertEquals(new ImageBlock("BAUG", "image/jpeg"), results.getLast().message().content().getLast());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void theNextRunOmitsEveryEarlierToolImageAndKeepsCurrentImagesAndSourcePayloads() {
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("first", "camera", "{}").build(), FakeResponse.text("first seen"),
                FakeResponse.builder().toolCall("second", "camera", "{}").build(), FakeResponse.text("second seen"),
                FakeResponse.text("remembered")));
        var number = new java.util.concurrent.atomic.AtomicInteger();
        Capability camera = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("camera", "fixture", new ObjectMapper().createObjectNode(),
                        args -> new ToolOutput("camera text", List.of(new ImagePart(
                                number.incrementAndGet() == 1 ? "AQID" : "BAUG", "image/png"))))));
            }
        };
        try (var session = session(provider, camera)) {
            session.submit("first picture");
            var original = session.log();
            String originalJson = session.messageKinds().json().write(original);
            session.submit("second picture");
            var second = provider.requests().get(3).context().messages();
            assertEquals(new UserMessage(List.of(new TextBlock("工具 camera 返回了图片内容，请结合上面的工具文本结果分析。"),
                    new TextBlock("[历史图片附件已省略 1 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"))), second.get(3));
            assertEquals(new ImageBlock("BAUG", "image/png"),
                    assertInstanceOf(UserMessage.class, second.getLast()).content().getLast());
            session.submit("remember both");
            assertTrue(provider.requests().get(4).context().messages().stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .flatMap(message -> message.content().stream()).noneMatch(ImageBlock.class::isInstance));
            assertEquals(originalJson, session.messageKinds().json().write(session.log().subList(0, original.size())));
            assertEquals(List.of(new ImageBlock("AQID", "image/png"), new ImageBlock("BAUG", "image/png")),
                    session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                            .flatMap(result -> result.message().content().stream()).filter(ImageBlock.class::isInstance).toList());
        }
    }

    @Test void anImageOnlyInputIsParsedAfterItsSkillPrefixWithoutInventingADefaultQuestion() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        Path skill = root.resolve(".cafecli/skills/apricot/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: apricot\ndescription: Test\n---\nUse apricot rules.\n");
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("loaded"), FakeResponse.text("seen")));
        try (var session = session(provider, new com.cafecli.harness.skills.SkillsCapability(config.skills()))) {
            session.submit("load it");
            session.submit("@image:shot.png");
            var user = assertInstanceOf(UserMessage.class, provider.requests().get(2).context().messages().getLast());
            String text = assertInstanceOf(TextBlock.class, user.content().getFirst()).text();
            assertTrue(text.startsWith("## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\n\n[图片已作为图片附件附加。"), text);
            assertFalse(text.contains("请分析以下图片。"), text);
            assertEquals(new ImageBlock("AQID", "image/png"), user.content().getLast());
            var source = session.log().stream().filter(UserEntry.class::isInstance).map(UserEntry.class::cast).toList().getLast();
            assertEquals(new TextBlock("@image:shot.png"), source.message().content().getFirst());
            assertEquals(1, source.preparedPrefixCount());
            assertEquals("## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\n", session.log().stream()
                    .filter(com.cafecli.harness.skills.SkillBody.class::isInstance).map(com.cafecli.harness.skills.SkillBody.class::cast).findFirst().orElseThrow().prefix());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void referencesInTheSkillPrefixAreParsedOnceWithoutBecomingUserOriginAttachments() throws Exception {
        Files.write(root.resolve("prefix.png"), new byte[]{1, 2, 3});
        Files.write(root.resolve("user.png"), new byte[]{4, 5, 6});
        Path skill = root.resolve(".cafecli/skills/apricot/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: apricot\ndescription: Test\n---\nObserve @image:prefix.png\n");
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("loaded"), FakeResponse.builder().toolCall("erase", "erase_images", "{}").build(),
                FakeResponse.text("seen"), FakeResponse.text("remembered")));
        Capability erase = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("erase_images", "fixture", new ObjectMapper().createObjectNode(), args -> {
                    try {
                        Files.delete(root.resolve("prefix.png"));
                        Files.delete(root.resolve("user.png"));
                    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    return ToolOutput.text("removed");
                })));
            }
        };
        try (var session = session(provider, new com.cafecli.harness.skills.SkillsCapability(config.skills()), erase)) {
            session.submit("load it");
            session.submit("User @image:user.png");
            var source = session.log().stream().filter(UserEntry.class::isInstance).map(UserEntry.class::cast).toList().getLast();
            assertEquals(List.of(new TextBlock("User @image:user.png"), new ImageBlock("BAUG", "image/png")), source.message().content());
            var first = provider.requests().get(2).context().messages().stream().filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast).toList().getLast();
            assertEquals(List.of(new ImageBlock("AQID", "image/png"), new ImageBlock("BAUG", "image/png")),
                    first.content().stream().filter(ImageBlock.class::isInstance).toList());
            String text = assertInstanceOf(TextBlock.class, first.content().getFirst()).text();
            assertTrue(text.startsWith("## 已加载 Skill：apricot\nObserve\n\n---\n\n用户输入：\nUser\n\n[图片已作为图片附件附加。"), text);
            assertEquals(1, text.split("图片已作为图片附件附加", -1).length - 1);
            assertEquals(first, provider.requests().get(3).context().messages().get(4));
            String original = session.messageKinds().json().write(session.log());
            int size = session.log().size();
            session.submit("remember both");
            var historical = assertInstanceOf(UserMessage.class, provider.requests().get(4).context().messages().get(4));
            assertEquals(new TextBlock("[历史图片附件已省略 2 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"), historical.content().getLast());
            assertEquals(original, session.messageKinds().json().write(session.log().subList(0, size)));
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void historicalUserImagesAreOmittedWhileTheLatestRunAndSourceRemainIntact() throws Exception {
        Files.write(root.resolve("old.png"), new byte[]{1, 2, 3});
        Files.write(root.resolve("latest.png"), new byte[]{4, 5, 6});
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("old seen"), FakeResponse.text("middle"), FakeResponse.text("latest seen")));
        try (var session = session(provider)) {
            session.submit("Old @image:old.png @image:old.png");
            String first = session.messageKinds().json().write(session.log());
            int firstSize = session.log().size();
            session.submit("middle run");
            session.submit("Latest @image:latest.png");
            var messages = provider.requests().get(2).context().messages();
            assertEquals(new TextBlock("[历史图片附件已省略 2 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"),
                    assertInstanceOf(UserMessage.class, messages.getFirst()).content().getLast());
            assertEquals(new ImageBlock("BAUG", "image/png"), assertInstanceOf(UserMessage.class, messages.getLast()).content().getLast());
            assertEquals(first, session.messageKinds().json().write(session.log().subList(0, firstSize)));
            assertEquals(3, session.log().stream().filter(UserEntry.class::isInstance).map(UserEntry.class::cast)
                    .flatMap(user -> user.message().content().stream()).filter(ImageBlock.class::isInstance).count());
        }
    }

    @Test void summarySourceRangesNormalizeBeforeImageViewsAndRecallAndSteeringCannotStealThePrefix() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("camera", "camera", "{}").build(),
                FakeResponse.text("old seen"), FakeResponse.text("latest seen")));
        Capability prefixes = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.messageKind(com.cafecli.harness.skills.SkillBody.KIND);
                agent.messageKind(com.cafecli.harness.memory.MemoryRecall.KIND);
                agent.inputPreparation((input, token) -> new com.cafecli.core.RunInput(input.source(), null,
                        List.of(new com.cafecli.harness.skills.SkillBody("Skill rules\n")), 0));
                agent.runStartWriter((input, token) -> List.of(new com.cafecli.harness.memory.MemoryRecall("memory"),
                        new com.cafecli.harness.context.Steering("steering")));
                agent.tool(new LeafToolAdapter(new Tool("camera", "fixture", new ObjectMapper().createObjectNode(),
                        args -> new ToolOutput("camera text", List.of(new ImagePart("BAUG", "image/png"))))));
            }
        };
        Capability summaries = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.messageKind(com.cafecli.harness.context.Summary.KIND);
                agent.sourceProjectionStage(com.cafecli.harness.context.SummaryProjection::apply);
                agent.iterationBoundaryWriter((log, token) -> log.stream().filter(UserEntry.class::isInstance).count() == 2
                        ? List.of(new com.cafecli.harness.context.Summary(0, 7, "earlier picture")) : List.of());
            }
        };
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(prefixes, new com.cafecli.harness.images.ImagesCapability(root), summaries)).build()) {
            session.submit("Old @image:shot.png");
            var original = session.log();
            String bytes = session.messageKinds().json().write(original);
            session.submit("Current @image:shot.png");
            var messages = provider.requests().get(2).context().messages();
            assertEquals(4, messages.size());
            assertEquals(UserMessage.text("[已压缩的历史对话摘要]\nearlier picture"), messages.getFirst());
            var current = assertInstanceOf(UserMessage.class, messages.get(1));
            assertTrue(assertInstanceOf(TextBlock.class, current.content().getFirst()).text().startsWith("Skill rules\nCurrent\n\n[图片已作为图片附件附加。"));
            assertEquals(new ImageBlock("AQID", "image/png"), current.content().getLast());
            assertEquals(UserMessage.text("memory"), messages.get(2));
            assertEquals(UserMessage.text("[用户转向 / 回合中提交]\nsteering"), messages.get(3));
            assertEquals(bytes, session.messageKinds().json().write(session.log().subList(0, original.size())));
        }
    }

    @Test void cachedPrefixesAreConsumedOnlyForTheirOwnUserAndLaterPrefixesStillApply() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two")));
        Capability prefixes = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.messageKind(com.cafecli.harness.skills.SkillBody.KIND);
                agent.inputPreparation((input, token) -> new com.cafecli.core.RunInput(input.source(), null,
                        List.of(new com.cafecli.harness.skills.SkillBody("early\n")), 0));
                agent.runStartWriter((input, token) -> List.of(new com.cafecli.harness.skills.SkillBody("late\n")));
            }
        };
        try (var session = session(provider, prefixes)) {
            session.submit("Look @image:shot.png");
            var first = assertInstanceOf(UserMessage.class, provider.requests().getFirst().context().messages().getFirst());
            assertTrue(assertInstanceOf(TextBlock.class, first.content().getFirst()).text().startsWith("late\nearly\nLook\n\n"));
            session.submit("plain");
            assertEquals(UserMessage.text("late\nearly\nplain"), provider.requests().get(1).context().messages().getLast());
            var source = session.log().stream().filter(UserEntry.class::isInstance).map(UserEntry.class::cast).toList().getLast();
            assertNull(source.presentation(), "ordinary prefix conversion needs no cached presentation");
            assertEquals(0, source.preparedPrefixCount());
        }
    }

    @Test void cancellationAndClearDoNotReusePreparedInputAndOldCancelledPayloadsRemainHistorical() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("latest seen"), FakeResponse.text("fresh")));
        try (var session = session(provider)) {
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            session.subscribe(event -> {
                if (event instanceof com.cafecli.core.AgentEvent.RunStart && first.getAndSet(false)) session.cancel();
            });
            var cancelled = assertInstanceOf(SubmitResult.Completed.class, session.submit("Cancelled @image:shot.png"));
            assertInstanceOf(com.cafecli.core.RunOutcome.Cancelled.class, cancelled.outcome());
            assertTrue(provider.requests().isEmpty());
            Files.write(root.resolve("shot.png"), new byte[]{4, 5, 6});
            session.submit("Latest @image:shot.png");
            var messages = provider.requests().getFirst().context().messages();
            assertEquals(new TextBlock("[历史图片附件已省略 1 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"),
                    assertInstanceOf(UserMessage.class, messages.getFirst()).content().getLast());
            assertEquals(new ImageBlock("BAUG", "image/png"), assertInstanceOf(UserMessage.class, messages.getLast()).content().getLast());
            assertEquals(new ImageBlock("AQID", "image/png"), assertInstanceOf(UserEntry.class, session.log().getFirst()).message().content().getLast());
            session.dispatch("/clear");
            Files.delete(root.resolve("shot.png"));
            session.submit("fresh");
            assertEquals(List.of(UserMessage.text("fresh")), provider.requests().get(1).context().messages());
            assertEquals(2, session.log().size());
            assertNull(assertInstanceOf(UserEntry.class, session.log().getFirst()).presentation());
        }
    }

    @Test void ordinarySubmitPreparationRewritesReferencesBeforeImageLoadingAndCanBlockWithoutLogging() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("seen")));
        var submitted = new java.util.ArrayList<String>();
        Capability preparation = new Capability() {
            @Override public void attachSession(SessionRegistrar session) {
                session.onSubmit((input, token) -> {
                    submitted.add(input);
                    return input.equals("blocked") ? Submission.block("no input") : Submission.proceed("Expanded @image:shot.png");
                });
            }
        };
        try (var session = session(provider, preparation)) {
            assertInstanceOf(SubmitResult.Blocked.class, session.submit("blocked"));
            assertTrue(session.log().isEmpty());
            assertTrue(provider.requests().isEmpty());
            session.submit("raw @image:missing.png");
            assertEquals(List.of("blocked", "raw @image:missing.png"), submitted);
            var user = assertInstanceOf(UserMessage.class, provider.requests().getFirst().context().messages().getFirst());
            assertTrue(assertInstanceOf(TextBlock.class, user.content().getFirst()).text().startsWith("Expanded\n\n"));
            assertEquals(new ImageBlock("AQID", "image/png"), user.content().getLast());
            assertEquals(new TextBlock("Expanded @image:shot.png"), assertInstanceOf(UserEntry.class, session.log().getFirst()).message().content().getFirst());
        }
    }

    private AgentSession session(FakeProvider provider, Capability... additional) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        return AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(java.util.stream.Stream.concat(java.util.stream.Stream.concat(java.util.stream.Stream.of(new SessionCapability()),
                        java.util.Arrays.stream(additional)), java.util.stream.Stream.of(new com.cafecli.harness.images.ImagesCapability(root))).toList()).build();
    }
}
