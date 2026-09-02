package com.cafecli.render.inline;

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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

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
    /** Held across compose+write, so a modal can take the terminal between two frames and not inside one. */
    private final Object frameLock = new Object();
    private final AtomicBoolean frameRequested = new AtomicBoolean();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong flushes = new AtomicLong();
    private final AtomicLong mergedEvents = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong reasoningCharsProcessed = new AtomicLong();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cafecli-render-loop");
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

    boolean post(Runnable event) {
        if (event == null) {
            return false;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            events.add(new RenderEvent(() -> {
                event.run();
                return 0;
            }));
        }
        requestFrame();
        return true;
    }

    boolean postReasoning(String sourceDelta, ToIntFunction<String> event) {
        if (sourceDelta == null || event == null) {
            return false;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            events.add(new RenderEvent(() -> Math.max(0, event.applyAsInt(sourceDelta))));
        }
        requestFrame();
        return true;
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
        // A failed CAS means a frame is already on its way; the event it merges into is enough.
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

    /**
     * Run {@code action} at a frame boundary — never while a frame is being composed or written.
     *
     * <p>This is how a modal takes and returns the terminal (ADR 0010 amendment A12). Composing a
     * frame and writing it are one critical section here, so a frame that had already begun finishes
     * before the modal draws, and one composed before the lease cannot be written after it. The action
     * itself only flips a flag, so the render thread is never held for longer than that.
     */
    void atFrameBoundary(Runnable action) {
        synchronized (frameLock) {
            action.run();
        }
    }

    private void renderFrame() {
        synchronized (frameLock) {
            composeAndWrite();
        }
    }

    private void composeAndWrite() {
        List<RenderEvent> drained = new ArrayList<>();
        events.drainTo(drained);
        if (drained.isEmpty()) {
            return;
        }
        frames.incrementAndGet();
        mergedEvents.addAndGet(Math.max(0, drained.size() - 1L));
        for (RenderEvent event : drained) {
            reasoningCharsProcessed.addAndGet(event.mutation().getAsInt());
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

    private record RenderEvent(IntSupplier mutation) {
    }

    /**
     * Reject queued/future producer events and wait for any write already in flight, without rendering
     * a final clearing frame. Once this method returns the writer can no longer be invoked.
     */
    void quiescePreservingFrame() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            pump.close();
            events.clear();
            renderExecutor.shutdown();
        }

        boolean interrupted = false;
        while (!renderExecutor.isTerminated()) {
            try {
                if (!renderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    renderExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                interrupted = true;
                renderExecutor.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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
