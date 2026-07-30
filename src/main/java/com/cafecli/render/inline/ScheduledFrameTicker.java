package com.cafecli.render.inline;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Production 250ms spinner event source shared by the inline renderer. */
final class ScheduledFrameTicker implements FrameTicker {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cafecli-render-ticker");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;

    @Override
    public synchronized Cancellable start(Runnable tick) {
        if (closed || tick == null) {
            return () -> { };
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(tick, 250, 250, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
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
