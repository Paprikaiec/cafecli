package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-thread rendezvous shared by nested execution and cancellable run-start work. */
final class AuxiliaryTools {
    private static final ObjectMapper JSON = new ObjectMapper();

    record OwnerCall(Callable<?> action, CompletableFuture<Object> reply) {
        void run() {
            if (reply.isDone()) return;
            try { reply.complete(action.call()); }
            catch (Throwable failure) { reply.completeExceptionally(failure); }
        }
    }
    @FunctionalInterface interface Owner { Object call(Callable<?> action) throws Exception; }

    static Object await(OwnerCall call, CancellationToken token, AtomicBoolean accepting) throws Exception {
        try {
            for (;;) {
                if (token.isCancelled() || !accepting.get()) throw new InterruptedException("auxiliary invocation cancelled");
                try { return call.reply().get(20, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
            }
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        } finally {
            // A cancelled/closed worker cannot leave a live callback behind in the owner's queue.
            call.reply().cancel(false);
        }
    }

    static ScopedInvoker worker(List<AgentTool> tools, AgentCallbacks callbacks, CancellationToken token,
                              AtomicBoolean accepting, Owner owner, String parent, ToolResult cancelled) {
        List<AgentTool> snapshot = List.copyOf(tools);
        return new ScopedInvoker() {
            @Override public Optional<AgentTool> find(String name) {
                return snapshot.stream().filter(tool -> tool.name().equals(name)).findFirst();
            }
            @Override public ToolResult invoke(String name, JsonNode input) {
                requireOpen();
                if (!accepting.get()) throw new IllegalStateException("auxiliary invocation is closed");
                ToolCall original = new ToolCall("aux-" + UUID.randomUUID(), name, input.toString());
                ToolCall call = original;
                ToolResult result;
                try {
                    if (token.isCancelled() || !accepting.get()) throw new InterruptedException();
                    ToolPreflight preflight = (ToolPreflight) owner.call(() -> { requireOpen(); return callbacks.beforeToolCall(original, token); });
                    requireOpen();
                    if (token.isCancelled() || !accepting.get()) throw new InterruptedException();
                    call = preflight.call();
                    if (!original.id().equals(call.id()) || !original.name().equals(call.name())) {
                        throw new IllegalArgumentException("preflight must preserve call id and name");
                    }
                    if (preflight instanceof ToolPreflight.Complete complete) result = complete.result();
                    else {
                        AgentTool tool = find(name).orElse(null);
                        if (tool == null) result = failureOnOwner(owner, callbacks, call, ToolFailure.Kind.UNKNOWN_TOOL, null);
                        else {
                            JsonNode effective;
                            try { effective = JSON.readTree(call.arguments()); }
                            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                                effective = null;
                            }
                            if (effective == null) result = failureOnOwner(owner, callbacks, call,
                                    ToolFailure.Kind.INVALID_INPUT, "Invalid JSON");
                            else try (var nested = worker(snapshot, callbacks, token, accepting, owner, call.id(), cancelled)) {
                                requireOpen();
                                if (token.isCancelled() || !accepting.get()) throw new InterruptedException();
                                result = tool.execute(call.id(), effective, token, ignored -> {}, nested);
                            }
                        }
                    }
                } catch (Exception failure) {
                    if (token.isCancelled() || !accepting.get() || !open.get() || failure instanceof InterruptedException) result = cancelled;
                    else {
                        try { result = failureOnOwner(owner, callbacks, call, ToolFailure.Kind.EXECUTION, failure.getMessage()); }
                        catch (Exception closed) { result = cancelled; }
                    }
                }
                if (!accepting.get() || token.isCancelled() || !open.get()) return cancelled;
                if (accepting.get() && !token.isCancelled()) {
                    AuxiliaryToolCall completion = new AuxiliaryToolCall(call, result, parent, token);
                    try { owner.call(() -> { requireOpen(); callbacks.afterAuxiliaryToolCall(completion); return null; }); }
                    catch (Exception failure) {
                        if (token.isCancelled() || !accepting.get() || !open.get()) return cancelled;
                        throw new IllegalStateException("auxiliary completion failed", failure);
                    }
                }
                return accepting.get() && !token.isCancelled() && open.get() ? result : cancelled;
            }
        };
    }

    abstract static class ScopedInvoker implements ToolInvoker, AutoCloseable {
        final AtomicBoolean open = new AtomicBoolean(true);
        @Override public void close() { open.set(false); }
        void requireOpen() { if (!open.get()) throw new IllegalStateException("auxiliary invocation is closed"); }
    }

    static ScopedInvoker standalone(List<AgentTool> tools, AgentCallbacks callbacks, CancellationToken token) {
        var snapshot = List.copyOf(tools);
        Thread ownerThread = Thread.currentThread();
        return new ScopedInvoker() {
            @Override public Optional<AgentTool> find(String name) {
                return snapshot.stream().filter(tool -> tool.name().equals(name)).findFirst();
            }
            @Override public ToolResult invoke(String name, JsonNode input) {
                requireOpen();
                if (Thread.currentThread() != ownerThread) throw new IllegalStateException("auxiliary owner thread required");
                var queue = new LinkedBlockingQueue<OwnerCall>();
                var accepting = new AtomicBoolean(true);
                Owner owner = action -> {
                    var call = new OwnerCall(action, new CompletableFuture<>());
                    queue.add(call);
                    return await(call, token, accepting);
                };
                ToolResult cancelled = new ToolResult(List.of(), null, ToolResult.Status.CANCELLED);
                var worker = worker(snapshot, callbacks, token, accepting, owner, null, cancelled);
                var done = new CompletableFuture<ToolResult>();
                Thread thread = Thread.ofVirtual().name("cafecli-auxiliary").start(() -> {
                    try { done.complete(worker.invoke(name, input)); }
                    catch (Throwable failure) { done.completeExceptionally(failure); }
                });
                try {
                    while (!done.isDone() && !token.isCancelled()) {
                        OwnerCall call = queue.poll(20, TimeUnit.MILLISECONDS);
                        if (call != null && !token.isCancelled()) call.run();
                    }
                    if (token.isCancelled()) return failure(callbacks,
                            new ToolCall("aux-cancelled", name, input.toString()), ToolFailure.Kind.CANCELLED, null);
                    return done.join();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return failure(callbacks, new ToolCall("aux-interrupted", name, input.toString()),
                            ToolFailure.Kind.CANCELLED, null);
                } finally {
                    accepting.set(false);
                    worker.close();
                    thread.interrupt();
                    queue.forEach(call -> call.reply().cancel(false));
                }
            }
        };
    }

    private static ToolResult failureOnOwner(Owner owner, AgentCallbacks callbacks, ToolCall call,
                                             ToolFailure.Kind kind, String detail) throws Exception {
        return (ToolResult) owner.call(() -> failure(callbacks, call, kind, detail));
    }

    private static ToolResult failure(AgentCallbacks callbacks, ToolCall call, ToolFailure.Kind kind, String detail) {
        return callbacks.toolFailure(call, new ToolFailure(kind, detail, 0));
    }
}
