package com.cafecli.render.inline;

import com.cafecli.render.StatusInfo;
import com.cafecli.render.inline.replay.ReplayTrace;
import com.cafecli.render.inline.replay.TerminalReplayRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererReplayRecordingTest {

    @TempDir
    Path tempDir;

    @Test
    void directTranscriptWriteIsPresentInTheReplayTrace() throws Exception {
        Path traceFile = tempDir.resolve("direct.jsonl");
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 80, 24, List.of());
             InlineRenderer renderer = new InlineRenderer(
                     null,
                     new PrintStream(terminalBytes, false, StandardCharsets.UTF_8),
                     new ManualFramePump(),
                     new ManualFrameTicker(),
                     null,
                     recorder)) {
            renderer.stream().println("transcript 雪");
        }

        ReplayTrace trace = ReplayTrace.read(traceFile);
        ReplayTrace.WriteEvent write = (ReplayTrace.WriteEvent) trace.events().getFirst();
        assertArrayEquals("transcript 雪\n".getBytes(StandardCharsets.UTF_8), write.bytes());
    }

    @Test
    void attemptedLiveFrameRetainsItsJavaBoundaryBytesWhenTerminalFlushFails() throws Exception {
        Path traceFile = tempDir.resolve("failed-frame.jsonl");
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();
        PrintWriter terminalWriter = new PrintWriter(
                new OutputStreamWriter(terminalBytes, StandardCharsets.UTF_8), false);
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.writer()).thenReturn(terminalWriter);
        Mockito.doThrow(new IllegalStateException("flush failed")).when(terminal).flush();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 80, 24, List.of())) {
            TerminalFrameWriter writer = new TerminalFrameWriter(
                    terminal, new PrintStream(terminalBytes), recorder);
            writer.write("live frame");
            assertThrows(IllegalStateException.class, writer::flush);
        }

        ReplayTrace.WriteEvent write = (ReplayTrace.WriteEvent) ReplayTrace.read(traceFile).events().getFirst();
        assertArrayEquals("live frame".getBytes(StandardCharsets.UTF_8), write.bytes());
    }

    @Test
    void writeKeepsItsBoundaryPositionWhenWinchArrivesDuringTheFollowingFlush() throws Exception {
        Path traceFile = tempDir.resolve("write-resize-order.jsonl");
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();
        PrintWriter terminalWriter = new PrintWriter(
                new OutputStreamWriter(terminalBytes, StandardCharsets.UTF_8), false);
        AtomicReference<TerminalReplayRecorder> activeRecorder = new AtomicReference<>();
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.writer()).thenReturn(terminalWriter);
        Mockito.doAnswer(ignored -> {
            activeRecorder.get().recordResizeSignal(1);
            terminalWriter.flush();
            return null;
        }).when(terminal).flush();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 80, 24, List.of())) {
            activeRecorder.set(recorder);
            TerminalFrameWriter writer = new TerminalFrameWriter(
                    terminal, new PrintStream(terminalBytes), recorder);
            writer.write("live frame");
            writer.flush();
        }

        ReplayTrace trace = ReplayTrace.read(traceFile);
        assertEquals(2, trace.events().size());
        assertTrue(trace.events().get(0) instanceof ReplayTrace.WriteEvent,
                "the Java write boundary precedes a signal delivered during its flush");
        assertTrue(trace.events().get(1) instanceof ReplayTrace.ResizeEvent);
    }

    @Test
    void coalescedWinchStaysUnresolvedWhileTheObservedGenerationKeepsItsOwnSize() throws Exception {
        Path traceFile = tempDir.resolve("resize.jsonl");
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();
        PrintWriter terminalWriter = new PrintWriter(
                new OutputStreamWriter(terminalBytes, StandardCharsets.UTF_8), false);
        AtomicReference<Size> size = new AtomicReference<>(new Size(100, 30));
        AtomicReference<Terminal.SignalHandler> winch = new AtomicReference<>();
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm");
        Mockito.when(terminal.getSize()).thenAnswer(ignored -> size.get());
        Mockito.when(terminal.getBufferSize()).thenAnswer(ignored -> size.get());
        Mockito.when(terminal.writer()).thenReturn(terminalWriter);
        Mockito.doAnswer(ignored -> {
            terminalWriter.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                .thenAnswer(invocation -> {
                    winch.set(invocation.getArgument(1));
                    return Terminal.SignalHandler.SIG_DFL;
                });
        ManualFramePump pump = new ManualFramePump();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 100, 30, List.of());
             InlineRenderer renderer = new InlineRenderer(
                     terminal,
                     new PrintStream(terminalBytes, false, StandardCharsets.UTF_8),
                     pump,
                     new ManualFrameTicker(),
                     new BottomStatusBar(),
                     recorder)) {
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("model", 100_000, false));
            pump.pump();

            size.set(new Size(90, 40));
            winch.get().handle(Terminal.Signal.WINCH);
            size.set(new Size(88, 44));
            winch.get().handle(Terminal.Signal.WINCH);
            pump.pump();
        }

        ReplayTrace trace = ReplayTrace.read(traceFile);
        List<ReplayTrace.ResizeEvent> resizes = trace.events().stream()
                .filter(ReplayTrace.ResizeEvent.class::isInstance)
                .map(ReplayTrace.ResizeEvent.class::cast)
                .toList();
        assertEquals(2, resizes.size());
        assertEquals(1, resizes.get(0).generation());
        assertFalse(resizes.get(0).resolved(), "a skipped generation must never borrow a later size");
        assertEquals(2, resizes.get(1).generation());
        assertEquals(44, resizes.get(1).rows());
        assertEquals(88, resizes.get(1).columns());
        assertFalse(trace.isComplete());

        ByteArrayOutputStream recordedFrames = new ByteArrayOutputStream();
        for (ReplayTrace.Event event : trace.events()) {
            if (event instanceof ReplayTrace.WriteEvent write) {
                recordedFrames.write(write.bytes());
            }
        }
        assertArrayEquals(terminalBytes.toByteArray(), recordedFrames.toByteArray(),
                "every completed render-loop flush must be represented exactly once");
    }

    @Test
    void replayCaptureQuiescencePreservesTheDisplayedLiveFrameAndRejectsLaterOutput() throws Exception {
        Path traceFile = tempDir.resolve("quiesced-endpoint.jsonl");
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();
        PrintWriter terminalWriter = new PrintWriter(
                new OutputStreamWriter(terminalBytes, StandardCharsets.UTF_8), false);
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm");
        Mockito.when(terminal.getSize()).thenReturn(new Size(100, 30));
        Mockito.when(terminal.getBufferSize()).thenReturn(new Size(100, 30));
        Mockito.when(terminal.writer()).thenReturn(terminalWriter);
        Mockito.doAnswer(ignored -> {
            terminalWriter.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                .thenReturn(Terminal.SignalHandler.SIG_DFL);
        ManualFramePump pump = new ManualFramePump();
        ManualFrameTicker ticker = new ManualFrameTicker();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 100, 30, List.of());
             InlineRenderer renderer = new InlineRenderer(
                     terminal,
                     new PrintStream(terminalBytes, false, StandardCharsets.UTF_8),
                     pump,
                     ticker,
                     new BottomStatusBar(),
                     recorder)) {
            renderer.start();
            renderer.updateStatus(StatusInfo.active("model", 100_000, false, "react"));
            renderer.beginThinking("Thinking");
            renderer.appendThinking("visible reasoning\n");
            pump.pump();

            byte[] displayedFrame = terminalBytes.toByteArray();
            assertTrue(new String(displayedFrame, StandardCharsets.UTF_8).contains("esc to cancel"),
                    "the capture boundary must retain a representative active live frame");

            renderer.quiesceForReplayCapture();
            assertArrayEquals(displayedFrame, terminalBytes.toByteArray(),
                    "quiescence must not clear or replace the frame Terminal.app will expose");

            ticker.tick();
            renderer.appendThinking("must not render\n");
            renderer.updateStatus(StatusInfo.idle("other-model", 100_000, false));
            renderer.stream().println("must not bypass the stopped render loop");
            pump.pump();

            assertArrayEquals(displayedFrame, terminalBytes.toByteArray(),
                    "no renderer producer or direct stream may emit after capture quiescence");
        }

        ByteArrayOutputStream recordedWrites = new ByteArrayOutputStream();
        for (ReplayTrace.Event event : ReplayTrace.read(traceFile).events()) {
            if (event instanceof ReplayTrace.WriteEvent write) {
                recordedWrites.write(write.bytes());
            }
        }
        assertArrayEquals(terminalBytes.toByteArray(), recordedWrites.toByteArray(),
                "the persisted endpoint must contain every and only submitted terminal byte");
    }

    @Test
    void captureReadinessIsPublishedOnlyAfterTheQuiescedTraceCanBeRead() throws Exception {
        Path traceFile = tempDir.resolve("endpoint.jsonl");
        Path readyFile = tempDir.resolve("endpoint.ready");
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 80, 24, List.of());
             InlineRenderer renderer = new InlineRenderer(
                     null,
                     new PrintStream(terminalBytes, false, StandardCharsets.UTF_8),
                     new ManualFramePump(),
                     new ManualFrameTicker(),
                     null,
                     recorder)) {
            renderer.stream().println("last submitted bytes");

            RenderReproDriver.completeReplayCapture(renderer, recorder, readyFile);

            assertEquals("ready\n", Files.readString(readyFile, StandardCharsets.UTF_8));
            ReplayTrace trace = ReplayTrace.read(traceFile).requireComplete();
            assertArrayEquals(terminalBytes.toByteArray(),
                    ((ReplayTrace.WriteEvent) trace.events().getFirst()).bytes());
            renderer.stream().println("too late");
            assertArrayEquals("last submitted bytes\n".getBytes(StandardCharsets.UTF_8),
                    terminalBytes.toByteArray());
        }
    }

    @Test
    void captureRequestWaitIgnoresUnrelatedDirectoryEventsForItsFullTimeout() throws Exception {
        Path requestFile = tempDir.resolve("capture.request");
        try (RenderReproDriver.CaptureRequestWatcher watcher =
                     new RenderReproDriver.CaptureRequestWatcher(requestFile)) {
            Files.writeString(tempDir.resolve("unrelated.tmp"), "noise", StandardCharsets.UTF_8);
            Thread.sleep(100);
            long started = System.nanoTime();

            assertFalse(watcher.await(300));

            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
            assertTrue(elapsedMillis >= 250,
                    "unrelated filesystem traffic must not accelerate the measured thought cadence");
        }
    }

}
