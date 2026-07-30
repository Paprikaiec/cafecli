package com.cafecli.render.inline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderLoopTest {

    @Test
    void manualPumpMergesProducerEventsIntoOneWriterThreadFrame() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();
        StringBuilder model = new StringBuilder();
        long producerThread = Thread.currentThread().threadId();

        try (RenderLoop loop = new RenderLoop(pump, model::toString, writer, false)) {
            loop.post(() -> model.append("first"));
            loop.post(() -> model.append(" second"));

            assertEquals(List.of(), writer.frames);
            pump.pump();

            assertEquals(List.of("first second"), writer.frames);
            assertEquals(1, writer.flushes);
            assertNotEquals(producerThread, writer.threadIds.get(0));
        }
    }

    @Test
    void statsSnapshotReportsOneMergedDirtyFrame() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();
        StringBuilder model = new StringBuilder();

        try (RenderLoop loop = new RenderLoop(pump, model::toString, writer, false)) {
            loop.post(() -> model.append("你好"));
            loop.post(() -> model.append("!"));
            pump.pump();

            assertEquals(new RenderStats(1, 1, 7, 1, 1, 0, 0), loop.stats());
        }
    }

    @Test
    void reasoningWorkCounterGrowsOnlyWithIncomingDeltas() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();
        StringBuilder model = new StringBuilder();

        try (RenderLoop loop = new RenderLoop(pump, model::toString, writer, false)) {
            for (int i = 0; i < 5_000; i++) {
                loop.postReasoning("ab", delta -> {
                    model.append(delta);
                    return delta.length();
                });
            }
            pump.pump();

            assertEquals(10_000, loop.stats().reasoningCharsProcessed());
        }
    }

    @Test
    void cacheHitCounterDoesNotCreateAFrame() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();

        try (RenderLoop loop = new RenderLoop(pump, () -> "", writer, false)) {
            loop.recordCacheHit();

            assertEquals(1, loop.stats().cacheHits());
            assertEquals(0, loop.stats().frames());
            assertEquals(List.of(), writer.frames);
        }
    }

    @Test
    void synchronizedOutputWrapsOneCompleteFrame() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();
        StringBuilder model = new StringBuilder();

        try (RenderLoop loop = new RenderLoop(pump, model::toString, writer, true)) {
            loop.post(() -> model.append("payload"));
            pump.pump();

            assertEquals(List.of(AnsiSeq.BEGIN_SYNCHRONIZED_UPDATE
                    + "payload" + AnsiSeq.END_SYNCHRONIZED_UPDATE), writer.frames);
        }
    }

    @Test
    void closeWritesTheQueuedFinalFrameWithoutManualPump() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();
        StringBuilder model = new StringBuilder("visible");
        RenderLoop loop = new RenderLoop(pump, model::toString, writer, false);

        loop.post(() -> model.setLength(0));
        loop.post(() -> model.append("cleared"));
        loop.close();

        assertEquals(List.of("cleared"), writer.frames);
        assertEquals(1, writer.flushes);
        assertTrue(loop.isTerminated());
    }

    @Test
    void closeDrainsAdmittedEventsAndRejectsLaterPosts() {
        ManualFramePump pump = new ManualFramePump();
        RecordingFrameWriter writer = new RecordingFrameWriter();
        StringBuilder model = new StringBuilder();
        CountDownLatch applied = new CountDownLatch(1);
        RenderLoop loop = new RenderLoop(pump, model::toString, writer, false);

        assertTrue(loop.post(() -> {
            model.append("admitted");
            applied.countDown();
        }));
        loop.close();

        assertEquals(0, applied.getCount());
        assertEquals(List.of("admitted"), writer.frames);
        assertFalse(loop.post(() -> model.append("too late")));
        assertEquals("admitted", model.toString());
    }

    private static final class RecordingFrameWriter implements RenderLoop.FrameWriter {
        private final List<String> frames = new ArrayList<>();
        private final List<Long> threadIds = new ArrayList<>();
        private int flushes;

        @Override
        public void write(String frame) {
            frames.add(frame);
            threadIds.add(Thread.currentThread().threadId());
        }

        @Override
        public void flush() {
            flushes++;
        }
    }
}
