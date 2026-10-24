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
    private static void await(BooleanSupplier condition) throws Exception {
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
