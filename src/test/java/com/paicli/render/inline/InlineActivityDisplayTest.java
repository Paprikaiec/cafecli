package com.paicli.render.inline;

import com.paicli.render.StatusInfo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineActivityDisplayTest {

    @Test
    void activityEventsWaitForManualFrameAndWriteOnlyOnRenderThread() {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        ThreadRecordingWriter terminalWriter = new ThreadRecordingWriter(terminalSink);
        Terminal terminal = mockAnsiTerminal(terminalSink);
        Mockito.when(terminal.writer()).thenReturn(terminalWriter);
        ManualFramePump pump = new ManualFramePump();
        ManualFrameTicker ticker = new ManualFrameTicker();
        long producerThread = Thread.currentThread().threadId();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), pump, ticker)) {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("first delta");
            renderer.appendThinking(" second delta");

            assertEquals("", terminalSink.toString(StandardCharsets.UTF_8));
            pump.pump();

            String output = terminalSink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Thinking..."), output);
            assertTrue(output.contains("first delta second delta"), output);
            assertEquals(1, terminalWriter.flushes);
            assertNotEquals(producerThread, terminalWriter.threadIds.get(0));
        }
    }

    @Test
    void reasoningStormProcessesEachIncomingCharacterOnce() {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);
        ManualFramePump pump = new ManualFramePump();
        ManualFrameTicker ticker = new ManualFrameTicker();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), pump, ticker)) {
            renderer.beginThinking("Thinking");
            for (int i = 0; i < 5_000; i++) {
                renderer.appendThinking("ab");
            }
            pump.pump();

            RenderStats stats = renderer.renderStats();
            assertEquals(10_000, stats.reasoningCharsProcessed());
            assertEquals(1, stats.frames());
            assertEquals(1, stats.writes());
            assertEquals(1, stats.flushes());
        }
    }

    @Test
    void spinnerTickIsAProducerEventThatWaitsForTheNextFrame() {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);
        ManualFramePump pump = new ManualFramePump();
        ManualFrameTicker ticker = new ManualFrameTicker();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), pump, ticker)) {
            renderer.beginThinking("Thinking");
            pump.pump();
            assertTrue(terminalSink.toString(StandardCharsets.UTF_8).contains("⠋"));

            terminalSink.reset();
            ticker.tick();
            assertEquals("", terminalSink.toString(StandardCharsets.UTF_8));
            pump.pump();

            assertTrue(terminalSink.toString(StandardCharsets.UTF_8).contains("⠙"));
        }
    }

    @Test
    void thinkingPanelKeepsStatusOutOfLiveArea() throws Exception {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);
        BottomStatusBar statusBar = new BottomStatusBar(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        statusBar.start();
        statusBar.update(StatusInfo.tokens("glm-5.1", 200_000L, 1234L, 1234L, 567L, 0L,
                null, false, 3200L, "thinking"));

        try (InlineActivityDisplay display = new InlineActivityDisplay(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                statusBar)) {
            display.begin("Thinking");
            display.appendThinking("trying to read file");
            terminal.writer().flush();
        }

        String output = terminalSink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("PaiCLI"), "thinking panel should not duplicate status bar: " + output);
        assertFalse(output.contains("glm-5.1"), "thinking panel should not duplicate model status: " + output);
        assertFalse(output.contains("Auto Model"), "thinking panel should not duplicate footer cue: " + output);
        assertTrue(output.contains("Thinking"), "thinking panel should keep the spinner label: " + output);
        assertTrue(output.contains("Thinking... (esc to cancel,"),
                "thinking panel should keep a stable label and move the animation to the spinner: " + output);
        assertTrue(output.contains("| trying to read file") || output.contains("│ trying to read file"),
                "thinking panel should show quoted reasoning: " + output);
    }

    @Test
    void thinkingPanelOmitsStatusHeaderWhenStatusBarMissing() throws Exception {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);

        try (InlineActivityDisplay display = new InlineActivityDisplay(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))) {
            display.begin("Thinking");
            terminal.writer().flush();
        }

        String output = terminalSink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("PaiCLI"),
                "without a status bar the activity panel should not invent a status row: " + output);
        assertTrue(output.contains("Thinking"), output);
    }

    @Test
    void refreshIfActiveIsNoOpWhenIdle() throws Exception {
        ByteArrayOutputStream terminalSink = new ByteArrayOutputStream();
        Terminal terminal = mockAnsiTerminal(terminalSink);
        BottomStatusBar statusBar = new BottomStatusBar(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        statusBar.start();
        statusBar.update(StatusInfo.idle("glm-5.1", 200_000L, false));

        try (InlineActivityDisplay display = new InlineActivityDisplay(terminal,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                statusBar)) {
            // no begin() called, panel is idle
            display.refreshIfActive();
            terminal.writer().flush();
        }
        String output = terminalSink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("PaiCLI"),
                "idle activity display must not paint when status updates: " + output);
    }

    private static Terminal mockAnsiTerminal(ByteArrayOutputStream sink) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        Mockito.when(terminal.getWidth()).thenReturn(120);
        Mockito.when(terminal.getHeight()).thenReturn(40);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.encoding()).thenReturn(StandardCharsets.UTF_8);
        return terminal;
    }

    private static final class ThreadRecordingWriter extends PrintWriter {
        private final List<Long> threadIds = new ArrayList<>();
        private int flushes;

        private ThreadRecordingWriter(ByteArrayOutputStream sink) {
            super(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        }

        @Override
        public void write(String value, int offset, int length) {
            threadIds.add(Thread.currentThread().threadId());
            super.write(value, offset, length);
        }

        @Override
        public void flush() {
            flushes++;
            super.flush();
        }
    }
}
