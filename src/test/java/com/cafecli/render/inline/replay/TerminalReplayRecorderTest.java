package com.cafecli.render.inline.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalReplayRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    void closePersistsAnExactUtf8WriteAndTerminalOutputModeAsVersionedJsonl() throws Exception {
        Path traceFile = tempDir.resolve("session.jsonl");

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm-256color", 120, 42, List.of("ONLCR", "OPOST"))) {
            recorder.recordWrite("first\n雪\u001b[2K");
            assertFalse(Files.exists(traceFile), "the recording hot path must stay in memory");
        }

        ReplayTrace trace = ReplayTrace.read(traceFile);
        assertEquals(new ReplayTrace.Header(
                        1,
                        "xterm-256color",
                        120,
                        42,
                        List.of("ONLCR", "OPOST"),
                        ReplayTrace.LineFeedMode.CRLF),
                trace.header());
        ReplayTrace.WriteEvent write = (ReplayTrace.WriteEvent) trace.events().getFirst();
        assertEquals(0, write.sequence());
        assertArrayEquals("first\n雪\u001b[2K".getBytes(StandardCharsets.UTF_8), write.bytes());

        String jsonl = Files.readString(traceFile, StandardCharsets.UTF_8);
        assertFalse(jsonl.contains("雪"), "write bytes must be Base64 only in the serialized trace");
    }

    @Test
    void observedResizeKeepsItsOriginalSignalPositionBetweenWrites() throws Exception {
        Path traceFile = tempDir.resolve("ordered.jsonl");

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 100, 30, List.of())) {
            recorder.recordWrite("before");
            recorder.recordResizeSignal(7);
            recorder.recordWrite("after");
            recorder.observeResize(7, 44, 88);
        }

        ReplayTrace trace = ReplayTrace.read(traceFile).requireComplete();
        assertEquals(3, trace.events().size());
        ReplayTrace.ResizeEvent resize = (ReplayTrace.ResizeEvent) trace.events().get(1);
        assertEquals(1, resize.sequence());
        assertEquals(7, resize.generation());
        assertEquals(44, resize.rows());
        assertEquals(88, resize.columns());
        assertTrue(trace.events().get(0).elapsedNanos() <= resize.elapsedNanos());
        assertTrue(resize.elapsedNanos() <= trace.events().get(2).elapsedNanos());
    }

    @Test
    void resizeObservationArrivingBeforeSignalRegistrationIsRetained() throws Exception {
        Path traceFile = tempDir.resolve("observe-before-register.jsonl");

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 100, 30, List.of())) {
            recorder.observeResize(7, 44, 88);
            recorder.recordWrite("before registration");
            recorder.recordResizeSignal(7);
            recorder.recordWrite("after registration");
        }

        ReplayTrace trace = ReplayTrace.read(traceFile).requireComplete();
        assertEquals(3, trace.events().size());
        assertEquals("before registration",
                ((ReplayTrace.WriteEvent) trace.events().get(0)).utf8Text());
        ReplayTrace.ResizeEvent resize = (ReplayTrace.ResizeEvent) trace.events().get(1);
        assertEquals(7, resize.generation());
        assertEquals(44, resize.rows());
        assertEquals(88, resize.columns());
        assertEquals("after registration",
                ((ReplayTrace.WriteEvent) trace.events().get(2)).utf8Text());
    }

    @Test
    void earlyResizeObservationCacheEvictsTheOldestAfter256UnseenGenerations() throws Exception {
        Path traceFile = tempDir.resolve("bounded-early-observations.jsonl");

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 100, 30, List.of())) {
            for (int generation = 0; generation <= 256; generation++) {
                recorder.observeResize(generation, 30, 80 + generation);
            }
            recorder.recordResizeSignal(0);
            recorder.recordResizeSignal(256);
        }

        ReplayTrace trace = ReplayTrace.read(traceFile);
        assertEquals(2, trace.events().size());
        ReplayTrace.ResizeEvent evictedOldest = (ReplayTrace.ResizeEvent) trace.events().get(0);
        assertFalse(evictedOldest.resolved());
        ReplayTrace.ResizeEvent retainedNewest = (ReplayTrace.ResizeEvent) trace.events().get(1);
        assertTrue(retainedNewest.resolved());
        assertEquals(30, retainedNewest.rows());
        assertEquals(336, retainedNewest.columns());
    }

    @Test
    void unresolvedResizeRemainsVisibleAndCannotBeReplayedAsComplete() throws Exception {
        Path traceFile = tempDir.resolve("incomplete.jsonl");

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                traceFile, "xterm", 100, 30, List.of())) {
            recorder.recordResizeSignal(11);
        }

        ReplayTrace trace = ReplayTrace.read(traceFile);
        ReplayTrace.ResizeEvent resize = (ReplayTrace.ResizeEvent) trace.events().getFirst();
        assertFalse(resize.resolved());
        assertFalse(trace.isComplete());
        IllegalStateException error = assertThrows(IllegalStateException.class, trace::requireComplete);
        assertTrue(error.getMessage().contains("11"));
    }

    @Test
    void signalTimeNativeSampleResolvesEveryGenerationBeforeRenderCoalescing() throws Exception {
        Path traceFile = tempDir.resolve("native-resize.jsonl");

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.createForTest(
                traceFile,
                "xterm-256color",
                170,
                44,
                List.of("ONLCR", "OPOST"),
                () -> TerminalReplayRecorder.packSize(57, 189))) {
            recorder.recordResizeSignal(1);
            recorder.recordResizeSignal(2);
            // A later render-frame observation must not overwrite either signal-time sample.
            recorder.observeResize(2, 44, 170);
        }

        ReplayTrace trace = ReplayTrace.read(traceFile).requireComplete();
        List<ReplayTrace.ResizeEvent> resizes = trace.events().stream()
                .map(ReplayTrace.ResizeEvent.class::cast)
                .toList();
        assertEquals(2, resizes.size());
        assertTrue(resizes.stream().allMatch(resize -> resize.rows() == 57));
        assertTrue(resizes.stream().allMatch(resize -> resize.columns() == 189));
    }

    @Test
    void geometrySamplerRecordsEveryObservedTransitionInTotalWriteOrder() throws Exception {
        Path traceFile = tempDir.resolve("sampled-resizes.jsonl");
        ControlledGeometryProbe probe = new ControlledGeometryProbe();

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.createWithGeometrySamplerForTest(
                traceFile,
                "xterm-256color",
                170,
                44,
                List.of("ONLCR", "OPOST"),
                probe,
                TimeUnit.MILLISECONDS.toNanos(1))) {
            assertTrue(probe.initialGeometryObserved.await(5, TimeUnit.SECONDS));
            recorder.recordWrite("before");

            probe.geometry.set(TerminalReplayRecorder.packSize(44, 171));
            assertTrue(probe.firstTransitionObserved.await(5, TimeUnit.SECONDS));
            recorder.recordWrite("between");

            probe.geometry.set(TerminalReplayRecorder.packSize(45, 171));
            assertTrue(probe.secondTransitionObservedTwice.await(5, TimeUnit.SECONDS));
            recorder.recordWrite("after");
        }

        ReplayTrace trace = ReplayTrace.read(traceFile).requireComplete();
        assertEquals(5, trace.events().size(), "unchanged samples must not create resize events");
        assertEquals("before", ((ReplayTrace.WriteEvent) trace.events().get(0)).utf8Text());
        ReplayTrace.ResizeEvent first = (ReplayTrace.ResizeEvent) trace.events().get(1);
        assertEquals(44, first.rows());
        assertEquals(171, first.columns());
        assertEquals("between", ((ReplayTrace.WriteEvent) trace.events().get(2)).utf8Text());
        ReplayTrace.ResizeEvent second = (ReplayTrace.ResizeEvent) trace.events().get(3);
        assertEquals(45, second.rows());
        assertEquals(171, second.columns());
        assertEquals("after", ((ReplayTrace.WriteEvent) trace.events().get(4)).utf8Text());
        assertTrue(first.generation() != second.generation());
    }

    @Test
    void closeInterruptsAndJoinsTheDaemonGeometrySamplerBeforeReturning() throws Exception {
        Path traceFile = tempDir.resolve("joined-sampler.jsonl");
        JoinAwareProbe probe = new JoinAwareProbe();
        TerminalReplayRecorder recorder = TerminalReplayRecorder.createWithGeometrySamplerForTest(
                traceFile,
                "xterm-256color",
                170,
                44,
                List.of(),
                probe,
                TimeUnit.MILLISECONDS.toNanos(1));
        assertTrue(probe.entered.await(5, TimeUnit.SECONDS));

        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            recorder.close();
            closeReturned.countDown();
        });
        try {
            assertTrue(probe.interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                    "close must wait for an in-flight native observation to finish");
            assertTrue(probe.ranOnDaemonThread.get());
        } finally {
            probe.allowExit.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(closer.isAlive());
        assertEquals(0, probe.callsAfterExit.get());
        assertTrue(Files.exists(traceFile));
    }

    @Test
    void deliveredWinchMarkerAndEarlyFrameObservationCannotRollSampledGeometryBackward() throws Exception {
        Path traceFile = tempDir.resolve("signal-after-sample.jsonl");
        CountDownLatch samplerObserved = new CountDownLatch(1);
        AtomicReference<Thread> samplerThread = new AtomicReference<>();
        LongSupplier probe = () -> {
            samplerThread.compareAndSet(null, Thread.currentThread());
            if (Thread.currentThread() == samplerThread.get()) {
                samplerObserved.countDown();
                return TerminalReplayRecorder.packSize(44, 171);
            }
            // If a delivered-WINCH marker queried independently, this later geometry would be
            // assigned to the earlier marker and replay could then roll back to the sampled 171.
            return TerminalReplayRecorder.packSize(44, 172);
        };

        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.createWithGeometrySamplerForTest(
                traceFile,
                "xterm-256color",
                170,
                44,
                List.of(),
                probe,
                TimeUnit.SECONDS.toNanos(1))) {
            assertTrue(samplerObserved.await(5, TimeUnit.SECONDS));
            recorder.observeResize(7, 99, 199);
            recorder.recordResizeSignal(7);
        }

        List<ReplayTrace.ResizeEvent> resizes = ReplayTrace.read(traceFile).requireComplete().events().stream()
                .filter(ReplayTrace.ResizeEvent.class::isInstance)
                .map(ReplayTrace.ResizeEvent.class::cast)
                .toList();
        assertEquals(2, resizes.size());
        assertTrue(resizes.stream().allMatch(resize -> resize.rows() == 44));
        assertTrue(resizes.stream().allMatch(resize -> resize.columns() == 171));
    }

    @Test
    void disabledRecorderShortCircuitsWithoutValidationOrFilesystemWork() {
        TerminalReplayRecorder recorder = TerminalReplayRecorder.disabled();

        recorder.recordWrite("忽略");
        recorder.recordResizeSignal(-1);
        recorder.observeResize(-1, -1, -1);
        recorder.close();

        assertFalse(recorder.isEnabled());
        assertTrue(recorder.writeFailure().isEmpty());
    }

    @Test
    void terminalFactorySnapshotsInitialOutputFlags() throws Exception {
        Path traceFile = tempDir.resolve("terminal-metadata.jsonl");
        Attributes attributes = new Attributes();
        attributes.setOutputFlag(Attributes.OutputFlag.OPOST, true);
        attributes.setOutputFlag(Attributes.OutputFlag.ONLCR, true);
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(170, 44));
        Mockito.when(terminal.getAttributes()).thenReturn(attributes);

        try (TerminalReplayRecorder ignored = TerminalReplayRecorder.create(traceFile, terminal)) {
            // Header-only trace.
        }

        ReplayTrace.Header header = ReplayTrace.read(traceFile).header();
        assertEquals(List.of("ONLCR", "OPOST"), header.outputFlags());
        assertEquals(ReplayTrace.LineFeedMode.CRLF, header.lineFeedMode());
    }

    @Test
    void persistenceFailureIsReportedWithoutEscapingClose() throws Exception {
        Path nonDirectory = tempDir.resolve("ordinary-file");
        Files.writeString(nonDirectory, "occupied", StandardCharsets.UTF_8);
        TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                nonDirectory.resolve("trace.jsonl"), "xterm", 80, 24, List.of());
        recorder.recordWrite("kept in memory until close");

        assertDoesNotThrow(recorder::close);
        assertTrue(recorder.writeFailure().isPresent());
    }

    private static final class ControlledGeometryProbe implements LongSupplier {
        private final AtomicLong geometry = new AtomicLong(
                TerminalReplayRecorder.packSize(44, 170));
        private final CountDownLatch initialGeometryObserved = new CountDownLatch(1);
        private final CountDownLatch firstTransitionObserved = new CountDownLatch(1);
        private final CountDownLatch secondTransitionObservedTwice = new CountDownLatch(2);

        @Override
        public long getAsLong() {
            long current = geometry.get();
            if (current == TerminalReplayRecorder.packSize(44, 170)) {
                initialGeometryObserved.countDown();
            } else if (current == TerminalReplayRecorder.packSize(44, 171)) {
                firstTransitionObserved.countDown();
            } else if (current == TerminalReplayRecorder.packSize(45, 171)) {
                // Seeing the same geometry twice proves unchanged polls are deduplicated.
                secondTransitionObservedTwice.countDown();
            }
            return current;
        }
    }

    private static final class JoinAwareProbe implements LongSupplier {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch allowExit = new CountDownLatch(1);
        private final AtomicBoolean ranOnDaemonThread = new AtomicBoolean();
        private final AtomicBoolean exited = new AtomicBoolean();
        private final AtomicInteger callsAfterExit = new AtomicInteger();

        @Override
        public long getAsLong() {
            if (exited.get()) {
                callsAfterExit.incrementAndGet();
                return TerminalReplayRecorder.packSize(44, 170);
            }
            ranOnDaemonThread.set(Thread.currentThread().isDaemon());
            entered.countDown();
            try {
                allowExit.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                awaitUninterruptibly(allowExit);
            }
            exited.set(true);
            return TerminalReplayRecorder.packSize(44, 170);
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interruptedAgain = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException e) {
                    interruptedAgain = true;
                }
            }
            if (interruptedAgain) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
