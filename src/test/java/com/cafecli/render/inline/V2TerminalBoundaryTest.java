package com.cafecli.render.inline;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.harness.*;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cafecli.render.inline.V2InteractiveReplTest.await;
import static org.junit.jupiter.api.Assertions.*;

/** Faults enter through Terminal's public control boundary while the actual resident reader owns the draft. */
@Timeout(20)
class V2TerminalBoundaryTest {
    @TempDir Path root;

    @Test void nativeGeometryInterruptedByRealIntStillCancelsKeepsDraftAndRestoresAttributesAndHandler() throws Exception {
        var modelEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = FakeProvider.responding(request -> {
            modelEntered.countDown();
            try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return FakeResponse.text("done");
        });
        var terminal = new AtomicReference<FaultTerminal>();
        var original = new AtomicReference<Attributes>();
        var priorHandler = new AtomicInteger();
        try (var session = session(provider,List.of());
             var rig = new V2InteractiveReplTest.ApprovalRig(session,null,(input,output) -> {
                 var nativeControl = new FaultTerminal(input,output);
                 nativeControl.handle(Terminal.Signal.INT,signal -> priorHandler.incrementAndGet());
                 original.set(new Attributes(nativeControl.getAttributes())); terminal.set(nativeControl); return nativeControl;
             })) {
            try {
                rig.type("run\r"); assertTrue(modelEntered.await(5,TimeUnit.SECONDS));
                rig.type("preserved"); await(() -> rig.reader.getBuffer().toString().equals("preserved"));
                terminal.get().fault.set(() -> {
                    terminal.get().raise(Terminal.Signal.INT);
                    Thread.interrupted(); // ExecHelper.waitFor consumes the thread's flag before throwing.
                    throw new IOError(new InterruptedIOException("Command interrupted").initCause(new InterruptedException()));
                });
                rig.type("x");
                rig.awaitScreen("已请求取消"); release.countDown();
                session.awaitIdle();
                await(() -> rig.reader.capture().lastFrame().plain().stream().anyMatch(line -> line.contains("preservedx")));
                assertNull(rig.failure.get());
                rig.type("\025"); await(() -> rig.reader.getBuffer().length()==0); rig.exit();
                var restored = terminal.get().getAttributes();
                assertEquals(original.get().getLocalFlags(),restored.getLocalFlags());
                assertEquals(original.get().getInputFlags(),restored.getInputFlags());
                assertEquals(original.get().getOutputFlags(),restored.getOutputFlags());
                assertEquals(original.get().getControlFlags(),restored.getControlFlags());
                assertEquals(original.get().getControlChars(),restored.getControlChars());
                terminal.get().raise(Terminal.Signal.INT); assertEquals(1,priorHandler.get());
            } finally { release.countDown(); }
        }
    }

    @Test void anApprovalRefreshContendingWithRedisplayEventuallyPublishesThePendingPromptWithoutAnotherKey() throws Exception {
        var modelEntered = new CountDownLatch(1); var releaseModel = new CountDownLatch(1);
        var frameEntered = new CountDownLatch(1); var releaseFrame = new CountDownLatch(1);
        var arm = new java.util.concurrent.atomic.AtomicBoolean();
        var provider = FakeProvider.responding(request -> {
            modelEntered.countDown();
            try { assertTrue(releaseModel.await(5,TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            return FakeResponse.builder().toolCall("write","write_file","{\"path\":\"pending.txt\",\"content\":\"pending\"}").build();
        });
        var approvals = new com.cafecli.ui.InteractiveApprover();
        try (var session = session(provider,List.of(new com.cafecli.harness.workspace.WorkspaceCapability(root),
                new com.cafecli.harness.permission.PermissionCapability(approvals,true)));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,approvals)) {
            rig.reader.capture().setFrameListener(frame -> {
                if (Thread.currentThread()==rig.thread && arm.compareAndSet(true,false)) {
                    frameEntered.countDown();
                    try { assertTrue(releaseFrame.await(5,TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }
            });
            try {
                rig.type("run\r"); assertTrue(modelEntered.await(5,TimeUnit.SECONDS));
                await(() -> rig.reader.capture().lastFrame().plain().stream().anyMatch(line -> line.contains("steer")));
                arm.set(true); rig.type("d"); assertTrue(frameEntered.await(5,TimeUnit.SECONDS));
                releaseModel.countDown(); rig.awaitScreen("HITL approval");
                rig.renderer.bindLineReader(rig.reader);
                releaseFrame.countDown();
                await(() -> rig.reader.capture().lastFrame().plain().stream().anyMatch(line -> line.contains("y / n / a")));
                rig.awaitScreen("y / n / a");
                assertEquals("d",rig.reader.getBuffer().toString());
            } finally { releaseModel.countDown(); releaseFrame.countDown(); }
        }
    }

    @Test void anOwnedFifoWakeDuringNativeGeometryPreservesDraftAndRunsQueuedCommandWithoutUserCancellation() throws Exception {
        var commandEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var geometryEntered = new CountDownLatch(1); var next = new CountDownLatch(1);
        var terminal = new AtomicReference<FaultTerminal>();
        Capability commands = new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                registry.command(new SessionCommand("/first","first",(session,args) -> {
                    commandEntered.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                    return "first done";
                }));
                registry.command(new SessionCommand("/second","second",(session,args) -> { next.countDown(); return "second done"; }));
            }
        };
        var provider = FakeProvider.scripted(List.of());
        try (var session = session(provider,List.of(commands));
             var rig = new V2InteractiveReplTest.ApprovalRig(session,null,(input,output) -> {
                 var nativeControl = new FaultTerminal(input,output); terminal.set(nativeControl); return nativeControl;
             })) {
            try {
                rig.type("/first\r"); assertTrue(commandEntered.await(5,TimeUnit.SECONDS));
                rig.type("/second\rpreserved"); await(() -> rig.reader.getBuffer().toString().equals("preserved"));
                terminal.get().fault.set(() -> {
                    geometryEntered.countDown();
                    try { new CountDownLatch(1).await(5,TimeUnit.SECONDS); fail("owned wake never reached the reader"); }
                    catch (InterruptedException expected) { throw new IOError(new InterruptedIOException("Command interrupted").initCause(expected)); }
                });
                rig.type("x"); assertTrue(geometryEntered.await(5,TimeUnit.SECONDS)); release.countDown();
                assertTrue(next.await(5,TimeUnit.SECONDS)); session.awaitIdle();
                await(() -> rig.reader.getBuffer().toString().equals("preservedx"));
                assertNull(rig.failure.get()); assertTrue(provider.requests().isEmpty());
                rig.type("\025"); await(() -> rig.reader.getBuffer().length()==0); rig.exit();
            } finally { release.countDown(); }
        }
    }

    private AgentSession session(FakeProvider provider,List<Capability> capabilities) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        return AgentSession.builder().interactive(true).config(config).provider(provider)
                .model(new Model("fake","fake","fake",128000,8192,Set.of())).capabilities(capabilities).build();
    }
    private static final class FaultTerminal extends TracingTerminal {
        final AtomicReference<Runnable> fault = new AtomicReference<>();
        FaultTerminal(InputStream input,OutputStream output) throws IOException { super("native-control","xterm-256color",input,output); }
        @Override public Size getBufferSize() {
            if (fault!=null && Thread.currentThread().getName().equals("v2-approval-repl")) {
                Runnable action = fault.getAndSet(null); if (action!=null) action.run();
            }
            return super.getBufferSize();
        }
    }
}
