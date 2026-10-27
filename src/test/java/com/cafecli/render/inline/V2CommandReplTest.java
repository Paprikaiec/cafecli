package com.cafecli.render.inline;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.*;
import com.cafecli.harness.*;
import com.cafecli.harness.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class V2CommandReplTest {
    @TempDir Path root;
    @Test @Timeout(20) void capabilityCommandIsEchoedAndExecutedByTheResidentReaderWithoutAModelRequest() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        Capability custom = new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/greet", "greet", (session, args) -> "Hello " + args + "!"));
            }
        };
        try (var session = session(provider, List.of(new SessionCapability(), custom));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,null)) {
            rig.type("  /greet friend  \r");
            rig.awaitScreen("Hello friend!");
            rig.awaitScreen("/greet friend");
            assertTrue(provider.requests().isEmpty());
            assertTrue(session.log().isEmpty());
            rig.exit();
        }
    }
    @Test @Timeout(30) void commandOriginToolsCanBeApprovedAndCancelledThroughTheSameResidentInputBox() throws Exception {
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        var approver=new com.cafecli.ui.InteractiveApprover();
        var permissions=new com.cafecli.harness.permission.PermissionCapability(approver,true);
        var audit=new com.cafecli.harness.permission.AuditCapability(permissions,new com.cafecli.policy.AuditLog(root.resolve("audit")));
        Capability command=new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/write","write through the permission route",(session,args) -> {
                    var input=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path",args).put("content","command body");
                    var result=session.invokeCommandTool("write_file",input);
                    return "Command result: " + result.status();
                }));
            }
        };
        try (var session=session(provider,List.of(new SessionCapability(),
                    new com.cafecli.harness.workspace.WorkspaceCapability(root),permissions,audit,command));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,approver)) {
            rig.type("/write approved.txt\r"); rig.awaitScreen("path: \"approved.txt\"");
            assertFalse(java.nio.file.Files.exists(root.resolve("approved.txt")));
            rig.type("y\r"); rig.awaitScreen("Command result: OK"); session.awaitIdle();
            assertEquals("command body",java.nio.file.Files.readString(root.resolve("approved.txt")));
            rig.type("/write cancelled.txt\r"); rig.awaitScreen("path: \"cancelled.txt\"");
            rig.type("draft stays"); rig.type("\033");
            session.awaitIdle(); rig.awaitScreen("Command result: CANCELLED");
            assertFalse(java.nio.file.Files.exists(root.resolve("cancelled.txt")));
            assertEquals("draft stays",rig.reader.getBuffer().toString());
            assertTrue(session.log().isEmpty()); assertTrue(provider.requests().isEmpty());
            assertTrue(session.dispatch("/audit").orElseThrow().contains("write_file"));
            rig.type("\025"); rig.exit();
        }
    }

    @Test @Timeout(25) void unknownAndMalformedCommandsGetTheLegacyRejectionAndNeverBecomeModelHistory() throws Exception {
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider,List.of(new SessionCapability()));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            for (String input : List.of("/unknown", "/clear extra", "/history  clear")) {
                rig.type(input+"\r");
                awaitDocument(rig,"❌ 未知命令: "+input);
            }
            assertTrue(session.log().isEmpty()); assertTrue(provider.requests().isEmpty());
            rig.exit();
        }
    }

    @Test @Timeout(25) void exitAliasesNormalizeCaseAndWhitespaceDrainTheirEchoAndCloseTheSession() throws Exception {
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider,List.of(new SessionCapability()));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            rig.type("  QuIt  \r"); rig.thread.join(5000);
            assertFalse(rig.thread.isAlive(),"the registered EXIT effect must end the resident reader");
            assertNull(rig.failure.get());
            awaitDocument(rig,"QuIt"); awaitDocument(rig,"再见");
            assertThrows(IllegalStateException.class,() -> session.submit("after close"));
            assertTrue(provider.requests().isEmpty());
        }
    }

    @Test @Timeout(25) void completionDiscoversACapabilityCommandWithoutAnyUiRegistration() throws Exception {
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        Capability command=new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/greet","greet",(context,args) -> "Completed for "+args));
            }
        };
        try (var session=session(provider,List.of(command));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            rig.type("/gre\tfriend\r"); rig.awaitScreen("Completed for friend");
            assertTrue(provider.requests().isEmpty()); rig.exit();
        }
    }

    @Test @Timeout(25) void sourceEchoAndSubmitPreparationUseTheSameTrimmedHumanInput() throws Exception {
        var seen=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("answer")));
        Capability prepare=new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.onSubmit((input,token) -> { seen.add(input); return Submission.proceed("MODEL "+input); });
            }
        };
        try (var session=session(provider,List.of(prepare));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            rig.type("   human source   \r"); rig.awaitScreen("answer"); session.awaitIdle();
            assertEquals(List.of("human source"),seen);
            assertTrue(provider.requests().getFirst().context().messages().toString().contains("MODEL human source"));
            awaitDocument(rig,"human source");
            assertFalse(document(rig).stream().anyMatch(row -> row.contains("MODEL human source")));
            rig.exit();
        }
    }

    @Test @Timeout(25) void queuedOrdinaryCommandsAndExitKeepTheirOrderWhileTheFirstHandlerIsBlocked() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var effects=new java.util.concurrent.CopyOnWriteArrayList<String>();
        Capability command=new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/first","first",(context,args) -> {
                    entered.countDown();
                    try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("first not released"); }
                    catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                    effects.add("first"); return "First command completed";
                }));
                registry.command(new SessionCommand("/second","second",(context,args) -> {
                    effects.add("second"); return "Second command completed";
                }));
            }
        };
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider,List.of(new SessionCapability(),command));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            try {
                rig.type("/first\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                rig.type("/second\r/exit\r");
                V2InteractiveReplTest.await(() -> rig.reader.getHistory().size()>=3);
                assertTrue(effects.isEmpty());
            } finally { release.countDown(); }
            rig.thread.join(5000); assertFalse(rig.thread.isAlive(),"queued exit must finish without another keystroke");
            assertNull(rig.failure.get());
            assertEquals(List.of("first","second"),effects);
            awaitDocument(rig,"First command completed"); awaitDocument(rig,"Second command completed");
            var rows=document(rig);
            assertTrue(indexContaining(rows,"First command completed")<indexContaining(rows,"Second command completed"));
            assertTrue(provider.requests().isEmpty());
        }
    }
    @Test @Timeout(25) void anOwnedCommandWakePreservesTheDraftAndDoesNotCancelTheFollowingModelRun() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        Capability command=gatedCommands(entered,release,new java.util.concurrent.CopyOnWriteArrayList<>());
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("Following model completed")));
        try (var session=session(provider,List.of(command));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            try {
                rig.type("/first\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                rig.type("/second\rpreserved draft");
                V2InteractiveReplTest.await(() -> rig.reader.getBuffer().toString().equals("preserved draft"));
            } finally { release.countDown(); }
            rig.awaitScreen("Second command completed"); session.awaitIdle();
            V2InteractiveReplTest.await(() -> rig.reader.getBuffer().toString().equals("preserved draft"));
            assertFalse(document(rig).stream().anyMatch(row -> row.contains("已请求取消") || row.contains("再按一次")));
            rig.type("\025following user input\r"); rig.awaitScreen("Following model completed"); session.awaitIdle();
            assertEquals(1,provider.requests().size());
            assertEquals(1,session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count());
            rig.exit();
        }
    }

    @Test @Timeout(25) void completionWhileTranscriptOwnsTheReaderDefersTheWakeAndTheNextCommandUntilTheModalCloses() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var effects=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider,List.of(gatedCommands(entered,release,effects)));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            try {
                rig.type("/first\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                rig.type("/second\r\017"); V2InteractiveReplTest.await(rig::overlayOpen);
            } finally { release.countDown(); }
            session.awaitIdle(); rig.awaitScreen("First command completed");
            assertTrue(rig.overlayOpen()); assertEquals(List.of("first"),effects);
            rig.type("\033"); rig.awaitPrimary(); rig.awaitScreen("Second command completed");
            assertEquals(List.of("first","second"),effects);
            rig.exit();
        }
    }

    @Test @Timeout(25) void queuedPaletteRunsOnTheInputOwnerAndKeepsTheSameTerminalStatusIdentity() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        Capability palette=new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/palette","palette",(context,args) -> "")
                        .withPresentation((context,args) -> new AgentSession.CommandPresentation(AgentSession.CommandEffect.PALETTE,
                                "Queued palette",List.of("first option","second option"),List.of("First selected","Second selected"))));
            }
        };
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider,List.of(gatedCommands(entered,release,new java.util.concurrent.CopyOnWriteArrayList<>()),palette));
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            assertSame(org.jline.utils.Status.getStatus(rig.terminal,true),org.jline.utils.Status.getStatus(rig.reader.getTerminal(),true));
            try {
                rig.type("/first\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                rig.type("/palette\r"); V2InteractiveReplTest.await(() -> rig.reader.getHistory().size()>=2);
            } finally { release.countDown(); }
            rig.awaitScreen("Queued palette"); rig.type("\r"); rig.awaitScreen("First selected");
            assertTrue(provider.requests().isEmpty()); rig.exit();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"key","signal"}) @Timeout(45)
    void realCtrlCRacingACommandReleaseStillCancelsOrClearsTheDraftInsteadOfBecomingAnOwnedWake(String delivery) throws Exception {
        for (int attempt=0;attempt<6;attempt++) {
            var entered=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var cancelled=new java.util.concurrent.atomic.AtomicInteger();
            var second=new java.util.concurrent.CountDownLatch(1);
            var followup=new java.util.concurrent.CountDownLatch(1);
            Capability commands=new Capability() {
                @Override public void attachSession(SessionRegistrar registry) {
                    registry.command(new SessionCommand("/first","first",(context,args) -> {
                        context.invokeCommandTool("gate",new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
                        return "First command completed";
                    }));
                    registry.command(new SessionCommand("/second","second",(context,args) -> { second.countDown(); return "Second command completed"; }));
                    registry.command(new SessionCommand("/third","third",(context,args) -> { followup.countDown(); return "Following command completed"; }));
                }
                @Override public void attachAgent(AgentScope scope) {
                    scope.tool(new com.cafecli.core.AgentTool() {
                        public String name() { return "gate"; }
                        public String description() { return "external wait"; }
                        public com.fasterxml.jackson.databind.JsonNode parameters() { return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); }
                        public com.cafecli.core.ToolResult execute(String id,com.fasterxml.jackson.databind.JsonNode input,
                                com.cafecli.ai.CancellationToken token,java.util.function.Consumer<com.cafecli.core.ToolResult> progress) {
                            token.onCancel(cancelled::incrementAndGet); entered.countDown();
                            try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("gate not released"); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            return new com.cafecli.core.ToolResult(List.of(new com.cafecli.ai.message.TextBlock("gate released")),null,
                                    com.cafecli.core.ToolResult.Status.OK);
                        }
                    });
                }
            };
            var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
            try (var session=session(provider,List.of(commands));
                 var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
                try {
                    rig.type("/first\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                    rig.type("/second\rdraft"); V2InteractiveReplTest.await(() -> rig.reader.getBuffer().toString().equals("draft"));
                    var start=new java.util.concurrent.CountDownLatch(1);
                    var releasing=Thread.ofVirtual().start(() -> { try { start.await(); release.countDown(); } catch (InterruptedException ignored) {} });
                    start.countDown();
                    if (delivery.equals("signal")) rig.terminal.raise(org.jline.terminal.Terminal.Signal.INT);
                    else rig.type("\003");
                    releasing.join();
                    session.awaitIdle();
                    V2InteractiveReplTest.await(() -> cancelled.get()>0 || rig.reader.getBuffer().length()==0);
                    assertTrue(rig.thread.isAlive(),"a real Ctrl+C with text must not arm or perform exit");
                    assertTrue(provider.requests().isEmpty());
                    rig.type("\025/third\r");
                    assertTrue(followup.await(5,java.util.concurrent.TimeUnit.SECONDS),"the next submitted command must still make progress");
                    rig.awaitScreen("Following command completed");
                    rig.exit();
                } finally { release.countDown(); }
            }
        }
    }

    @Test @Timeout(25) void hitlAndTaskCommandsRemainResponsiveDuringModelWorkAndShareTheFacadeMode() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var provider=FakeProvider.responding(request -> {
            entered.countDown();
            try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("not released"); }
            catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            return FakeResponse.text("Busy main completed");
        });
        var model=new Model("fake-model","fake","fake",10000,1000,Set.of());
        var permissions=new com.cafecli.harness.permission.PermissionCapability((request,token) ->
                com.cafecli.harness.permission.ApprovalResult.approve(),false);
        var config=ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var factory=new AgentFactory(provider,new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                config.homes().user().resolve("prompts"),config.homes().project().resolve("prompts"))),java.time.Duration.ofSeconds(90));
        var tasks=new com.cafecli.harness.tasks.SubAgentsCapability(factory,AgentProfile.mainAgent(model,List.of(permissions),5));
        try (var session=AgentSession.builder().interactive(true).config(config).factory(factory).model(model)
                    .capabilities(List.of(new SessionCapability(),permissions,tasks)).build();
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            try {
                rig.type("model work\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                rig.type("/hitl on\r/task\r"); rig.awaitScreen("当前会话没有任务");
                assertTrue(permissions.isEnabled()); assertTrue(session.status().approvalEnabled()); assertTrue(session.isBusy());
                rig.type("/hitl off\r"); rig.awaitScreen("HITL 审批已关闭"); assertFalse(session.approvalEnabled());
                rig.type("\031"); V2InteractiveReplTest.await(session::approvalEnabled); assertTrue(permissions.isEnabled());
                assertEquals(1,provider.requests().size());
            } finally { release.countDown(); }
            session.awaitIdle(); rig.awaitScreen("Busy main completed");
            assertEquals(1,session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count());
            assertFalse(provider.requests().getFirst().context().messages().toString().contains("/hitl"));
            rig.exit();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"/context,Context Usage","/policy,安全策略状态","/hooks,未加载任何生命周期 Hook","/audit,今日尚无审计记录"})
    @Timeout(25)
    void observationsAndTheLegacyCancelNoticeRemainAvailableWithoutAbortingBusyModelWork(String command,String expected) throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var token=new java.util.concurrent.atomic.AtomicReference<com.cafecli.ai.CancellationToken>();
        var fake=FakeProvider.responding(request -> {
            entered.countDown();
            try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("not released"); }
            catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            return FakeResponse.text("Busy observation run completed");
        });
        com.cafecli.ai.Provider provider=(model,context,options) -> { token.set(options.cancellation()); return fake.stream(model,context,options); };
        var config=ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var permissions=com.cafecli.harness.permission.PermissionCapability.headless();
        var audit=new com.cafecli.harness.permission.AuditCapability(permissions,new com.cafecli.policy.AuditLog(root.resolve("audit")));
        var completions=new java.util.concurrent.LinkedBlockingQueue<SessionCompletion>();
        try (var session=AgentSession.builder().interactive(true).config(config).provider(provider)
                    .model(new Model("fake-model","fake","fake",10000,1000,Set.of()))
                    .capabilities(List.of(new SessionCapability(),new com.cafecli.harness.workspace.WorkspaceCapability(root,config.workspace(),config.auditDirectory()),
                            permissions,audit,new com.cafecli.harness.hooks.HooksCapability(config.hooks(),List.of(),audit))).build();
             var rig=new V2InteractiveReplTest.ApprovalRig(session,null)) {
            session.subscribeCompletion(completions::add);
            try {
                rig.type("ordinary main work\r"); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                rig.type(command+"\r"); awaitDocument(rig,expected);
                assertTrue(session.isBusy()); assertFalse(token.get().isCancelled());
                rig.type("/cancel\r"); awaitDocument(rig,"当前没有正在运行的任务。");
                assertEquals("当前没有正在运行的任务。\n\n",session.dispatch("/cancel").orElseThrow());
                assertFalse(token.get().isCancelled(),"legacy /cancel prints a notice and never cancels work");
                assertTrue(completions.isEmpty(),"the held main request must still be running");
            } finally { release.countDown(); }
            session.awaitIdle(); rig.awaitScreen("Busy observation run completed");
            var completion=assertInstanceOf(SessionCompletion.Completed.class,completions.poll(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(com.cafecli.core.RunOutcome.Completed.class,completion.outcome());
            assertEquals(1,fake.requests().size());
            assertEquals(1,session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count());
            assertFalse(fake.requests().getFirst().context().messages().toString().contains(command));
            rig.exit();
        }
    }

    private Capability gatedCommands(java.util.concurrent.CountDownLatch entered,java.util.concurrent.CountDownLatch release,
                                    java.util.List<String> effects) {
        return new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/first","first",(context,args) -> {
                    entered.countDown();
                    try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("not released"); }
                    catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                    effects.add("first"); return "First command completed";
                }));
                registry.command(new SessionCommand("/second","second",(context,args) -> {
                    effects.add("second"); return "Second command completed";
                }));
            }
        };
    }

    private static int indexContaining(List<String> rows,String text) {
        for (int i=0;i<rows.size();i++) if (rows.get(i).contains(text)) return i;
        return -1;
    }

    private static List<String> document(V2InteractiveReplTest.ApprovalRig rig) {
        rig.pump.drain();
        var screen=new com.cafecli.render.inline.replay.TerminalScreenModel(100,40);
        screen.feed(rig.sink.toByteArray()); return screen.document();
    }
    private static void awaitDocument(V2InteractiveReplTest.ApprovalRig rig,String text) throws Exception {
        V2InteractiveReplTest.await(() -> document(rig).stream().anyMatch(row -> row.contains(text)));
    }

    private AgentSession session(FakeProvider provider, List<Capability> capabilities) {
        return AgentSession.builder().interactive(true).provider(provider)
                .config(ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root)))
                .model(new Model("fake-model","fake","fake",10000,1000,Set.of())).capabilities(capabilities).build();
    }
}
