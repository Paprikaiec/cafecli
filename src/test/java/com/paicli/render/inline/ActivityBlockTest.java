package com.paicli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityBlockTest {

    @Test
    void activityEventsWaitForFrameAndWriteOnlyOnRenderThread() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThreadRecordingWriter writer = new ThreadRecordingWriter(sink);
        Terminal terminal = terminal(sink);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        long producerThread = Thread.currentThread().threadId();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                pump, new ManualFrameTicker())) {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("first delta");
            renderer.appendThinking(" second delta");

            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();

            String output = sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Thinking..."), output);
            assertTrue(output.contains("first delta second delta"), output);
            assertNotEquals(producerThread, writer.threadIds.getFirst());
        }
    }

    @Test
    void reasoningStormProcessesEachIncomingCharacterOnce() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Terminal terminal = terminal(sink);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                pump, new ManualFrameTicker())) {
            renderer.beginThinking("Thinking");
            for (int i = 0; i < 5_000; i++) {
                renderer.appendThinking("ab");
            }
            pump.pump();

            assertEquals(10_000, renderer.renderStats().reasoningCharsProcessed());
            assertEquals(1, renderer.renderStats().frames());
            assertEquals(1, renderer.renderStats().writes());
        }
    }

    @Test
    void spinnerTickIsAProducerEventThatWaitsForTheNextFrame() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Terminal terminal = terminal(sink);
        ManualFramePump pump = new ManualFramePump();
        ManualFrameTicker ticker = new ManualFrameTicker();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                pump, ticker)) {
            renderer.beginThinking("Thinking");
            pump.pump();
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains("⠋"));

            sink.reset();
            ticker.tick();
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains("⠙"));
        }
    }

    @Test
    void activityBlockContainsThinkingOnlyAndDoesNotDuplicateTheStatusDock() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Terminal terminal = terminal(sink);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                pump, new ManualFrameTicker())) {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("trying to read file");
            pump.pump();

            String output = sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Thinking... (esc to cancel,"), output);
            assertTrue(output.contains("trying to read file"), output);
            assertTrue(!output.contains("Auto Model") && !output.contains("PaiCLI"), output);
        }
    }

    private static Terminal terminal(ByteArrayOutputStream sink) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(120, 40);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(120);
        Mockito.when(terminal.getHeight()).thenReturn(40);
        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            terminal.writer().flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.encoding()).thenReturn(StandardCharsets.UTF_8);
        return terminal;
    }

    private static final class ThreadRecordingWriter extends PrintWriter {
        private final List<Long> threadIds = new ArrayList<>();

        private ThreadRecordingWriter(ByteArrayOutputStream sink) {
            super(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        }

        @Override
        public void write(String value, int offset, int length) {
            threadIds.add(Thread.currentThread().threadId());
            super.write(value, offset, length);
        }
    }
}
