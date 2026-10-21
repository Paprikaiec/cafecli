package com.cafecli.harness.permission;

import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.ToolResult;
import com.cafecli.core.ToolFailure;
import com.cafecli.harness.Capability;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.ToolExecution;
import com.cafecli.policy.AuditLog;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enqueues immutable observations on loop threads. One owned writer constructs entries (including sanitization),
 * tracks executions and writes files. Approval never duplicates an execution allow. The session drains it on close.
 */
public final class AuditCapability implements Capability, AutoCloseable {
    private final PermissionCapability permissions;
    private final AuditLog audit;
    /** Only the writer reads this map or constructs/sanitizes audit entries. */
    private final Map<CallKey, Started> calls = new HashMap<>();
    private final Object writerLock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cafecli-audit-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private AgentSession.Subscription permissionSubscription;

    public AuditCapability(PermissionCapability permissions, AuditLog audit) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override public void attachSession(SessionRegistrar session) {
        session.onClose(this);
        permissionSubscription = permissions.subscribe(event -> {
            if (event instanceof PermissionEvent.Decided) {
                Instant observedAt = Instant.now();
                enqueue(() -> permission(event, observedAt));
            }
        });
        session.eventListener(event -> {
            if (event instanceof AgentEvent.ToolStart || event instanceof AgentEvent.ToolEnd || event instanceof AgentEvent.RunEnd) {
                long observed = System.nanoTime();
                Instant observedAt = Instant.now();
                enqueue(() -> execution(event, observed, observedAt));
            }
        });
    }

    private void permission(PermissionEvent event, Instant observedAt) {
        if (!(event instanceof PermissionEvent.Decided decision)) return;
        String reason = switch (decision.reply().decision()) {
            case REJECTED -> decision.reply().reason();
            case SKIPPED -> "用户跳过";
            default -> null;
        };
        if (reason != null) {
            record(AuditLog.AuditEntry.denyByHitl(decision.call().name(), decision.call().arguments(), reason,
                    decision.durationMs()), observedAt);
        }
    }

    private void execution(AgentEvent event, long observedNanos, Instant observedAt) {
        if (event instanceof AgentEvent.ToolStart start && PermissionCapability.requiresApproval(start.call().name())) {
            calls.put(new CallKey(start.origin().agentId(), start.call().id()), new Started(start.call(), observedNanos));
        } else if (event instanceof AgentEvent.ToolEnd end) {
            var entry = end.result();
            Started started = calls.remove(new CallKey(end.origin().agentId(), entry.message().toolCallId()));
            if (started == null) return;
            ToolCall call = started.call();
            ToolExecution receipt = ToolExecution.from(new ToolResult(entry.message().content(), entry.details(), entry.status()));
            if (receipt == null) {
                var details = entry.details();
                if (details != null && ToolFailure.Kind.INVALID_INPUT.name().equals(details.path("failure").path("kind").asText())) {
                    record(AuditLog.AuditEntry.error(call.name(), call.arguments(),
                            details.path("failure").path("detail").asText(),
                            TimeUnit.NANOSECONDS.toMillis(observedNanos - started.atNanos())), observedAt);
                }
                return;
            }
            record(switch (receipt.kind()) {
                case RETURNED -> AuditLog.AuditEntry.allow(call.name(), call.arguments(), receipt.durationMs());
                case POLICY_DENIED -> AuditLog.AuditEntry.denyByPolicy(call.name(), call.arguments(), receipt.reason(), receipt.durationMs());
                case FAILED -> AuditLog.AuditEntry.error(call.name(), call.arguments(), receipt.reason(), receipt.durationMs());
            }, observedAt);
        } else if (event instanceof AgentEvent.RunEnd end) {
            calls.keySet().removeIf(key -> key.agentId().equals(end.origin().agentId()));
        }
    }

    private void record(AuditLog.AuditEntry entry, Instant observedAt) {
        // Factories sanitize on this worker; replace only the timestamp so queue lag cannot move the event in time.
        audit.record(new AuditLog.AuditEntry(observedAt.toString(), entry.tool(), entry.args(), entry.outcome(),
                entry.reason(), entry.approver(), entry.durationMs(), entry.metadata()));
    }

    private void enqueue(Runnable observation) {
        synchronized (writerLock) {
            writer.execute(() -> {
                try { observation.run(); }
                catch (RuntimeException e) { failure.compareAndSet(null, e); }
            });
        }
    }

    /** Waits for observations already enqueued; never called from an event listener or the loop. */
    public void flush() {
        Future<?> barrier;
        synchronized (writerLock) {
            barrier = writer.isShutdown() ? null : writer.submit(() -> {});
        }
        if (barrier == null) awaitTermination();
        else {
            boolean interrupted = false;
            try {
                for (;;) {
                    try { barrier.get(); break; }
                    catch (InterruptedException e) { interrupted = true; }
                    catch (ExecutionException e) { throw new IllegalStateException("audit writer failed", e.getCause()); }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        checkFailure();
    }

    /** Stops accepting observations and drains every accepted entry, even if the closing thread is interrupted. */
    @Override public void close() {
        synchronized (writerLock) {
            if (permissionSubscription != null) permissionSubscription.close();
            writer.shutdown();
        }
        awaitTermination();
        checkFailure();
    }

    private void awaitTermination() {
        boolean interrupted = false;
        try {
            while (!writer.isTerminated()) {
                try { writer.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void checkFailure() {
        RuntimeException problem = failure.get();
        if (problem != null) throw new IllegalStateException("audit writer failed", problem);
    }

    private record Started(ToolCall call, long atNanos) {}
    private record CallKey(String agentId, String callId) {}
}
