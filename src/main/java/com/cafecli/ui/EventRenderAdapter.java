package com.cafecli.ui;

import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.Model;
import com.cafecli.ai.message.StopReason;
import com.cafecli.harness.SessionStatus;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.ToolResultView;
import java.util.*;

/** MAIN event presentation. Call only on the UI queue consumer, never in a session subscriber. */
public final class EventRenderAdapter {
    private final Renderer renderer;
    private SessionStatus snapshot;
    private SourceStreamRenderer source;
    private final Map<String, ToolCall> calls = new LinkedHashMap<>();
    private final Map<String, ToolResultEntry> results = new HashMap<>();
    private long started;
    private String phase = "idle";

    public EventRenderAdapter(Renderer renderer, String model, long contextWindow) {
        this(renderer,SessionStatus.idle(new Model(model,"unknown","unknown",(int)Math.min(Integer.MAX_VALUE,contextWindow),0,Set.of())));
    }

    public EventRenderAdapter(Renderer renderer, SessionStatus status) {
        this.renderer = Objects.requireNonNull(renderer);
        this.snapshot = Objects.requireNonNull(status);
        status("idle");
    }

    /** An immutable owner-captured snapshot; no projection or prompt work occurs here. */
    public void updateSessionStatus(SessionStatus status) {
        snapshot = Objects.requireNonNull(status);
        status(phase);
    }

    public void accept(AgentEvent event) {
        if (!"main".equals(event.agentId())) return;
        switch (event) {
            case AgentEvent.RunStart ignored -> {
                renderer.beginTurn(); source = new SourceStreamRenderer(renderer);
                started = System.nanoTime(); calls.clear(); results.clear(); snapshot = snapshot.withUsage(0,0,0);
                status("running");
            }
            case AgentEvent.IterationStart ignored -> { calls.clear(); results.clear(); }
            case AgentEvent.MessageStart ignored -> { ensureSource(); source.beginThinking(); status("thinking"); }
            case AgentEvent.MessageUpdate update -> {
                ensureSource();
                switch (update.event()) {
                    case StreamEvent.TextDelta delta -> { source.onContentDelta(delta.delta()); status("running"); }
                    case StreamEvent.ThinkingDelta delta -> source.onReasoningDelta(delta.delta());
                    default -> { }
                }
            }
            case AgentEvent.MessageEnd end -> {
                ensureSource(); source.resetBetweenIterations();
                if (end.message().stopReason() != StopReason.ERROR && end.message().stopReason() != StopReason.ABORTED) {
                    var usage = end.message().usage();
                    snapshot = snapshot.withUsage(snapshot.inputTokens()+Math.max(0,usage.inputTokens()),
                            snapshot.outputTokens()+Math.max(0,usage.outputTokens()),
                            snapshot.cachedInputTokens()+Math.max(0,usage.cachedInputTokens()));
                }
                for (var call : end.message().toolCalls()) calls.put(call.id(), call);
                if (!calls.isEmpty()) renderer.appendToolCalls(calls.values().stream()
                        .map(c -> new ToolCallView(c.id(), c.name(), c.arguments())).toList());
                status(calls.isEmpty() ? "running" : "tools");
            }
            case AgentEvent.ToolUpdate update -> {
                var details = update.progress().details();
                if (update.call().name().equals("execute_command") && details != null && details.has("line"))
                    renderer.appendCommandOutput(details.path("command").asText(), details.path("line").asText());
            }
            case AgentEvent.ToolEnd end -> results.put(end.result().message().toolCallId(), end.result());
            case AgentEvent.ToolBatchEnd end -> {
                results.clear(); end.results().forEach(r -> results.put(r.message().toolCallId(), r));
            }
            case AgentEvent.IterationEnd ignored -> publishBatch();
            case AgentEvent.Retry retry -> status("retry " + retry.attempt().attempt() + "/" + retry.attempt().maxAttempts());
            case AgentEvent.Compaction ignored -> status("compacting");
            case AgentEvent.RunEnd end -> {
                if (source != null) { source.finish(); source = null; }
                if (!(end.outcome() instanceof RunOutcome.Completed)) renderer.stream().println(outcome(end.outcome()));
                calls.clear(); results.clear(); status("idle");
            }
            default -> { }
        }
    }

    /** An exceptional session callback can end a run before its ordinary RunEnd event. */
    public void fail(Throwable failure) {
        if (source != null) { source.finish(); source = null; }
        renderer.stream().println("❌ " + failure.getMessage());
        calls.clear(); results.clear(); status("idle");
    }

    private void ensureSource() { if (source == null) source = new SourceStreamRenderer(renderer); }

    private void publishBatch() {
        var batch = new ArrayList<ToolResultView>();
        for (var call : calls.values()) {
            var result = results.get(call.id());
            if (result == null) continue;
            var details = result.details();
            if (details != null && details.hasNonNull("diff")) {
                var diff = details.get("diff");
                renderer.appendDiff(diff.path("path").asText(), nullable(diff.get("before")), nullable(diff.get("after")));
            }
            var text = new StringBuilder();
            result.message().content().forEach(part -> { if (part instanceof TextBlock t) text.append(t.text()); });
            batch.add(new ToolResultView(call.id(),call.name(),call.arguments(),text.toString()));
        }
        if (!batch.isEmpty()) renderer.appendToolResults(List.copyOf(batch));
        calls.clear(); results.clear();
    }
    private static String nullable(com.fasterxml.jackson.databind.JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
    private void status(String phase) {
        this.phase = phase;
        if ("idle".equals(phase)) {
            renderer.updateStatus(StatusInfo.idle(snapshot.model().id(),snapshot.model().contextWindow(),snapshot.contextTokens(),false));
            return;
        }
        renderer.updateStatus(StatusInfo.tokens(snapshot.model().id(),snapshot.model().contextWindow(),snapshot.contextTokens(),
                snapshot.inputTokens(),snapshot.outputTokens(),snapshot.cachedInputTokens(),snapshot.estimatedCost(),false,
                started == 0 ? 0 : (System.nanoTime()-started)/1_000_000,phase));
    }
    private static String outcome(RunOutcome outcome) {
        return com.cafecli.harness.RunOutcomeText.format(outcome);
    }
}
