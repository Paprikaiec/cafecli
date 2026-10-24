package com.cafecli.app;

import com.cafecli.core.AgentEvent;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionRegistrar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Headless presentation only: listeners enqueue; the owned writer drains before the final answer is printed. */
final class HeadlessRetryStatus implements Capability, AutoCloseable {
    private final Consumer<String> status;
    private final Object lock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual()
            .name("cafecli-retry-status-", 0).factory());
    private boolean closing;
    private volatile RuntimeException failure;

    HeadlessRetryStatus(Consumer<String> status) { this.status = status; }

    @Override public void attachSession(SessionRegistrar session) {
        session.onClose(this);
        session.eventListener(event -> {
            if (event instanceof AgentEvent.Retry retry && AgentSession.MAIN_AGENT_ID.equals(retry.agentId())) {
                synchronized (lock) {
                    if (!closing) writer.execute(() -> {
                        var attempt = retry.attempt();
                        try {
                            status.accept("Retrying model request (attempt " + attempt.attempt() + "/"
                                    + attempt.maxAttempts() + "): " + attempt.reason());
                        } catch (RuntimeException outputFailure) {
                            if (failure == null) failure = outputFailure;
                            else if (failure != outputFailure) failure.addSuppressed(outputFailure);
                        }
                    });
                }
            }
        });
    }

    @Override public void close() {
        synchronized (lock) {
            closing = true;
            writer.shutdown();
        }
        boolean interrupted = false;
        try {
            while (!writer.isTerminated()) {
                try { writer.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (failure != null) throw new IllegalStateException("retry status output failed", failure);
    }
}
