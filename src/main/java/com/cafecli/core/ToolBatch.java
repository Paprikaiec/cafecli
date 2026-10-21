package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.core.AgentEvent.Origin;
import com.cafecli.core.log.ToolResultEntry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Source-order preflight, then at most four parallel executions. Workers only post data; callbacks and events stay
 * on the loop thread. A complete batch is returned before the loop appends the assistant and its results together.
 */
final class ToolBatch {
    private final Origin origin;
    private final Consumer<AgentEvent> events;
    private final List<AgentTool> tools;
    private final AgentCallbacks callbacks;
    private final CancellationToken cancellation;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    ToolBatch(Origin origin, Consumer<AgentEvent> events, List<AgentTool> tools,
              AgentCallbacks callbacks, CancellationToken cancellation, Duration timeout) {
        this.origin = origin;
        this.events = events;
        this.tools = tools;
        this.callbacks = callbacks;
        this.cancellation = cancellation;
        this.timeout = timeout;
    }

    List<ToolResultEntry> run(List<ToolCall> calls) {
        if (calls.isEmpty()) return List.of();
        List<ToolResult> preflight = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            try {
                preflight.add(callbacks.beforeToolCall(call, cancellation));
            } catch (Exception e) {
                preflight.add(failure(call, ToolFailure.Kind.EXECUTION, e.getMessage()));
            }
        }
        return execute(calls, preflight);
    }

    private List<ToolResultEntry> execute(List<ToolCall> calls, List<ToolResult> preflight) {
        BlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
        ToolResultEntry[] results = new ToolResultEntry[calls.size()];
        AtomicReferenceArray<Finished> completed = new AtomicReferenceArray<>(calls.size());
        AtomicBoolean accepting = new AtomicBoolean(true);
        boolean[] started = new boolean[calls.size()];
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(calls.size(), 4), runnable -> {
            Thread thread = new Thread(runnable, "cafecli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });
        try (CancellationToken.Registration ignored = cancellation.onCancel(() -> queue.add(new Cancelled(System.nanoTime())))) {
            // A single leaf owns its timeout; the batch deadline only applies to multi-call batches.
            boolean timed = calls.size() > 1;
            long deadline = timed ? System.nanoTime() + timeout.toNanos() : Long.MAX_VALUE;
            int remaining = calls.size();
            for (int i = 0; i < calls.size(); i++) {
                int index = i;
                ToolCall call = calls.get(i);
                if (preflight.get(i) == null) {
                    futures.add(executor.submit(() -> {
                        queue.add(new Started(index));
                        Finished done;
                        try {
                            ToolResult result = invoke(call, update -> {
                                if (accepting.get()) queue.add(new Updated(index, update));
                            });
                            done = new Finished(index, result, null, System.nanoTime());
                        } catch (Throwable e) {
                            done = new Finished(index, null, e, System.nanoTime());
                        }
                        completed.set(index, done);
                        queue.add(done);
                    }));
                }
            }
            for (int i = 0; i < calls.size(); i++) {
                if (preflight.get(i) != null) {
                    start(calls.get(i), i, started);
                    results[i] = finish(calls.get(i), preflight.get(i));
                    remaining--;
                }
            }
            while (remaining > 0) {
                if (timed && System.nanoTime() >= deadline) {
                    fillUnfinished(calls, results, started, completed, ToolFailure.Kind.TIMEOUT, deadline);
                    break;
                }
                Signal signal;
                try {
                    signal = timed ? queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                            : queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fillUnfinished(calls, results, started, completed, ToolFailure.Kind.BATCH_INTERRUPTED, System.nanoTime());
                    break;
                }
                if (signal == null || signal instanceof Cancelled) {
                    fillUnfinished(calls, results, started, completed, signal == null
                            ? ToolFailure.Kind.TIMEOUT : ToolFailure.Kind.CANCELLED,
                            signal instanceof Cancelled cancel ? cancel.atNanos() : deadline);
                    break;
                }
                switch (signal) {
                    case Started begun -> start(calls.get(begun.index()), begun.index(), started);
                    case Updated update -> {
                        if (results[update.index()] == null) {
                            events.accept(new AgentEvent.ToolUpdate(origin, calls.get(update.index()), update.result()));
                        }
                    }
                    case Finished done -> {
                        ToolCall call = calls.get(done.index());
                        start(call, done.index(), started);
                        results[done.index()] = finish(call, done.atNanos() <= deadline ? result(call, done)
                                : failure(call, ToolFailure.Kind.TIMEOUT, null));
                        remaining--;
                    }
                    case Cancelled ignoredCancel -> throw new AssertionError();
                }
            }
            return List.copyOf(Arrays.asList(results));
        } finally {
            accepting.set(false);
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            // Queue belongs only to this batch: a late worker can neither emit nor reach a later iteration.
        }
    }

    private void fillUnfinished(List<ToolCall> calls, ToolResultEntry[] results, boolean[] started,
                                AtomicReferenceArray<Finished> completed, ToolFailure.Kind kind, long cutoff) {
        for (int i = 0; i < calls.size(); i++) {
            if (results[i] == null) {
                start(calls.get(i), i, started);
                Finished done = completed.get(i);
                results[i] = finish(calls.get(i), done != null && done.atNanos() <= cutoff
                        ? result(calls.get(i), done) : failure(calls.get(i), kind, null));
            }
        }
    }

    private ToolResult result(ToolCall call, Finished done) {
        if (done.error() == null) return done.result();
        return failure(call, done.error() instanceof UnknownTool ? ToolFailure.Kind.UNKNOWN_TOOL
                : cancellation.isCancelled() ? ToolFailure.Kind.CANCELLED : ToolFailure.Kind.EXECUTION,
                done.error().getMessage());
    }

    private void start(ToolCall call, int index, boolean[] started) {
        if (!started[index]) {
            started[index] = true;
            events.accept(new AgentEvent.ToolStart(origin, call));
        }
    }

    private ToolResultEntry finish(ToolCall call, ToolResult result) {
        try {
            result = callbacks.afterToolCall(call, result);
        } catch (Exception e) {
            result = failure(call, ToolFailure.Kind.EXECUTION, e.getMessage());
        }
        ToolResultEntry entry = new ToolResultEntry(new ToolResultMessage(call.id(), call.name(), result.content(),
                result.status() != ToolResult.Status.OK), result.status(), result.details());
        events.accept(new AgentEvent.ToolEnd(origin, entry));
        return entry;
    }

    /** Worker-safe: never invokes callbacks or subscribers. */
    private ToolResult invoke(ToolCall call, Consumer<ToolResult> progress) throws Exception {
        if (cancellation.isCancelled()) throw new InterruptedException();
        AgentTool tool = tools.stream().filter(candidate -> candidate.name().equals(call.name())).findFirst().orElse(null);
        if (tool == null) throw new UnknownTool();
        return tool.execute(call.id(), json.readTree(call.arguments()), cancellation, progress);
    }

    private ToolResult failure(ToolCall call, ToolFailure.Kind kind, String detail) {
        return callbacks.toolFailure(call, new ToolFailure(kind, detail, timeout.toSeconds()));
    }

    private static final class UnknownTool extends Exception {}
    private sealed interface Signal {}
    private record Started(int index) implements Signal {}
    private record Updated(int index, ToolResult result) implements Signal {}
    private record Finished(int index, ToolResult result, Throwable error, long atNanos) implements Signal {}
    private record Cancelled(long atNanos) implements Signal {}
}
