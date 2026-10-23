package com.cafecli.harness;

import com.cafecli.core.RunInput;
import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Context;
import com.cafecli.ai.Model;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentEvent;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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
import com.cafecli.harness.context.SystemPrompt;
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
    private final SystemPrompt prompt;
    private final Model model;
    private final Consumer<AgentEvent> events;
    private final List<UnaryOperator<List<LogEntry>>> sourceStages = new ArrayList<>();
    private final List<UnaryOperator<List<LogEntry>>> stages = new ArrayList<>();
    private final List<LogWriter> boundaries = new ArrayList<>();
    private final List<LogWriter> overflows = new ArrayList<>();
    private final List<BiFunction<AssistantMessage, List<ToolResultEntry>, IterationDecision>> budgets = new ArrayList<>();
    private final List<BiFunction<RunInput, CancellationToken, RunInput>> inputPreparers = new ArrayList<>();
    private final List<RunStartWriter> runStartWriters = new ArrayList<>();
    private final List<java.util.function.Supplier<List<AgentTool>>> toolSources = new ArrayList<>();
    private final List<UnaryOperator<com.cafecli.prompt.PromptContext>> promptContributions = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<java.util.function.BiConsumer<UserMessage, CancellationToken>> runStarts = new ArrayList<>();
    private final List<Runnable> runEnds = new ArrayList<>();
    private final List<UnaryOperator<List<ToolResultEntry>>> batchNormalizers = new ArrayList<>();
    private final List<UnaryOperator<List<AgentTool>>> toolStages = new ArrayList<>();
    private final List<Consumer<com.cafecli.core.AuxiliaryToolCall>> auxiliary = new ArrayList<>();
    private final List<AgentTool> tools = new ArrayList<>();
    private record BeforeTool(PermissionRoute.Approval participant, boolean approval) {}

    private final List<java.util.function.Function<ToolCall, String>> approvalNotices = new ArrayList<>();
    private final List<BeforeTool> beforeTools = new ArrayList<>();
    private final List<BiFunction<ToolCall, ToolResult, ToolResult>> afterTools = new ArrayList<>();
    private final List<BiFunction<ToolCall, CancellationToken, PermissionRoute>> routes = new ArrayList<>();
    private final List<BiFunction<AssistantMessage, List<ToolResultEntry>,
            IterationDecision>> iterationEnds = new ArrayList<>();
    private final List<MessageKind<?>> kinds = new ArrayList<>(List.of(Steering.KIND));
    private volatile BoundaryInbox producer;
    private Agent agent;
    private MessageKindRegistry registry;
    private boolean closed;

    AgentContributions(String agentId, Model model, SystemPrompt prompt, Consumer<AgentEvent> events) {
        this.prompt = prompt;
        this.events = events;
        this.model = model;
        this.agentId = agentId;
    }

    @Override public RunInput presentInput(RunInput input) {
        if (registry == null) throw new IllegalStateException("agent hooks have not completed");
        return registry.presentInput(input);
    }
    @Override public void inputPreparation(BiFunction<RunInput, CancellationToken, RunInput> participant) {
        requireOpen(); inputPreparers.add(Objects.requireNonNull(participant, "participant"));
    }
    @Override public void sourceProjectionStage(UnaryOperator<List<LogEntry>> stage) {
        requireOpen(); sourceStages.add(Objects.requireNonNull(stage, "stage"));
    }
    @Override public void projectionStage(UnaryOperator<List<LogEntry>> stage) {
        requireOpen(); stages.add(Objects.requireNonNull(stage, "stage"));
    }
    @Override public void iterationBoundaryWriter(LogWriter writer) {
        requireOpen(); boundaries.add(Objects.requireNonNull(writer, "writer"));
    }
    @Override public void contextOverflowWriter(LogWriter writer) {
        requireOpen(); overflows.add(Objects.requireNonNull(writer, "writer"));
    }
    @Override public void iterationBudget(BiFunction<AssistantMessage, List<ToolResultEntry>, IterationDecision> participant) {
        requireOpen(); budgets.add(Objects.requireNonNull(participant, "participant"));
    }
    @Override public void emit(AgentEvent event) {
        if (agent == null) throw new IllegalStateException("agent hooks have not completed");
        if (!agentId.equals(event.agentId())) throw new IllegalArgumentException("event origin must name this agent");
        events.accept(event);
    }
    private List<LogEntry> project(List<LogEntry> log) {
        List<LogEntry> view = List.copyOf(log);
        for (var stage : sourceStages) view = List.copyOf(stage.apply(view));
        for (var stage : stages) view = List.copyOf(stage.apply(view));
        return view;
    }
    @Override public List<Message> projectMessages(List<LogEntry> log) {
        if (registry == null) throw new IllegalStateException("agent hooks have not completed");
        return registry.convertToLlm(project(log));
    }

    @Override public Runnable systemPromptInvalidator() { return prompt::invalidate; }

    @Override public String systemPrompt() { return prompt.current(); }

    @Override public Model model() { return model; }

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

    @Override public List<CustomEntry> takeInbox(java.util.function.Predicate<? super CustomEntry> matches) {
        if (agent == null) throw new IllegalStateException("agent hooks have not completed");
        return agent.takeInbox(matches);
    }

    @Override public List<Message> messages() {
        if (agent == null) throw new IllegalStateException("agent hooks have not completed");
        return projectMessages(agent.log());
    }

    void bind(Agent agent) {
        this.agent = agent;
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
    @Override public void approvalNotice(java.util.function.Function<ToolCall, String> constraint) {
        requireOpen(); approvalNotices.add(Objects.requireNonNull(constraint));
    }
    @Override public String approvalNotice(ToolCall call) {
        for (var constraint : approvalNotices) {
            String notice = constraint.apply(call);
            if (notice != null) return notice;
        }
        return null;
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

    @Override public void toolSource(java.util.function.Supplier<List<AgentTool>> source) {
        requireOpen(); toolSources.add(Objects.requireNonNull(source));
    }
    @Override public void toolSnapshotStage(UnaryOperator<List<AgentTool>> stage) {
        requireOpen(); toolStages.add(Objects.requireNonNull(stage));
    }
    @Override public void afterAuxiliaryToolCall(Consumer<com.cafecli.core.AuxiliaryToolCall> observer) {
        requireOpen(); auxiliary.add(Objects.requireNonNull(observer));
    }
    @Override public void promptContext(UnaryOperator<com.cafecli.prompt.PromptContext> contribution) {
        requireOpen(); promptContributions.add(Objects.requireNonNull(contribution));
    }
    com.cafecli.prompt.PromptContext promptContext() {
        var context = com.cafecli.prompt.PromptContext.empty();
        for (var contribution : promptContributions) context = Objects.requireNonNull(contribution.apply(context));
        return context;
    }
    @Override public void runStart(Consumer<CancellationToken> participant) {
        Objects.requireNonNull(participant);
        runStart((input, token) -> participant.accept(token));
    }
    @Override public void runStart(java.util.function.BiConsumer<UserMessage, CancellationToken> participant) {
        requireOpen(); runStarts.add(Objects.requireNonNull(participant));
    }
    @Override public void runEnd(Runnable participant) { requireOpen(); runEnds.add(Objects.requireNonNull(participant)); }
    @Override public void onClose(AutoCloseable resource) { requireOpen(); resources.add(Objects.requireNonNull(resource)); }
    @Override public void toolBatchResults(UnaryOperator<List<ToolResultEntry>> normalizer) {
        requireOpen(); batchNormalizers.add(Objects.requireNonNull(normalizer));
    }
    void cleanup() {
        Throwable failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); }
            catch (Throwable e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        resources.clear();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("agent cleanup failed", failure);
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
            @Override public RunInput prepareInput(UserMessage input, CancellationToken cancellation) {
                var prepared = new RunInput(input);
                for (var participant : inputPreparers) prepared = Objects.requireNonNull(participant.apply(prepared, cancellation));
                return prepared;
            }
            @Override public List<LogEntry> transformContext(List<LogEntry> log) { return project(log); }

            @Override public List<CustomEntry> onIterationBoundary(List<LogEntry> log, CancellationToken token) {
                return write(boundaries, log, token);
            }
            @Override public List<CustomEntry> onContextOverflow(List<LogEntry> log, CancellationToken token) {
                return write(overflows, log, token);
            }
            private List<CustomEntry> write(List<LogWriter> writers, List<LogEntry> log, CancellationToken token) {
                List<LogEntry> snapshot = new ArrayList<>(log);
                List<CustomEntry> added = new ArrayList<>();
                for (var writer : writers) {
                    var entries = List.copyOf(writer.write(List.copyOf(snapshot), token));
                    added.addAll(entries);
                    snapshot.addAll(entries);
                }
                return List.copyOf(added);
            }

            @Override public void afterAuxiliaryToolCall(com.cafecli.core.AuxiliaryToolCall completion) {
                auxiliary.forEach(observer -> observer.accept(completion));
            }
            @Override public void onRunEnd() {
                Throwable failure = null;
                for (Runnable participant : runEnds) {
                    try { participant.run(); }
                    catch (RuntimeException | Error e) {
                        if (failure == null) failure = e;
                        else if (failure != e) failure.addSuppressed(e);
                    }
                }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
            }
            @Override public void onClose() { cleanup(); }
            @Override public List<ToolResultEntry> afterToolBatch(List<ToolResultEntry> results) {
                for (var normalizer : batchNormalizers) results = List.copyOf(normalizer.apply(results));
                return results;
            }
            @Override public List<AgentTool> tools(List<AgentTool> initial) {
                List<AgentTool> current = new ArrayList<>(initial);
                for (var source : toolSources) current.addAll(List.copyOf(source.get()));
                List<AgentTool> snapshot = List.copyOf(current);
                for (var stage : toolStages) snapshot = List.copyOf(stage.apply(snapshot));
                return snapshot;
            }
            @Override public Context prepareRequest(Context request) {
                return new Context(prompt.current(), request.messages(), request.tools());
            }

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
                if (proceed) {
                    for (var budget : budgets) {
                        var decision = Objects.requireNonNull(budget.apply(message, results), "budget decision");
                        entries.addAll(decision.entries());
                        if (decision instanceof IterationDecision.Exhausted exhausted) {
                            return new IterationDecision.Exhausted(exhausted.reason(), entries);
                        }
                    }
                }
                return proceed ? new IterationDecision.Proceed(entries)
                        : new IterationDecision.End(entries);
            }

            @Override
            public ToolResult toolFailure(ToolCall call, ToolFailure failure) {
                return ToolFailures.result(call, failure);
            }

            @Override
            public List<CustomEntry> onRunStart(UserMessage input, CancellationToken cancellation, com.cafecli.core.ToolInvoker invoker) {
                for (var participant : runStarts) participant.accept(input, cancellation);
                List<CustomEntry> entries = new ArrayList<>();
                for (RunStartWriter writer : writers) {
                    var scoped = new WriterTools(invoker);
                    try { entries.addAll(writer.write(input, cancellation, scoped)); }
                    finally { scoped.open = false; }
                }
                return entries;
            }
        };
    }

    /** A writer cannot retain its borrowed invocation port into a later writer. */
    private static final class WriterTools implements com.cafecli.core.ToolInvoker {
        private final com.cafecli.core.ToolInvoker delegate;
        private volatile boolean open = true;
        WriterTools(com.cafecli.core.ToolInvoker delegate) { this.delegate = delegate; }
        private void requireOpen() { if (!open) throw new IllegalStateException("run-start writer is closed"); }
        @Override public java.util.Optional<AgentTool> find(String name) { requireOpen(); return delegate.find(name); }
        @Override public ToolResult invoke(String name, com.fasterxml.jackson.databind.JsonNode input) {
            requireOpen(); return delegate.invoke(name, input);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("contributions to agent " + agentId
                    + " are registered while its agent hooks run");
        }
    }
}
