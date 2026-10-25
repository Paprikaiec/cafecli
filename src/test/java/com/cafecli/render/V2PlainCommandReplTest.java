package com.cafecli.render;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.*;
import com.cafecli.harness.*;
import com.cafecli.harness.config.*;
import com.cafecli.ui.InputTerminal;
import com.cafecli.ui.InteractiveRepl;
import org.jline.reader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class V2PlainCommandReplTest {
    @TempDir Path root;
    @Test @Timeout(25) void plainFallbackPreservesPlanBytesAndDrainsQueuedCommandsBeforeExit() throws Exception {
        String tombstone="""
                📋 Plan-and-Execute（`/plan`）已移除 —— 见 ADR 0015（docs/adr/0015-retire-plan-and-execute-and-lanterna-tui.md）。
                   它与已退役的 `/team` 是同一物种：模型之外的确定性编排脚手架。
                   直接把任务描述给默认的 ReAct 即可；需要拆分并行推进时，主 Agent 会自己用 spawn_agent 派生子任务。
                """+"\n";
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var effects=new CopyOnWriteArrayList<String>();
        Capability commands=new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.command(new SessionCommand("/first","first",(session,args) -> {
                    entered.countDown();
                    try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("not released"); }
                    catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                    effects.add("first"); return "First plain command";
                }));
                registrar.command(new SessionCommand("/second","second",(session,args) -> { effects.add("second"); return "Second plain command"; }));
            }
        };
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        var session=AgentSession.builder().interactive(true).provider(provider)
                .model(new Model("fake","fake","fake",128000,8192,Set.of()))
                .config(ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root)))
                .capabilities(List.of(new SessionCapability(),commands)).build();
        var sink=new ByteArrayOutputStream(); var input=new PipedInputStream(65536);
        try (session; var keys=new PipedOutputStream(input);
             var terminal=new InputTerminal(new org.jline.terminal.impl.ExternalTerminal("plain-command","dumb",input,sink,StandardCharsets.UTF_8));
             var renderer=new PlainRenderer(new PrintStream(sink,true,StandardCharsets.UTF_8),new BufferedReader(new StringReader("")))) {
            var reader=LineReaderBuilder.builder().terminal(terminal).variable(LineReader.INPUT_RC_FILE_NAME,"/nonexistent-inputrc").build();
            var failure=new AtomicReference<Throwable>();
            try (var repl=new InteractiveRepl(terminal,reader,renderer,session)) {
                var thread=new Thread(() -> { try { repl.run(); } catch (Throwable problem) { failure.set(problem); } },"plain-command-reader");
                thread.setDaemon(true); thread.start();
                try {
                    keys.write("/plan any task\r".getBytes(StandardCharsets.UTF_8)); keys.flush();
                    await(() -> sink.toString(StandardCharsets.UTF_8).contains(tombstone));
                    assertEquals(tombstone,session.dispatch(" /PlAn anything ").orElseThrow());
                    keys.write("/first\r".getBytes(StandardCharsets.UTF_8)); keys.flush(); assertTrue(entered.await(5,TimeUnit.SECONDS));
                    keys.write("/second\r/exit\r".getBytes(StandardCharsets.UTF_8)); keys.flush();
                    await(() -> reader.getHistory().size()>=4);
                } finally { release.countDown(); }
                thread.join(5000); assertFalse(thread.isAlive(),"plain queued exit must need no extra key"); assertNull(failure.get());
                assertEquals(List.of("first","second"),effects);
                String text=sink.toString(StandardCharsets.UTF_8);
                assertTrue(text.indexOf("First plain command")<text.indexOf("Second plain command"));
                assertTrue(provider.requests().isEmpty()); assertTrue(session.log().isEmpty());
            }
        } finally { release.countDown(); }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
