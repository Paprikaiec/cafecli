package com.cafecli.app;

import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveReaderTest {
    @TempDir Path root;

    @Test void startupReaderPersistsSafeHistoryUnderCapturedHomeAndRetainsEditingPresentation() throws Exception {
        Path home = Files.createDirectories(root.resolve("captured-home/.paicli")).getParent();
        var settings = ConfigLoader.history(new ConfigSources(Map.of("cafecli.history.size", "17",
                "paicli.history.size", "5"), Map.of("PAICLI_HISTORY_FILE_SIZE", "23"), home, root));
        try (var terminal = TerminalBuilder.builder().system(false).dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build()) {
            var reader = InteractiveReader.create(terminal,false,settings);
            assertEquals(home.resolve(".paicli/history/input.history"),reader.getVariable(LineReader.HISTORY_FILE));
            assertEquals(17,reader.getVariable(LineReader.HISTORY_SIZE));
            assertEquals(23,reader.getVariable(LineReader.HISTORY_FILE_SIZE));
            for (var option : new LineReader.Option[]{LineReader.Option.HISTORY_IGNORE_SPACE,
                    LineReader.Option.HISTORY_IGNORE_DUPS,LineReader.Option.HISTORY_REDUCE_BLANKS,
                    LineReader.Option.DISABLE_EVENT_EXPANSION,LineReader.Option.AUTO_LIST,LineReader.Option.AUTO_MENU})
                assertTrue(reader.isSet(option),option.toString());
            reader.getHistory().attach(reader);
            reader.getHistory().add(Instant.now(),"safe task");
            reader.getHistory().add(Instant.now(),"token=fixture-secret");
            reader.getHistory().add(Instant.now(),"@image:data:image/png;base64,"+"A".repeat(300));
            reader.getHistory().save();
            var next = InteractiveReader.create(terminal,false,settings);
            next.getHistory().attach(next);
            assertEquals(1,next.getHistory().size());
            assertEquals("safe task",next.getHistory().get(next.getHistory().first()));
            String persisted = Files.readString(settings.file());
            assertTrue(persisted.contains("safe task")); assertFalse(persisted.contains("fixture-secret"));
            var highlighted = ((LineReaderImpl)next).getHighlighter().highlight(next,"/help @file");
            assertEquals("/help @file",highlighted.toString());
            assertNotEquals(highlighted.styleAt(5),highlighted.styleAt(0));
            assertNotEquals(highlighted.styleAt(5),highlighted.styleAt(6));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void productionReaderRetainsAutomaticPairInsertionWhileEditing() throws Exception {
        var input = new java.io.PipedInputStream(65536);
        try (var keys = new java.io.PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).type("xterm-256color")
                     .streams(input,new ByteArrayOutputStream()).build()) {
            var settings = ConfigLoader.history(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
            var reader = InteractiveReader.create(terminal,false,settings);
            var result = new java.util.concurrent.CompletableFuture<String>();
            var thread = Thread.ofVirtual().start(() -> {
                try { result.complete(reader.readLine()); } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            keys.write("(\005\r".getBytes(java.nio.charset.StandardCharsets.UTF_8)); keys.flush();
            assertEquals("()",result.get(5,java.util.concurrent.TimeUnit.SECONDS));
            thread.join(1000); assertFalse(thread.isAlive());
            assertTrue(Files.readString(settings.file()).contains("()"),"accepting a real line persists it without an explicit save call");
            for (String sensitive : new String[]{"token=fixture-secret", "@image:data:image/png;base64,"+"A".repeat(300)}) {
                var accepted = new java.util.concurrent.CompletableFuture<String>();
                var inputOwner = Thread.ofVirtual().start(() -> {
                    try { accepted.complete(reader.readLine()); }
                    catch (Throwable failure) { accepted.completeExceptionally(failure); }
                });
                keys.write((sensitive+"\r").getBytes(java.nio.charset.StandardCharsets.UTF_8)); keys.flush();
                assertEquals(sensitive,accepted.get(5,java.util.concurrent.TimeUnit.SECONDS));
                inputOwner.join(1000); assertFalse(inputOwner.isAlive());
            }
            var reopened = InteractiveReader.create(terminal,false,settings);
            reopened.getHistory().attach(reopened);
            assertEquals(1,reopened.getHistory().size());
            assertEquals("()",reopened.getHistory().get(reopened.getHistory().first()));
            assertFalse(Files.readString(settings.file()).contains("fixture-secret"));
            assertFalse(Files.readString(settings.file()).contains("data:image"));
        }
    }

    @Test void explicitHistoryPathAndLimitsUseCapturedSourcesWithModernAndLegacyPrecedence() throws Exception {
        Files.createDirectory(root.resolve("history-dir"));
        var settings = ConfigLoader.history(new ConfigSources(Map.of("cafecli.history.file", "history-dir",
                "paicli.history.file", "wrong", "cafecli.history.size", "-1"),
                Map.of("CAFECLI_HISTORY_FILE", "wrong-env", "PAICLI_HISTORY_FILE_SIZE", "31"), root.resolve("home"),root));
        assertEquals(root.resolve("history-dir/input.history"), settings.file());
        assertEquals(2000,settings.size()); assertEquals(31,settings.fileSize());
    }
}
