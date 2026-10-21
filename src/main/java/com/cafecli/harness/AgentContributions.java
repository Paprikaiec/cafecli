package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentCallbacks;
import com.cafecli.core.AgentTool;
import com.cafecli.core.IterationDecision;
import com.cafecli.core.ToolFailure;
import com.cafecli.core.ToolPreflight;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.harness.context.MessageKindRegistry;
import com.cafecli.harness.context.Steering;
import com.cafecli.harness.permission.PermissionRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * The {@link AgentScope} of one agent being built: collects what its attached capabilities register, then composes
 * it into the agent's one {@link AgentCallbacks}. Contributions of one kind run in registration order — capabilities in
 * declared order, each in the order it registered.
 */
final class AgentContributions implements AgentScope {

    private final String agentId;
    private final List<RunStartWriter> runStartWriters = new ArrayList<>();
    private final List<AgentTool> tools = new ArrayList<>();
    private record BeforeTool(PermissionRoute.Approval participant, boolean approval) {}

    private final List<BeforeTool> beforeTools = new ArrayList<>();
    private final List<BiFunction<ToolCall, ToolResult, ToolResult>> afterTools = new ArrayList<>();
    private final List<BiFunction<ToolCall, CancellationToken, PermissionRoute>> routes = new ArrayList<>();
    private final List<BiFunction<AssistantMessage, List<ToolResultEntry>,
            IterationDecision>> iterationEnds = new ArrayList<>();
    private final List<MessageKind<?>> kinds = new ArrayList<>(List.of(Steering.KIND));
    private volatile BoundaryInbox producer;
    private MessageKindRegistry registry;
    private boolean closed;

    AgentContributions(String agentId) {
        this.agentId = agentId;
    }

    @Override public void messageKind(MessageKind<?> kind) {
        requireOpen();
        kinds.add(Objects.requireNonNull(kind, "kind"));
    }

    @Override public BoundaryInbox inbox() {
        requireOpen();
        return entry -> {
            BoundaryInbox target = producer;
            if (target == null) throw new IllegalStateException("agent hooks have not completed");
            target.post(Objects.requireNonNull(entry, "entry"));
        };
    }

    void bind(Agent agent) {
        producer = entry -> { registry.requireRegistered(entry); agent.post(entry); };
    }

    MessageKindRegistry messageKinds() { return registry; }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public void runStartWriter(RunStartWriter writer) {
        Objects.requireNonNull(writer, "writer");
        requireOpen();
        runStartWriters.add(writer);
    }

    @Override
    public void tool(AgentTool tool) {
        requireOpen();
        tools.add(Objects.requireNonNull(tool, "tool"));
    }

    @Override
    public void beforeToolCall(BiFunction<ToolCall, CancellationToken, ToolPreflight> participant) {
        requireOpen();
        Objects.requireNonNull(participant, "participant");
        beforeTools.add(new BeforeTool((call, cancellation, route) -> participant.apply(call, cancellation), false));
    }

    @Override
    public void afterToolCall(BiFunction<ToolCall, ToolResult, ToolResult> participant) {
        requireOpen();
        afterTools.add(Objects.requireNonNull(participant, "participant"));
    }

    @Override public void permissionRoute(BiFunction<ToolCall, CancellationToken, PermissionRoute> participant) {
        requireOpen();
        routes.add(Objects.requireNonNull(participant, "participant"));
    }
    @Override public void approval(PermissionRoute.Approval participant) {
        requireOpen();
        beforeTools.add(new BeforeTool(Objects.requireNonNull(participant, "participant"), true));
    }

    @Override public void iterationEnd(BiFunction<AssistantMessage,
            List<ToolResultEntry>, IterationDecision> participant) {
        requireOpen();
        iterationEnds.add(Objects.requireNonNull(participant, "participant"));
    }

    List<AgentTool> tools() {
        return List.copyOf(tools);
    }

    /**
     * Ends registration — the agent hooks have run — and composes the contributions. Every callback with nothing
     * contributed keeps core's default; custom kinds use the assembled registry.
     */
    AgentCallbacks close() {
        registry = new MessageKindRegistry(kinds);
        closed = true;
        List<RunStartWriter> writers = List.copyOf(runStartWriters);
        var before = List.copyOf(beforeTools);
        var after = List.copyOf(afterTools);
        var routing = List.copyOf(routes);
        var endings = List.copyOf(iterationEnds);
        return new AgentCallbacks() {
            @Override public List<Message> convertToLlm(List<LogEntry> entries) {
                return registry.convertToLlm(entries);
            }

            @Override
            public ToolPreflight beforeToolCall(ToolCall call, CancellationToken cancellation) {
                PermissionRoute route = null;
                for (var participant : before) {
                    // Routes may register later than approval. Fold them once at its first invocation, while
                    // retaining generic participants' position relative to approved argument edits.
                    if (participant.approval() && route == null) {
                        route = route(call, cancellation);
                        if (route.mode() == PermissionRoute.Mode.COMPLETE) {
                            return ToolPreflight.complete(call, route.result());
                        }
                    }
                    ToolPreflight decision = Objects.requireNonNull(
                            participant.participant().apply(call, cancellation, route), "preflight");
                    ToolCall adjusted = decision.call();
                    if (!call.id().equals(adjusted.id()) || !call.name().equals(adjusted.name())) {
                        throw new IllegalArgumentException("preflight must preserve call id and name");
                    }
                    if (decision instanceof ToolPreflight.Complete) return decision;
                    call = adjusted;
                }
                if (route == null) route = route(call, cancellation);
                return route.mode() == PermissionRoute.Mode.COMPLETE
                        ? ToolPreflight.complete(call, route.result()) : ToolPreflight.allow(call);
            }

            private PermissionRoute route(ToolCall call, CancellationToken cancellation) {
                var route = PermissionRoute.CONTINUE;
                for (var participant : routing) {
                    var next = Objects.requireNonNull(participant.apply(call, cancellation), "route");
                    if (next.mode().ordinal() > route.mode().ordinal()) route = next;
                    if (route.mode() == PermissionRoute.Mode.COMPLETE) {
                        return route;
                    }
                }
                return route;
            }

            @Override
            public ToolResult afterToolCall(ToolCall call, ToolResult result) {
                for (var participant : after) result = Objects.requireNonNull(participant.apply(call, result), "result");
                return result;
            }

            @Override public IterationDecision onIterationEnd(
                    AssistantMessage message, List<ToolResultEntry> results) {
                List<CustomEntry> entries = new ArrayList<>();
                boolean proceed = !message.toolCalls().isEmpty();
                for (var participant : endings) {
                    var decision = Objects.requireNonNull(participant.apply(message, results), "iteration decision");
                    entries.addAll(decision.entries());
                    if (decision instanceof IterationDecision.Exhausted exhausted) {
                        return new IterationDecision.Exhausted(exhausted.reason(), entries);
                    }
                    proceed |= decision instanceof IterationDecision.Proceed;
                }
                return proceed ? new IterationDecision.Proceed(entries)
                        : new IterationDecision.End(entries);
            }

            @Override
            public ToolResult toolFailure(ToolCall call, ToolFailure failure) {
                return ToolFailures.result(call, failure);
            }

            @Override
            public List<CustomEntry> onRunStart(UserMessage input, CancellationToken cancellation) {
                List<CustomEntry> entries = new ArrayList<>();
                for (RunStartWriter writer : writers) {
                    entries.addAll(writer.write(input, cancellation));
                }
                return entries;
            }
        };
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("contributions to agent " + agentId
                    + " are registered while its agent hooks run");
        }
    }
}
