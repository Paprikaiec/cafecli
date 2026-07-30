package com.cafecli.render.inline;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Production leading/trailing frame scheduler with an approximately 33ms cadence. */
final class ScheduledFramePump implements FramePump {

    static final long DEFAULT_FRAME_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(33);

    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final long intervalNanos;
    private long lastFrameNanos = Long.MIN_VALUE;
    private boolean closed;

    ScheduledFramePump() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cafecli-frame-pump");
            thread.setDaemon(true);
            return thread;
        }), System::nanoTime, DEFAULT_FRAME_INTERVAL_NANOS);
    }

    ScheduledFramePump(ScheduledExecutorService scheduler, LongSupplier nanoTime, long intervalNanos) {
        this.scheduler = scheduler;
        this.nanoTime = nanoTime;
        this.intervalNanos = Math.max(0, intervalNanos);
    }

    @Override
    public synchronized void request(Runnable frame) {
        if (closed || frame == null) {
            return;
        }
        long now = nanoTime.getAsLong();
        long delay = lastFrameNanos == Long.MIN_VALUE
                ? 0
                : Math.max(0, intervalNanos - Math.max(0, now - lastFrameNanos));
        scheduler.schedule(() -> runFrame(frame), delay, TimeUnit.NANOSECONDS);
    }

    private void runFrame(Runnable frame) {
        synchronized (this) {
            if (closed) {
                return;
            }
            lastFrameNanos = nanoTime.getAsLong();
        }
        frame.run();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdownNow();
    }
}
