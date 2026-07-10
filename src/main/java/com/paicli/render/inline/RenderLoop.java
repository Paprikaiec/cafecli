package com.paicli.render.inline;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** MPSC event queue whose frames are written exclusively by one render thread. */
final class RenderLoop implements AutoCloseable {

    interface FrameWriter {
        void write(String frame);

        void flush();
    }

    private final FramePump pump;
    private final Supplier<String> frameRenderer;
    private final FrameWriter writer;
    private final boolean synchronizedOutput;
    private final LinkedBlockingQueue<RenderEvent> events = new LinkedBlockingQueue<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean frameRequested = new AtomicBoolean();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong flushes = new AtomicLong();
    private final AtomicLong mergedEvents = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong reasoningCharsProcessed = new AtomicLong();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "paicli-render-loop");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    RenderLoop(FramePump pump,
               Supplier<String> frameRenderer,
               FrameWriter writer,
               boolean synchronizedOutput) {
        this.pump = pump;
        this.frameRenderer = frameRenderer;
        this.writer = writer;
        this.synchronizedOutput = synchronizedOutput;
    }

    void post(Runnable event) {
        if (event == null || closed) {
            return;
        }
        events.add(new RenderEvent(event, 0));
        requestFrame();
    }

    void postReasoning(int sourceChars, Runnable event) {
        if (event == null || closed) {
            return;
        }
        events.add(new RenderEvent(event, Math.max(0, sourceChars)));
        requestFrame();
    }

    RenderStats stats() {
        return new RenderStats(frames.get(), writes.get(), bytes.get(), flushes.get(),
                mergedEvents.get(), cacheHits.get(), reasoningCharsProcessed.get());
    }

    void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    void resetStats() {
        frames.set(0);
        writes.set(0);
        bytes.set(0);
        flushes.set(0);
        mergedEvents.set(0);
        cacheHits.set(0);
        reasoningCharsProcessed.set(0);
    }

    boolean isTerminated() {
        return renderExecutor.isTerminated();
    }

    private void requestFrame() {
        if (frameRequested.compareAndSet(false, true)) {
            pump.request(this::dispatchFrame);
        }
    }

    private void dispatchFrame() {
        try {
            Future<?> rendered;
            synchronized (lifecycleLock) {
                if (renderExecutor.isShutdown()) {
                    return;
                }
                rendered = renderExecutor.submit(this::renderFrame);
            }
            rendered.get();
        } catch (Exception e) {
            throw new IllegalStateException("Render frame failed", e);
        } finally {
            frameRequested.set(false);
            if (!events.isEmpty() && !closed) {
                requestFrame();
            }
        }
    }

    private void renderFrame() {
        List<RenderEvent> drained = new ArrayList<>();
        events.drainTo(drained);
        if (drained.isEmpty()) {
            return;
        }
        frames.incrementAndGet();
        mergedEvents.addAndGet(Math.max(0, drained.size() - 1L));
        for (RenderEvent event : drained) {
            event.mutation().run();
            reasoningCharsProcessed.addAndGet(event.reasoningChars());
        }
        String frame = frameRenderer.get();
        if (frame == null || frame.isEmpty()) {
            return;
        }
        String payload = synchronizedOutput
                ? AnsiSeq.BEGIN_SYNCHRONIZED_UPDATE + frame + AnsiSeq.END_SYNCHRONIZED_UPDATE
                : frame;
        writes.incrementAndGet();
        bytes.addAndGet(payload.getBytes(StandardCharsets.UTF_8).length);
        writer.write(payload);
        writer.flush();
        flushes.incrementAndGet();
    }

    private record RenderEvent(Runnable mutation, int reasoningChars) {
    }

    @Override
    public void close() {
        Future<?> finalFrame = null;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            pump.close();
            if (!events.isEmpty()) {
                finalFrame = renderExecutor.submit(this::renderFrame);
            }
            renderExecutor.shutdown();
        }
        try {
            if (finalFrame != null) {
                finalFrame.get();
            }
            if (!renderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                renderExecutor.shutdownNow();
                renderExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("Final render frame failed", e);
        }
    }
}
