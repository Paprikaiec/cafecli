package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.RoutingProvider;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.config.TaskConfig;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.harness.context.Steering;
import com.cafecli.harness.context.Summary;
import com.cafecli.harness.context.SummaryProjection;
import com.cafecli.harness.hooks.HookContext;
import com.cafecli.harness.hooks.HooksCapability;
import com.cafecli.harness.lsp.LspCapability;
import com.cafecli.harness.lsp.LspDiagnostics;
import com.cafecli.harness.tasks.SubAgentsCapability;
import com.cafecli.harness.tasks.TaskNotification;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookTenant;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptMode;
import com.cafecli.prompt.PromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class BoundaryContextSessionTest {
    @TempDir Path root;
    static final Model MAIN = new Model("main","main","fake",128000,8192,Set.of());
    static final Model CHILD = new Model("child","child","fake",128000,8192,Set.of());

    @Test void aRealBackgroundCompletionBetweenSteeringAndPostWriteContextKeepsItsSlotAndTheNextRequestUsesLegacyOrder() throws Exception {
        var childEntered = new CountDownLatch(1);
        var releaseChild = new CountDownLatch(1);
        var childThread = new AtomicReference<Thread>();
        var parent = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("spawn","spawn_agent","{\"prompt\":\"work\",\"background\":true}").build(),
                FakeResponse.builder().toolCall("write","write_file","{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}").build(),
                FakeResponse.text("done")));
        var child = FakeProvider.responding(request -> {
            childThread.set(Thread.currentThread()); childEntered.countDown(); await(releaseChild);
            return FakeResponse.text("COMPLETED_CHILD");
        });
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.post_tool_use.enabled","true"),
                Map.of(),root.resolve("home"),root));
        var factory = new AgentFactory(new RoutingProvider(Map.of("main",parent,"child",child)),
                new PromptAssembler(new PromptRepository(root.resolve("user-prompts"),root.resolve("project-prompts"))),Duration.ofSeconds(90));
        var tasks = new SubAgentsCapability(factory,new AgentProfile(CHILD,PromptMode.AGENT,List.of(),10),
                new TaskConfig(true,120000,8,5,8,50));
        var hooks = new HooksCapability(config.hooks(),List.of(new HooksCapability.Registration(HookEvent.POST_TOOL_USE,
                new HookTenant() {
                    public String name() { return "post-write context"; }
                    public HookDecision evaluate(HookPayload payload) {
                        return "write_file".equals(payload.toolName()) ? HookDecision.cont().withAdditionalContext("HOOK_CONTEXT") : HookDecision.cont();
                    }
                })),null);
        try (var session = AgentSession.builder().interactive(true).config(config).factory(factory).model(MAIN)
                .capabilities(List.of(new WorkspaceCapability(root),hooks,new LspCapability(config.lsp()),tasks)).build()) {
            session.subscribe(event -> {
                if (event instanceof AgentEvent.ToolStart start && start.call().name().equals("write_file")) {
                    assertInstanceOf(SubmitResult.SteeringAccepted.class,session.submit("STEER_NEXT"));
                    try {
                        assertTrue(childEntered.await(5,TimeUnit.SECONDS)); releaseChild.countDown();
                        childThread.get().join(5000); assertFalse(childThread.get().isAlive(),"completion must have posted before the write proceeds");
                    } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }
            });
            try {
                session.submit("delegate and write"); session.awaitIdle();
                var source = session.log();
                assertEquals(List.of(Steering.class,TaskNotification.class,HookContext.class,LspDiagnostics.class),
                        source.subList(5,9).stream().map(Object::getClass).toList());
                var context = parent.requests().get(2).context().messages().subList(5,9);
                var text = context.stream().map(message -> ((TextBlock)((UserMessage)message).content().getFirst()).text()).toList();
                assertTrue(text.get(0).startsWith("[LSP 诊断注入]\n"),text.toString());
                assertEquals("<task-notification task_id=\"task_1\">\n[task_1 · done]\n\nCOMPLETED_CHILD\n</task-notification>",text.get(1));
                assertEquals("[Hook 上下文 / PostToolUse]\nHOOK_CONTEXT",text.get(2));
                assertEquals("[用户转向 / 回合中提交]\nSTEER_NEXT",text.get(3));
                assertEquals(3,parent.requests().size(),"the notification belongs to the first eligible request, without another run");
                assertEquals(source,session.messageKinds().json().read(session.messageKinds().json().write(source)));
            } finally { releaseChild.countDown(); }
        }
    }

    @Test void orderingDoesNotCrossRealUserOrAssistantBoundariesOrMoveOtherRegisteredKinds() {
        Capability contexts = new Capability() {
            public void attachAgent(AgentScope scope) {
                scope.messageKind(Marker.KIND); scope.messageKind(HookContext.KIND); scope.messageKind(LspDiagnostics.KIND);
                scope.runStartWriter((input,token) -> input.equals(UserMessage.text("first"))
                        ? List.of(new Steering("OLD_STEER"),new Marker("OLD_GAP"),new HookContext("OLD_HOOK"))
                        : List.of(new Steering("NEW_STEER"),new Marker("NEW_GAP"),new LspDiagnostics("NEW_LSP")));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("first answer"),FakeResponse.text("second answer")));
        try (var session = session(provider,contexts)) {
            session.submit("first"); String original = session.messageKinds().json().write(session.log());
            session.submit("second");
            var request = provider.requests().get(1).context().messages();
            assertEquals(UserMessage.text("first"),request.getFirst());
            assertEquals(UserMessage.text("[Hook 上下文 / PostToolUse]\nOLD_HOOK"),request.get(1));
            assertEquals(UserMessage.text("[marker]\nOLD_GAP"),request.get(2));
            assertEquals(UserMessage.text("[用户转向 / 回合中提交]\nOLD_STEER"),request.get(3));
            assertEquals("first answer",((com.cafecli.ai.message.AssistantMessage)request.get(4)).text());
            assertEquals(UserMessage.text("second"),request.get(5));
            assertEquals(UserMessage.text("NEW_LSP"),request.get(6));
            assertEquals(UserMessage.text("[marker]\nNEW_GAP"),request.get(7));
            assertEquals(UserMessage.text("[用户转向 / 回合中提交]\nNEW_STEER"),request.get(8));
            assertEquals(original,session.messageKinds().json().write(session.log().subList(0,5)));
        }
    }

    @Test void aNormalizedSummarySeparatesTheSurvivingContextCohorts() {
        Capability contexts = new Capability() {
            public void attachAgent(AgentScope scope) {
                scope.messageKind(Marker.KIND); scope.messageKind(HookContext.KIND); scope.messageKind(Summary.KIND);
                scope.sourceProjectionStage(SummaryProjection::apply);
                scope.runStartWriter((input,token) -> List.of(new Steering("LEFT_STEER"),new Marker("covered"),
                        new HookContext("RIGHT_HOOK"),new Summary(2,3,"covered middle")));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("done")));
        try (var session = session(provider,contexts)) {
            session.submit("run");
            assertEquals(List.of(UserMessage.text("run"),UserMessage.text("[用户转向 / 回合中提交]\nLEFT_STEER"),
                    UserMessage.text("[已压缩的历史对话摘要]\ncovered middle"),
                    UserMessage.text("[Hook 上下文 / PostToolUse]\nRIGHT_HOOK")),provider.requests().getFirst().context().messages());
            assertInstanceOf(Marker.class,session.log().get(2),"the covered source entry remains unchanged");
        }
    }

    @Test void contextsOnOppositeSidesOfAnAssistantAndItsToolResultRemainInSeparateCohorts() {
        Capability contexts = new Capability() {
            public void attachAgent(AgentScope scope) {
                scope.messageKind(Marker.KIND); scope.messageKind(HookContext.KIND); scope.messageKind(LspDiagnostics.KIND);
                scope.runStartWriter((input,token) -> List.of(new Steering("LEFT_STEER"),new Marker("LEFT_GAP"),new HookContext("LEFT_HOOK")));
                scope.iterationEnd((message,results) -> message.toolCalls().isEmpty() ? com.cafecli.core.IterationDecision.end()
                        : com.cafecli.core.IterationDecision.proceed(new Steering("RIGHT_STEER"),new Marker("RIGHT_GAP"),new LspDiagnostics("RIGHT_LSP")));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("missing","missing","{}").build(),FakeResponse.text("done")));
        try (var session = session(provider,contexts)) {
            session.submit("run");
            var request = provider.requests().get(1).context().messages();
            assertEquals(UserMessage.text("[Hook 上下文 / PostToolUse]\nLEFT_HOOK"),request.get(1));
            assertEquals(UserMessage.text("[marker]\nLEFT_GAP"),request.get(2));
            assertEquals(UserMessage.text("[用户转向 / 回合中提交]\nLEFT_STEER"),request.get(3));
            assertEquals("missing",assertInstanceOf(com.cafecli.ai.message.AssistantMessage.class,request.get(4)).toolCalls().getFirst().id());
            assertEquals("missing",assertInstanceOf(com.cafecli.ai.message.ToolResultMessage.class,request.get(5)).toolCallId());
            assertEquals(UserMessage.text("RIGHT_LSP"),request.get(6));
            assertEquals(UserMessage.text("[marker]\nRIGHT_GAP"),request.get(7));
            assertEquals(UserMessage.text("[用户转向 / 回合中提交]\nRIGHT_STEER"),request.get(8));
            assertInstanceOf(Steering.class,session.log().get(1));
            assertInstanceOf(Steering.class,session.log().get(6));
        }
    }

    private AgentSession session(FakeProvider provider,Capability contexts) {
        return AgentSession.builder().provider(provider).model(MAIN)
                .config(ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root)))
                .capabilities(List.of(contexts)).build();
    }
    public record Marker(String text) implements CustomEntry {
        static final MessageKind<Marker> KIND = MessageKind.tagged("marker",Marker.class,"[marker]",Marker::text);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
