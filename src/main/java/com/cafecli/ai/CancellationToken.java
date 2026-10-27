package com.cafecli.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Cancels a model request from another thread. {@code ai}'s own token: callers above {@code ai} bridge theirs to it.
 * Thread-safe; cancelling is idempotent and cannot be undone.
 *
 * <p>A stream checks the token between events by itself. A provider that blocks — on a socket read, a backoff wait —
 * registers {@link #onCancel} to be woken (e.g. by aborting its HTTP call) and closes the registration when it is done.
 */
public final class CancellationToken {

    /** Undoes an {@link #onCancel} registration. Idempotent. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean cancelled;

    /** Cancels, then runs every registered listener once, on this thread. */
    public void cancel() {
        List<Listener> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(listeners);
            listeners.clear();
        }
        RuntimeException failure = null;
        for (Listener listener : toRun) {
            try {
                listener.action.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Runs {@code action} when the token is cancelled — right away, on this thread, if it already is. The action
     * must be quick and must not block: it runs on the cancelling thread.
     */
    public Registration onCancel(Runnable action) {
        Listener listener = new Listener(action);
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return listener;
            }
        }
        action.run();
        return listener;
    }

    private final class Listener implements Registration {
        private final Runnable action;

        private Listener(Runnable action) {
            this.action = action;
        }

        @Override
        public void close() {
            synchronized (CancellationToken.this) {
                listeners.remove(this);
            }
        }
    }
}
