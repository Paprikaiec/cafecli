package com.cafecli.render.inline;

import com.cafecli.ai.*;
import com.cafecli.ai.fake.*;
import com.cafecli.ai.message.*;
import com.cafecli.core.AgentEvent;
import com.cafecli.harness.*;
import com.cafecli.harness.config.*;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.tasks.SubAgentsCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.prompt.*;
import com.cafecli.ui.InteractiveApprover;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cafecli.render.inline.V2InteractiveReplTest.await;

/** v1 interactive contracts exercised through the v2 session and actual resident reader. */
@Timeout(30)
class V2InteractiveContractsTest {
    @TempDir Path root;
    private static final Model MAIN = new Model("main","main","fake",128000,8192,Set.of());
    private static final Model CHILD = new Model("child","child","fake",128000,8192,Set.of());

    @ParameterizedTest @ValueSource(strings={"\033\r","\033[13;2u","\033[27;2;13~"})
    void modifiedEnterInsertsANewlineAndPlainEnterStillSubmits(String newline) throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("first done"),FakeResponse.text("second done")));
        var approvals = new InteractiveApprover();
        try (var session = session(provider,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            rig.type("first"+newline+"second");
            await(() -> rig.reader.getBuffer().toString().equals("first\nsecond"));
            assertTrue(provider.requests().isEmpty(),"modified Enter cannot submit");
            rig.type("\r"); await(() -> provider.requests().size()==1); session.awaitIdle();
            assertEquals(UserMessage.text("first\nsecond"),provider.requests().getFirst().context().messages().getLast());
            rig.type("third\r"); await(() -> provider.requests().size()==2); session.awaitIdle();
            assertEquals(UserMessage.text("third"),provider.requests().getLast().context().messages().getLast()); rig.exit();
        }
    }

    @Test void emptyIdleEscIsHarmlessAndTheExitConfirmationRearmsAfterItsWindow() throws Exception {
        var approvals = new InteractiveApprover();
        try (var session = session(FakeProvider.scripted(List.of()),new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            rig.type("\033"); Thread.sleep(150); assertTrue(rig.thread.isAlive()); assertEquals(0,rig.reader.getBuffer().length());
            rig.terminal.raise(Terminal.Signal.INT); rig.awaitScreen("再按一次 Ctrl+C 退出");
            Thread.sleep(2200);
            rig.terminal.raise(Terminal.Signal.INT);
            await(() -> {
                rig.pump.drain(); var screen = new com.cafecli.render.inline.replay.TerminalScreenModel(100,40);
                screen.feed(rig.sink.toByteArray());
                return screen.document().stream().filter(line -> line.contains("再按一次 Ctrl+C 退出")).count()==2;
            });
            assertTrue(rig.thread.isAlive(),"a press after the confirmation window must rearm");
            rig.terminal.raise(Terminal.Signal.INT); rig.thread.join(5000); assertFalse(rig.thread.isAlive()); assertNull(rig.failure.get());
        }
    }

    @Test void ctrlYChangesTheActualApprovalModeWhileTheModelIsRunning() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var provider = FakeProvider.responding(request -> { entered.countDown(); waitFor(release); return FakeResponse.text("done"); });
        var approvals = new InteractiveApprover();
        try (var session = session(provider,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            try {
                rig.type("run\r"); assertTrue(entered.await(5,TimeUnit.SECONDS));
                rig.type("draft\031"); await(session::approvalEnabled);
                assertTrue(session.isBusy()); assertTrue(session.status().approvalEnabled());
                assertEquals("draft",rig.reader.getBuffer().toString());
                rig.type("\031"); await(() -> !session.approvalEnabled()); assertFalse(session.status().approvalEnabled());
            } finally { release.countDown(); }
            session.awaitIdle(); rig.type("\025"); await(() -> rig.reader.getBuffer().length()==0); rig.exit();
        }
    }

    @Test void cancellingDiscardsSteeringWhichNeverReachedAnIterationAndTheSessionStillServesNextInput() throws Exception {
        var contexts = new CopyOnWriteArrayList<Context>(); var entered = new CountDownLatch(1);
        var next = FakeProvider.scripted(List.of(FakeResponse.text("next answer")));
        Provider provider = (model,context,options) -> {
            contexts.add(context);
            if (contexts.size()>1) return next.stream(model,context,options);
            return AssistantStream.of(model,options.cancellation(),out -> {
                entered.countDown(); var cancelled = new CountDownLatch(1);
                try (var ignored = options.cancellation().onCancel(cancelled::countDown)) { waitFor(cancelled); }
                return false;
            });
        };
        var approvals = new InteractiveApprover();
        try (var session = session(provider,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            rig.type("long run\r"); assertTrue(entered.await(5,TimeUnit.SECONDS));
            rig.type("unlanded steering\r"); rig.awaitScreen("unlanded steering");
            assertEquals(1,contexts.size(),"steering does not open another run");
            rig.type("\033"); session.awaitIdle();
            rig.type("next input\r"); await(() -> contexts.size()==2); session.awaitIdle();
            assertFalse(contexts.getLast().messages().toString().contains("unlanded steering"));
            assertEquals(UserMessage.text("next input"),contexts.getLast().messages().getLast()); rig.exit();
        }
    }

    @Test void inputBoxYesNoAndAllHaveDistinctEffectsAndAllRemembersOnlyTheSameTool() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("one","write_file","{\"path\":\"yes.txt\",\"content\":\"yes\"}")
                .toolCall("two","write_file","{\"path\":\"no.txt\",\"content\":\"no\"}")
                .toolCall("three","write_file","{\"path\":\"all.txt\",\"content\":\"all\"}").build(),
                FakeResponse.builder().toolCall("four","write_file","{\"path\":\"remembered.txt\",\"content\":\"remembered\"}").build(),FakeResponse.text("done")));
        var approvals = new InteractiveApprover();
        try (var session = session(provider,new PermissionCapability(approvals,true));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            rig.type("write files\r"); rig.awaitScreen("path: \"yes.txt\""); rig.type("y\r");
            rig.awaitScreen("path: \"no.txt\""); rig.type("n\r");
            rig.awaitScreen("path: \"all.txt\""); rig.type("a\r");
            session.awaitIdle();
            assertEquals("yes",Files.readString(root.resolve("yes.txt"))); assertFalse(Files.exists(root.resolve("no.txt")));
            assertEquals("all",Files.readString(root.resolve("all.txt"))); assertEquals("remembered",Files.readString(root.resolve("remembered.txt")));
            assertEquals(3,provider.requests().size(),"responses are approval answers, not new human turns"); rig.exit();
        }
    }

    @ParameterizedTest @ValueSource(strings={"escape","ctrlC"})
    void cancellingFromTheInputBoxStopsAttachedTasksAndPreservesDetachedTasks(String key) throws Exception {
        var attachedEntered = new CountDownLatch(1); var detachedEntered = new CountDownLatch(1);
        var attachedCancelled = new CountDownLatch(1); var detachedCancelled = new CountDownLatch(1);
        Provider child = (model,context,options) -> AssistantStream.of(model,options.cancellation(),out -> {
            boolean attached = context.messages().getFirst().toString().contains("attached");
            CountDownLatch cancelled = attached ? attachedCancelled : detachedCancelled;
            try (var ignored = options.cancellation().onCancel(cancelled::countDown)) {
                (attached ? attachedEntered : detachedEntered).countDown(); waitFor(cancelled);
            }
            return false;
        });
        var parentCalls = new AtomicInteger();
        var parent = FakeProvider.responding(request -> parentCalls.getAndIncrement()==0 ? FakeResponse.builder()
                .toolCall("detached","spawn_agent","{\"prompt\":\"background\",\"background\":true}")
                .toolCall("attached","spawn_agent","{\"prompt\":\"attached\"}").build() : FakeResponse.text("notification consumed"));
        var approvals = new InteractiveApprover();
        try (var session = taskSession(parent,child,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            var firstOutcome = new AtomicReference<com.cafecli.core.RunOutcome>();
            session.subscribeCompletion(completion -> {
                if (completion instanceof SessionCompletion.Completed result) firstOutcome.compareAndSet(null,result.outcome());
            });
            rig.type("delegate\r"); assertTrue(attachedEntered.await(5,TimeUnit.SECONDS),"attached provider must start"); assertTrue(detachedEntered.await(5,TimeUnit.SECONDS));
            if (key.equals("escape")) rig.type("\033"); else rig.terminal.raise(Terminal.Signal.INT);
            session.awaitIdle(); assertTrue(attachedCancelled.await(5,TimeUnit.SECONDS));
            assertInstanceOf(com.cafecli.core.RunOutcome.Cancelled.class,firstOutcome.get());
            assertEquals(1,detachedCancelled.getCount(),"Detached work must survive a parent cancel key");
            String listing = session.dispatch("/task list").orElseThrow();
            String detachedId = listing.lines().filter(line -> line.contains("[运行中]")).findFirst().orElseThrow().trim().split(" +")[0];
            session.dispatch("/task cancel "+detachedId); assertTrue(detachedCancelled.await(5,TimeUnit.SECONDS));
            await(() -> parent.requests().size()>=2); session.awaitIdle(); rig.exit();
        }
    }

    @Test void aDetachedChildApprovalUsesTheSameInputBoxAndShowsItsTaskAttribution() throws Exception {
        var calls = new AtomicInteger();
        var parent = FakeProvider.responding(request -> calls.getAndIncrement()==0 ? FakeResponse.builder()
                .toolCall("spawn","spawn_agent","{\"prompt\":\"write child file\",\"background\":true}").build() : FakeResponse.text("parent answer"));
        var child = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("write","write_file","{\"path\":\"child.txt\",\"content\":\"child approved\"}").build(),FakeResponse.text("child done")));
        var approvals = new InteractiveApprover();
        try (var session = taskSession(parent,child,new PermissionCapability(approvals,true));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            var childDone = new CountDownLatch(1);
            session.subscribe(event -> { if (event instanceof AgentEvent.RunEnd && event.agentId().equals("task_1")) childDone.countDown(); });
            rig.type("delegate\r"); rig.awaitScreen("path: \"child.txt\""); rig.awaitScreen("来源: task_1");
            session.awaitIdle();
            rig.type("\033"); Thread.sleep(150);
            assertFalse(Files.exists(root.resolve("child.txt")),"idle ESC must preserve the Detached child's pending approval");
            rig.type("y\r"); assertTrue(childDone.await(5,TimeUnit.SECONDS));
            session.awaitIdle(); assertEquals("child approved",Files.readString(root.resolve("child.txt")));
            assertEquals(1,session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count(),"child approval answer is not a MAIN source input"); rig.exit();
        }
    }

    @Test void aDeadInputStreamEndsABusyReplAndCancelsItsRequest() throws Exception {
        var entered = new CountDownLatch(1); var cancelled = new CountDownLatch(1);
        Provider provider = (model,context,options) -> AssistantStream.of(model,options.cancellation(),out -> {
            try (var ignored = options.cancellation().onCancel(cancelled::countDown)) { entered.countDown(); waitFor(cancelled); }
            return false;
        });
        var approvals = new InteractiveApprover();
        try (var session = session(provider,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            rig.type("run\r"); assertTrue(entered.await(5,TimeUnit.SECONDS));
            rig.keys.close(); rig.thread.join(5000);
            assertFalse(rig.thread.isAlive(),"a genuinely closed stream must return, unlike mid-run Ctrl+D");
            assertEquals(0,cancelled.getCount()); assertNull(rig.failure.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void busyInputIsEchoedWithoutAnotherRunAndReachesTheNextBoundaryOrANaturalFollowUp(boolean toolBoundary) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var provider = FakeProvider.responding(request -> {
            if (calls.getAndIncrement()==0) {
                entered.countDown(); waitFor(release);
                return toolBoundary ? FakeResponse.builder().toolCall("tick","unknown","{}").build() : FakeResponse.text("first answer");
            }
            return FakeResponse.text("steered answer");
        });
        var approvals = new InteractiveApprover(); var runs = new AtomicInteger();
        try (var session = session(provider,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            session.subscribe(event -> { if (event instanceof AgentEvent.RunStart) runs.incrementAndGet(); });
            try {
                rig.type("long run\r"); assertTrue(entered.await(5,TimeUnit.SECONDS));
                rig.type("steer me\r"); rig.awaitScreen("steer me");
                assertEquals(1,provider.requests().size()); assertEquals(1,runs.get(),"a busy input must not open another run immediately");
            } finally { release.countDown(); }
            await(() -> provider.requests().size()==2); session.awaitIdle();
            assertTrue(provider.requests().getLast().context().messages().toString().contains("steer me"));
            assertEquals(toolBoundary ? 1 : 2,runs.get(),"natural-final-boundary steering becomes a follow-up only when no iteration can consume it");
            rig.exit();
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationStopsWorkEvenWithoutPresentationAndOtherwiseFeedbackAppearsBeforeCleanupEnds(boolean rendererAvailable) throws Exception {
        var entered = new CountDownLatch(1); var cancelled = new CountDownLatch(1); var releaseCleanup = new CountDownLatch(1);
        Provider provider = (model,context,options) -> AssistantStream.of(model,options.cancellation(),out -> {
            try (var ignored = options.cancellation().onCancel(cancelled::countDown)) {
                entered.countDown(); waitFor(cancelled); waitFor(releaseCleanup);
            }
            return false;
        });
        var approvals = new InteractiveApprover();
        try (var session = session(provider,new PermissionCapability(approvals,false));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            try {
                rig.type("run\r"); assertTrue(entered.await(5,TimeUnit.SECONDS));
                if (!rendererAvailable) rig.renderer.close();
                assertDoesNotThrow(() -> rig.terminal.raise(Terminal.Signal.INT));
                assertTrue(cancelled.await(5,TimeUnit.SECONDS),"presentation availability cannot prevent cancellation");
                if (rendererAvailable) rig.awaitScreen("已请求取消");
                assertTrue(session.isBusy(),"feedback is observable before provider cleanup releases run admission");
            } finally { releaseCleanup.countDown(); }
            session.awaitIdle(); rig.exit();
        }
    }

    private AgentSession session(Provider provider,Capability permission) {
        var config = config();
        return AgentSession.builder().config(config).provider(provider).model(MAIN).interactive(true)
                .capabilities(List.of(new WorkspaceCapability(root,config.workspace()),permission)).build();
    }
    private AgentSession taskSession(Provider parent,Provider child,PermissionCapability permission) {
        var config = config();
        var factory = new AgentFactory(new RoutingProvider(Map.of("main",parent,"child",child)),
                new PromptAssembler(new PromptRepository(root.resolve("user-prompts"),root.resolve("project-prompts"))),Duration.ofSeconds(30));
        var children = List.<Capability>of(new WorkspaceCapability(root,config.workspace()),permission);
        var tasks = new SubAgentsCapability(factory,new AgentProfile(CHILD,PromptMode.AGENT,children,10),
                new TaskConfig(true,120000,8,5,8,50));
        return AgentSession.builder().config(config).factory(factory).model(MAIN).interactive(true)
                .capabilities(List.of(children.getFirst(),permission,tasks)).build();
    }
    private HarnessConfig config() { return ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root)); }
    private static void waitFor(CountDownLatch latch) {
        try { assertTrue(latch.await(10,TimeUnit.SECONDS),"provider boundary did not release"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
