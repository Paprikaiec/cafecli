package com.cafecli.render.inline;

import com.cafecli.ui.ProjectHookTrustPrompt;
import com.cafecli.render.inline.replay.TerminalScreenModel;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.cafecli.render.inline.V2InteractiveReplTest.await;

class ProjectHookTrustPromptTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings={"no","ctrlC","eof"}) @Timeout(30)
    void explicitNoDeclinesButCtrlCAndEofPropagateToTheWholeHookLoader(String answer) throws Exception {
        var sink = new ByteArrayOutputStream(); var input = new PipedInputStream(65536); var pump = new ManualFramePump();
        try (var keys = new PipedOutputStream(input);
             var terminal = new TracingTerminal("v2-hook-trust","xterm-256color",input,sink)) {
            terminal.setSize(new Size(100,40));
            try (var renderer = new InlineRenderer(terminal,new PrintStream(sink,false,StandardCharsets.UTF_8),pump,
                    new ManualFrameTicker(),new ManualClock())) {
                var reader = CafeCliLineReader.create(terminal,"trust-test",Map.of(LineReader.INPUT_RC_FILE_NAME,"/nonexistent-inputrc"),null,null,null);
                renderer.bindLineReader(reader); renderer.start();
                var result = new AtomicReference<Boolean>(); var failure = new AtomicReference<Throwable>();
                var prompt = new ProjectHookTrustPrompt(renderer,reader);
                var thread = Thread.ofVirtual().start(() -> {
                    try { result.set(prompt.confirm(root,List.of("printf actual-project-hook-command"))); }
                    catch (Throwable thrown) { failure.set(thrown); }
                });
                try {
                    await(() -> !reader.capture().lastFrame().isEmpty());
                    pump.drain(); var screen = new TerminalScreenModel(100,40); screen.feed(sink.toByteArray());
                    assertTrue(screen.document().stream().anyMatch(line -> line.contains("printf actual-project-hook-command")),"actual commands must be visible before a decision");
                    if (answer.equals("ctrlC")) terminal.raise(Terminal.Signal.INT);
                    else { keys.write((answer.equals("eof") ? "\004" : "n\r").getBytes(StandardCharsets.UTF_8)); keys.flush(); }
                    thread.join(5000); assertFalse(thread.isAlive(),"startup prompt must release its reader");
                    if (answer.equals("no")) { assertEquals(Boolean.FALSE,result.get()); assertNull(failure.get()); }
                    else {
                        assertNull(result.get(),"an interruption is not an explicit declined trust decision");
                        if (answer.equals("ctrlC")) assertInstanceOf(UserInterruptException.class,failure.get());
                        else assertInstanceOf(EndOfFileException.class,failure.get());
                    }
                } finally {
                    if (thread.isAlive()) { keys.close(); thread.join(5000); }
                    assertFalse(thread.isAlive(),"startup reader cleanup must exit");
                }
            }
        }
    }
}
