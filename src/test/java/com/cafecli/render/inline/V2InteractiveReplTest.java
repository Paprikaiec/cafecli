package com.cafecli.render.inline;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.*;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.RunInput;
import com.cafecli.harness.*;
import com.cafecli.harness.config.*;
import com.cafecli.ui.InteractiveRepl;
import com.cafecli.render.inline.replay.TerminalScreenModel;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.cafecli.render.inline.replay.TerminalScreenModel.*;

class V2InteractiveReplTest {
    @TempDir Path root;
    @Test @Timeout(30)
    void residentReaderKeepsTheDraftWhileBusyAndEofDrainsFinalPresentationAndRestoresSignalHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.responding(request -> {
            entered.countDown();
            try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return FakeResponse.text("The final answer.\n");
        });
        Capability prepared = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.inputPreparation((input, token) -> new RunInput(input.source(),
                        UserMessage.text("MODEL PRESENTATION ONLY"),List.of(),0));
            }
        };
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var session = AgentSession.builder().interactive(true).config(config).provider(provider)
                .model(new Model("fake-model","fake","fake",10000,1000,Set.of())).capabilities(List.of(prepared)).build();
        var sink = new ByteArrayOutputStream();
        var input = new PipedInputStream(65536);
        var pump = new ManualFramePump();
        try (var keys = new PipedOutputStream(input);
             var terminal = new TracingTerminal("v2-repl","xterm-256color",input,sink)) {
            terminal.setSize(new Size(80,24));
            var signals = new AtomicInteger();
            terminal.handle(Terminal.Signal.INT, signal -> signals.incrementAndGet());
            try (var renderer = new InlineRenderer(terminal,new PrintStream(sink,false,StandardCharsets.UTF_8),pump,
                    new ManualFrameTicker(),new ManualClock())) {
                var reader = CafeCliLineReader.create(terminal,"v2-test",Map.of(
                        LineReader.INPUT_RC_FILE_NAME,"/nonexistent-inputrc",LineReader.AMBIGUOUS_BINDING,100L),null,null,null);
                renderer.bindLineReader(reader); renderer.start();
                var failure = new AtomicReference<Throwable>();
                try (var repl = new InteractiveRepl(terminal,reader,renderer,session)) {
                    var thread = new Thread(() -> { try { repl.run(); } catch(Throwable t) {failure.set(t);} },"v2-test-repl");
                    thread.setDaemon(true); thread.start();
                    try {
                        await(() -> !reader.capture().lastFrame().isEmpty());
                        type(keys,"actual user source\r");
                        assertTrue(entered.await(5,TimeUnit.SECONDS));
                        type(keys,"draft during model");
                        await(() -> reader.capture().lastFrame().plain().stream().anyMatch(s -> s.contains("draft during model")));
                        pump.drain();
                        assertEquals(1,rowsContaining(document(sink),"draft during model"),dump(document(sink)));
                        assertTrue(session.isBusy());
                        type(keys,"\025");
                        await(() -> reader.getBuffer().length()==0);
                    } finally { release.countDown(); }
                    session.awaitIdle();
                    type(keys,"\004");
                    thread.join(5000);
                    assertFalse(thread.isAlive(),"idle EOF must return and drain accepted UI events");
                    assertNull(failure.get());
                    pump.drain();
                    var doc = document(sink);
                    assertEquals(1,rowsContaining(doc,"actual user source"),dump(doc));
                    assertEquals(0,rowsContaining(doc,"MODEL PRESENTATION ONLY"),dump(doc));
                    assertEquals(1,rowsContaining(doc,"The final answer."),dump(doc));
                    assertEquals(UserMessage.text("MODEL PRESENTATION ONLY"),provider.requests().getFirst().context().messages().getLast());
                    terminal.raise(Terminal.Signal.INT);
                    assertEquals(1,signals.get(),"REPL must restore the handler it replaced");
                }
            }
        } finally { release.countDown(); session.close(); }
    }
    @Test @Timeout(30)
    void approvalsAppearOneAtATimeInSourceOrderAndNoToolRunsBeforeTheLastAnswer() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("one","write_file","{\"path\":\"first.txt\",\"content\":\"one\"}")
                .toolCall("two","write_file","{\"path\":\"second.txt\",\"content\":\"two\"}")
                .toolCall("three","write_file","{\"path\":\"third.txt\",\"content\":\"three\"}").build(),
                FakeResponse.text("Approved batch finished.")));
        var approvals = new com.cafecli.ui.InteractiveApprover();
        var permissions = new com.cafecli.harness.permission.PermissionCapability(approvals,true);
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var session = AgentSession.builder().interactive(true).config(config).provider(provider)
                .model(new Model("fake-model","fake","fake",10000,1000,Set.of()))
                .capabilities(List.of(new com.cafecli.harness.workspace.WorkspaceCapability(root,config.workspace()),permissions)).build();
        try (var rig = new ApprovalRig(session,approvals)) {
            rig.type("write three files\r");
            for (String name : List.of("first.txt","second.txt","third.txt")) {
                rig.awaitScreen("path: \""+name+"\"");
                assertFalse(java.nio.file.Files.exists(root.resolve("first.txt")),"no batch execution during approvals");
                rig.type("y\r");
            }
            session.awaitIdle();
            assertEquals("one",java.nio.file.Files.readString(root.resolve("first.txt")));
            assertEquals("two",java.nio.file.Files.readString(root.resolve("second.txt")));
            assertEquals("three",java.nio.file.Files.readString(root.resolve("third.txt")));
            rig.exit();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"escape","ctrlC","overlayCtrlC"})
    @Timeout(30)
    void cancellingAPendingApprovalDeniesItPreservesTheDraftAndEndsCancelled(String key) throws Exception {
        var approvals = new com.cafecli.ui.InteractiveApprover();
        var permissions = new com.cafecli.harness.permission.PermissionCapability(approvals,true);
        var decided = new AtomicReference<com.cafecli.harness.permission.PermissionEvent.Decided>();
        permissions.subscribe(event -> { if (event instanceof com.cafecli.harness.permission.PermissionEvent.Decided reply) decided.set(reply); });
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("write","write_file","{\"path\":\"cancelled.txt\",\"content\":\"never\"}").build()));
        try (var session = session(provider,permissions); var rig = new ApprovalRig(session,approvals)) {
            var completion = new AtomicReference<SessionCompletion>();
            session.subscribeCompletion(completion::set);
            rig.type("write a file\r"); rig.awaitScreen("path: \"cancelled.txt\"");
            rig.type("my preserved draft"); await(() -> rig.reader.getBuffer().toString().equals("my preserved draft"));
            if (key.equals("overlayCtrlC")) {
                rig.type("\017"); await(rig::overlayOpen);
                rig.terminal.raise(Terminal.Signal.INT);
            } else if (key.equals("ctrlC")) rig.terminal.raise(Terminal.Signal.INT);
            else rig.type("\033");
            session.awaitIdle(); await(() -> completion.get() != null);
            assertInstanceOf(com.cafecli.core.RunOutcome.Cancelled.class,
                    assertInstanceOf(SessionCompletion.Completed.class,completion.get()).outcome());
            assertEquals(com.cafecli.harness.permission.ApprovalResult.Decision.REJECTED,decided.get().reply().decision());
            assertFalse(java.nio.file.Files.exists(root.resolve("cancelled.txt")));
            if (key.equals("overlayCtrlC")) { assertTrue(rig.overlayOpen(),"Ctrl+C cancels without closing the modal"); rig.awaitScreen("已请求取消"); rig.type("\033"); rig.awaitPrimary(); }
            await(() -> rig.reader.capture().lastFrame().plain().stream().anyMatch(line -> line.contains("my preserved draft")));
            rig.type("\025"); await(() -> rig.reader.getBuffer().length()==0); rig.exit();
        }
    }

    @Test @Timeout(30)
    void ctrlDMidRunDoesNothingAndEscInsideTheOverlayOnlyClosesTheOverlay() throws Exception {
        var approvals = new com.cafecli.ui.InteractiveApprover();
        var permissions = new com.cafecli.harness.permission.PermissionCapability(approvals,true);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("write","write_file","{\"path\":\"waiting.txt\",\"content\":\"done\"}").build(),FakeResponse.text("done")));
        try (var session = session(provider,permissions); var rig = new ApprovalRig(session,approvals)) {
            rig.type("write a file\r"); rig.awaitScreen("path: \"waiting.txt\"");
            rig.type("\004"); Thread.sleep(150);
            assertTrue(rig.thread.isAlive(),"Ctrl+D during the run must not exit"); assertTrue(session.isBusy());
            rig.type("\017"); await(rig::overlayOpen); rig.type("\033"); rig.awaitPrimary();
            assertTrue(session.isBusy(),"modal ESC only returns the terminal");
            rig.type("y\r"); session.awaitIdle();
            assertEquals("done",java.nio.file.Files.readString(root.resolve("waiting.txt"))); rig.exit();
        }
    }

    @Test @Timeout(30)
    void idleKeysKeepEscAndCtrlDHarmlessWithTextAndCtrlCClearsThenConfirmsExit() throws Exception {
        var approvals = new com.cafecli.ui.InteractiveApprover();
        try (var session = session(FakeProvider.scripted(List.of()),new com.cafecli.harness.permission.PermissionCapability(approvals,false));
             var rig = new ApprovalRig(session,approvals)) {
            rig.type("idle draft"); await(() -> rig.reader.getBuffer().toString().equals("idle draft"));
            rig.type("\033"); Thread.sleep(150); assertEquals("idle draft",rig.reader.getBuffer().toString());
            rig.type("\004"); Thread.sleep(100); assertTrue(rig.thread.isAlive()); assertEquals("idle draft",rig.reader.getBuffer().toString());
            rig.terminal.raise(Terminal.Signal.INT); await(() -> rig.reader.getBuffer().length()==0);
            rig.terminal.raise(Terminal.Signal.INT); rig.awaitScreen("再按一次 Ctrl+C 退出"); assertTrue(rig.thread.isAlive());
            rig.terminal.raise(Terminal.Signal.INT); rig.thread.join(5000); assertFalse(rig.thread.isAlive()); assertNull(rig.failure.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"runtime","error"})
    @Timeout(30)
    void theSignalGuardCancelsSubmissionPreparationOutsideReadLineWithoutArmingExit(String failureKind) throws Exception {
        var entered = new CountDownLatch(1);
        Capability preparation = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.onSubmit((input,token) -> {
                    var cancelled = new CountDownLatch(1);
                    try (var ignored = token.onCancel(() -> {
                        cancelled.countDown();
                        if (failureKind.equals("error")) throw new AssertionError("cancel callback failed");
                        throw new IllegalStateException("cancel callback failed");
                    })) {
                        entered.countDown();
                        try { assertTrue(cancelled.await(5,TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    return Submission.proceed(input);
                });
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("not reached")));
        try (var session = session(provider,preparation); var rig = new ApprovalRig(session,new com.cafecli.ui.InteractiveApprover())) {
            rig.type("preparing\r"); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(session.isBusy(),"preparation is covered by the synchronous run guard");
            assertDoesNotThrow(() -> rig.terminal.raise(Terminal.Signal.INT),"a cancellation callback must not escape the signal handler");
            session.awaitIdle();
            assertTrue(rig.thread.isAlive(),"signal cancellation cannot turn into idle double-Ctrl+C exit");
            assertTrue(provider.requests().isEmpty()); rig.exit();
        }
    }

    @Test @Timeout(30)
    void cancellingAHalfStreamedAnswerStopsTheRequestAndKeepsTheTextBeforeTheCancellationNotice() throws Exception {
        var partial = new CountDownLatch(1);
        com.cafecli.ai.Provider provider = (model,context,options) -> {
            var step = new AtomicInteger();
            return com.cafecli.ai.AssistantStream.of(model,options.cancellation(),out -> {
                if (step.getAndIncrement()==0) { out.textDelta("A partial streamed answer.\n"); partial.countDown(); return true; }
                var cancelled = new CountDownLatch(1);
                try (var ignored = options.cancellation().onCancel(cancelled::countDown)) {
                    try { assertTrue(cancelled.await(5,TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return false;
            });
        };
        try (var session = session(provider,new Capability() {}); var rig = new ApprovalRig(session,new com.cafecli.ui.InteractiveApprover())) {
            rig.type("stream\r"); assertTrue(partial.await(5,TimeUnit.SECONDS)); rig.awaitScreen("A partial streamed answer.");
            rig.type("saved draft"); await(() -> rig.reader.getBuffer().toString().equals("saved draft")); rig.type("\033");
            session.awaitIdle(); rig.awaitScreen("已取消");
            var model = new TerminalScreenModel(100,40); model.feed(rig.sink.toByteArray());
            assertTrue(model.document().stream().anyMatch(row -> row.contains("A partial streamed answer.")));
            assertEquals("saved draft",rig.reader.getBuffer().toString());
            rig.type("\025"); await(() -> rig.reader.getBuffer().length()==0); rig.exit();
        }
    }

    @Test @Timeout(30)
    void anApprovalArrivingWhileBrowsingClosesTheOverlayEvenWithActivityRefreshQueuedAheadOfIt() throws Exception {
        var approvals = new com.cafecli.ui.InteractiveApprover();
        var permissions = new com.cafecli.harness.permission.PermissionCapability(approvals,true);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("write","write_file","{\"path\":\"from-overlay.txt\",\"content\":\"done\"}").build(),FakeResponse.text("done")));
        try (var session = session(provider,permissions); var rig = new ApprovalRig(session,approvals)) {
            rig.type("\017"); await(rig::overlayOpen);
            session.submit("write while browsing");
            rig.awaitScreen("path: \"from-overlay.txt\"");
            assertFalse(rig.overlayOpen(),"approval must return the input box without user action");
            rig.type("y\r"); session.awaitIdle();
            assertEquals("done",java.nio.file.Files.readString(root.resolve("from-overlay.txt"))); rig.exit();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"mcp__","mcp____tool"})
    @Timeout(30)
    void anEmptyMcpServerCannotTurnTheInputBoxAllReplyIntoAServerGrant(String name) throws Exception {
        var approvals = new com.cafecli.ui.InteractiveApprover();
        var permissions = new com.cafecli.harness.permission.PermissionCapability(approvals,true);
        var reply = new AtomicReference<com.cafecli.harness.permission.ApprovalResult>();
        permissions.subscribe(event -> { if (event instanceof com.cafecli.harness.permission.PermissionEvent.Decided decided) reply.set(decided.reply()); });
        Capability tool = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new com.cafecli.core.AgentTool() {
                    public String name() { return name; }
                    public String description() { return "test tool boundary"; }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); }
                    public com.cafecli.core.ToolResult execute(String id,com.fasterxml.jackson.databind.JsonNode args,
                            com.cafecli.ai.CancellationToken token,java.util.function.Consumer<com.cafecli.core.ToolResult> progress) {
                        return new com.cafecli.core.ToolResult(List.of(new com.cafecli.ai.message.TextBlock("done")),null,com.cafecli.core.ToolResult.Status.OK);
                    }
                });
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("tool",name,"{}").build(),FakeResponse.text("done")));
        try (var session = session(provider,permissions,tool); var rig = new ApprovalRig(session,approvals)) {
            rig.type("call tool\r"); rig.awaitScreen("需要审批");
            rig.type("a server\r"); session.awaitIdle();
            assertEquals(com.cafecli.harness.permission.ApprovalResult.Decision.APPROVED_ALL,reply.get().decision()); rig.exit();
        }
    }

    @Test @Timeout(30)
    void emptyAndInvalidApprovalRepliesStayPendingAndModifiedArgumentsReachTheGuardedTool() throws Exception {
        var approvals = new com.cafecli.ui.InteractiveApprover();
        var permissions = new com.cafecli.harness.permission.PermissionCapability(approvals,true);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("write","write_file","{\"path\":\"original.txt\",\"content\":\"old\"}").build(),FakeResponse.text("done")));
        try (var session = session(provider,permissions); var rig = new ApprovalRig(session,approvals)) {
            rig.type("write file\r"); rig.awaitScreen("path: \"original.txt\"");
            rig.type("\r"); Thread.sleep(50); assertTrue(session.isBusy());
            rig.type("unknown\r"); rig.awaitScreen("无法识别的回答"); assertTrue(session.isBusy());
            rig.type("m {broken\r"); rig.awaitScreen("参数不是合法 JSON"); assertTrue(session.isBusy());
            rig.type("m {\"path\":\"modified.txt\",\"content\":\"edited\"}\r"); session.awaitIdle();
            assertFalse(java.nio.file.Files.exists(root.resolve("original.txt")));
            assertEquals("edited",java.nio.file.Files.readString(root.resolve("modified.txt")));
            assertEquals(2,provider.requests().size(),"approval replies are never ordinary prompts"); rig.exit();
        }
    }

    private AgentSession session(com.cafecli.ai.Provider provider,Capability... capabilities) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        var attached = new ArrayList<Capability>();
        attached.add(new com.cafecli.harness.workspace.WorkspaceCapability(root,config.workspace()));
        attached.addAll(List.of(capabilities));
        return AgentSession.builder().interactive(true).config(config).provider(provider)
                .model(new Model("fake-model","fake","fake",10000,1000,Set.of()))
                .capabilities(attached).build();
    }

    static final class ApprovalRig implements AutoCloseable {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final PipedInputStream input = new PipedInputStream(65536);
        final PipedOutputStream keys = new PipedOutputStream(input);
        final TracingTerminal terminal;
        final ManualFramePump pump = new ManualFramePump();
        final InlineRenderer renderer;
        final CafeCliLineReader reader;
        final InteractiveRepl repl;
        final AgentSession session;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        ApprovalRig(AgentSession session,com.cafecli.ui.InteractiveApprover approvals) throws Exception {
            this(session,approvals,(input,output) -> new TracingTerminal("v2-approvals","xterm-256color",input,output));
        }
        interface TerminalFactory { TracingTerminal create(InputStream input,OutputStream output) throws IOException; }
        ApprovalRig(AgentSession session,com.cafecli.ui.InteractiveApprover approvals,TerminalFactory factory) throws Exception {
            this.session=session;
            terminal=factory.create(input,sink);
            terminal.setSize(new Size(100,40));
            renderer = new InlineRenderer(terminal,new PrintStream(sink,false,StandardCharsets.UTF_8),pump,
                    new ManualFrameTicker(),new ManualClock());
            reader = CafeCliLineReader.create(new com.cafecli.ui.InputTerminal(terminal),"v2-test",Map.of(LineReader.INPUT_RC_FILE_NAME,"/nonexistent-inputrc",
                    LineReader.AMBIGUOUS_BINDING,100L),null,null,null);
            renderer.bindLineReader(reader); renderer.start();
            repl = new InteractiveRepl(terminal,reader,renderer,session,approvals);
            thread = new Thread(() -> { try { repl.run(); } catch(Throwable t) { failure.set(t); } },"v2-approval-repl");
            thread.setDaemon(true); thread.start();
            await(() -> !reader.capture().lastFrame().isEmpty());
        }
        void type(String text) throws Exception { V2InteractiveReplTest.type(keys,text); }
        void awaitScreen(String text) throws Exception {
            try {
                await(() -> { pump.drain(); var model = new TerminalScreenModel(100,40);model.feed(sink.toByteArray());
                    return model.screen().stream().anyMatch(row -> row.contains(text)); });
            } catch (AssertionError timeout) {
                var evidence = java.nio.file.Files.createTempDirectory("cafecli-modal-screen-");
                byte[] bytes = sink.toByteArray();
                java.nio.file.Files.write(evidence.resolve("terminal.bin"),bytes);
                var model = new TerminalScreenModel(100,40); model.feed(bytes);
                String state = "Expected visible: " + text + "\nOverlay open: " + overlayOpen()
                        + "\nReader alive/state: " + thread.isAlive() + "/" + thread.getState()
                        + "\nReader failure: " + failure.get() + "\nCaptured input: " + reader.capture().lastFrame().plain()
                        + "\nVisible across row boundaries: " + String.join("",model.screen()).replaceAll("\\s+", "")
                                .contains(text.replaceAll("\\s+", ""))
                        + "\nSCREEN\n" + String.join("\n",model.screen())
                        + "\nDOCUMENT\n" + String.join("\n",model.document());
                java.nio.file.Files.writeString(evidence.resolve("screen.txt"),state);
                java.nio.file.Files.writeString(evidence.resolve("reader-stack.txt"),
                        java.util.Arrays.toString(thread.getStackTrace()));
                var writes = terminal.tracer().records();
                java.nio.file.Files.writeString(evidence.resolve("terminal-writes.txt"),writes.subList(
                        Math.max(0,writes.size()-200),writes.size()).stream().map(record -> record+"\n"+record.fullStack)
                        .collect(java.util.stream.Collectors.joining("\n")));
                throw new AssertionError(state + "\nArtifacts: " + evidence,timeout);
            }
        }
        void awaitPrimary() throws Exception {
            await(() -> { pump.drain(); return !overlayOpen(); });
            pump.drain();
        }
        boolean overlayOpen() {
            String raw = sink.toString(StandardCharsets.UTF_8);
            return raw.lastIndexOf("\033[?1049h") > raw.lastIndexOf("\033[?1049l");
        }
        void exit() throws Exception {
            session.awaitIdle(); await(() -> !session.isBusy());
            type("\004"); thread.join(5000); assertFalse(thread.isAlive(),"closing EOF must exit"); assertNull(failure.get());
        }
        @Override public void close() throws Exception {
            if (overlayOpen()) { type("\033"); awaitPrimary(); }
            session.cancel(); session.awaitIdle();
            if (thread.isAlive()) { type("\025\004"); thread.join(5000); }
            assertFalse(thread.isAlive(),"REPL cleanup must exit");
            repl.close(); renderer.close(); terminal.close(); keys.close();
        }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"condition did not become true");
    }
    private static void type(PipedOutputStream keys,String text) throws IOException {
        keys.write(text.getBytes(StandardCharsets.UTF_8)); keys.flush();
    }
    private static List<String> document(ByteArrayOutputStream sink) {
        var model = new TerminalScreenModel(80,24);model.feed(sink.toByteArray());return model.document();
    }
}
