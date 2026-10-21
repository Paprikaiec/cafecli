package com.cafecli.harness.permission;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.ToolPreflight;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.AgentSession;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.tool.MutatingTools;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Session approval policy. Guards remain inside the leaf tool, including after an approved argument edit. */
public final class PermissionCapability implements Capability {
    private final Approver approver;
    private final List<Consumer<? super PermissionEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Object gate = new Object();
    private final Set<String> tools = new HashSet<>();
    private final Set<String> servers = new HashSet<>();
    private volatile boolean enabled;
    private boolean occupied;
    private long generation;

    public PermissionCapability(Approver approver, boolean enabled) {
        this.approver = Objects.requireNonNull(approver, "approver");
        this.enabled = enabled;
    }

    /** Synchronous on the requesting loop thread; listeners only enqueue. */
    public AgentSession.Subscription subscribe(Consumer<? super PermissionEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        Consumer<PermissionEvent> entry = listener::accept;
        listeners.add(entry);
        return () -> listeners.remove(entry);
    }

    private void emit(PermissionEvent event) {
        for (var listener : listeners) listener.accept(event);
    }

    /** Disabling mirrors v1's toggle: previously remembered grants are forgotten. */
    public void setEnabled(boolean enabled) {
        synchronized (gate) {
            this.enabled = enabled;
            if (!enabled) {
                generation++;
                tools.clear();
                servers.clear();
            }
        }
    }
    public boolean isEnabled() { return enabled; }

    @Override public void attachAgent(AgentScope agent) {
        agent.beforeToolCall((call, cancellation) -> preflight(agent.agentId(), call, cancellation));
    }

    private ToolPreflight preflight(String agentId, ToolCall call, CancellationToken cancellation) {
        if (!enabled || !requiresApproval(call.name())) return ToolPreflight.allow(call);
        synchronized (gate) {
            if (!cancellation.isCancelled() && remembered(call.name())) return ToolPreflight.allow(call);
        }
        long start = System.nanoTime();
        long approvalGeneration = -1;
        try (var ignored = cancellation.onCancel(() -> { synchronized (gate) { gate.notifyAll(); } })) {
            boolean cancelled;
            synchronized (gate) {
                while (occupied && !cancellation.isCancelled()) gate.wait();
                cancelled = cancellation.isCancelled();
                if (!cancelled) {
                    if (!enabled || remembered(call.name())) return ToolPreflight.allow(call);
                    occupied = true;
                    approvalGeneration = generation;
                }
            }
            if (cancelled) return decide(agentId, call, ApprovalResult.reject("用户取消了此次工具调用"), start, approvalGeneration);
            try {
                emit(new PermissionEvent.Requested(agentId, call));
                ApprovalResult reply = approver.approve(new ApprovalRequest(agentId, call), cancellation);
                if (cancellation.isCancelled()) reply = ApprovalResult.reject("用户取消了此次工具调用");
                return decide(agentId, call, reply, start, approvalGeneration);
            } finally {
                synchronized (gate) { occupied = false; gate.notifyAll(); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return decide(agentId, call, ApprovalResult.reject("用户取消了此次工具调用"), start, approvalGeneration);
        }
    }

    private ToolPreflight decide(String agentId, ToolCall call, ApprovalResult reply, long start, long approvalGeneration) {
        String reason = reply.reason();
        if (reply.decision() == ApprovalResult.Decision.REJECTED && (reason == null || reason.isBlank())) {
            reply = ApprovalResult.reject("用户拒绝了此操作");
        }
        ToolCall effective = new ToolCall(call.id(), call.name(), reply.effectiveArguments(call.arguments()));
        synchronized (gate) {
            if (enabled && generation == approvalGeneration && reply.decision() == ApprovalResult.Decision.APPROVED_ALL) {
                tools.add(call.name());
            }
            if (enabled && generation == approvalGeneration && reply.decision() == ApprovalResult.Decision.APPROVED_ALL_BY_SERVER) {
                String server = server(call.name());
                if (server != null) servers.add(server);
            }
        }
        emit(new PermissionEvent.Decided(agentId, call, effective, reply,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
        return switch (reply.decision()) {
            case REJECTED -> denied(call, reply.reason());
            case SKIPPED -> ToolPreflight.complete(call, new ToolResult(
                    List.of(new TextBlock("[HITL] 操作已被跳过")), null, ToolResult.Status.DENIED));
            default -> ToolPreflight.allow(effective);
        };
    }

    private boolean remembered(String tool) { return tools.contains(tool) || servers.contains(server(tool)); }
    public static boolean requiresApproval(String tool) {
        return MutatingTools.NAMES.contains(tool) || McpToolDescriptor.isNamespaced(tool);
    }
    private static String server(String tool) {
        if (!McpToolDescriptor.isNamespaced(tool)) return null;
        String[] parts = tool.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
    private static ToolPreflight denied(ToolCall call, String reason) {
        return ToolPreflight.complete(call, new ToolResult(List.of(new TextBlock("[HITL] 操作已被拒绝：" + reason)),
                null, ToolResult.Status.DENIED));
    }
}
