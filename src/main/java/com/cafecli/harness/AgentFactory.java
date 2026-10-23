package com.cafecli.harness;

import com.cafecli.ai.Provider;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentCallbacks;
import com.cafecli.harness.context.MessageKindRegistry;
import com.cafecli.harness.context.SystemPrompt;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptContext;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Builds every agent of a session — the main agent, and sub-agents from ticket 17 — from an {@link AgentProfile}. For
 * each agent: run the agent hooks of the capabilities its profile attaches, in the session's declared order, with a
 * fresh {@link AgentScope}; compose what they registered into the agent's one {@link AgentCallbacks}; assemble its
 * system prompt — once, now, not per run, so the request prefix stays stable; build it; and connect it to the
 * session's event stream, so its events reach every session listener and subscriber.
 *
 * <p>The system prompt is assembled from the templates ({@code prompts/*.md} on the class path, with the user's and the
 * project's overrides under their config homes) through the prompt leaf. Recall stays outside this prompt; the
 * external-context and skill-index sections come from the attached capabilities. The leaf also writes the current
 * date and the JVM's default time zone into it.
 */
public final class AgentFactory {

    private final Provider provider;
    private final PromptAssembler prompts;
    private volatile List<Capability> capabilities;
    private SessionEvents events;
    private final Duration toolBatchTimeout;
    private boolean claimed;

    /** Constructed at the composition root, then opened once by its owning session. */
    public AgentFactory(Provider provider, PromptAssembler prompts, Duration toolBatchTimeout) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.toolBatchTimeout = Objects.requireNonNull(toolBatchTimeout, "toolBatchTimeout");
    }

    synchronized void claim() {
        if (claimed || capabilities != null) throw new IllegalStateException("factory already belongs to a session");
        claimed = true;
    }

    synchronized void open(List<Capability> capabilities, SessionEvents events) {
        if (this.capabilities != null) throw new IllegalStateException("factory already belongs to a session");
        this.events = Objects.requireNonNull(events, "events");
        this.capabilities = List.copyOf(capabilities);
    }

    /**
     * @param capabilities the session's capabilities in declared order; their session hooks have run
     */
    AgentFactory(Provider provider, PromptAssembler prompts, List<Capability> capabilities, SessionEvents events,
                 Duration toolBatchTimeout) {
        this(provider, prompts, toolBatchTimeout);
        open(capabilities, events);
    }

    /**
     * @param agentId names the agent in every event it emits
     * @throws IllegalArgumentException if the profile attaches a capability the session was not opened with: its
     *                                  session hook never ran
     */
    public Agent create(String agentId, AgentProfile profile) {
        return assemble(agentId, profile).agent();
    }

    /** Agent plus its pure, harness-owned presentation; the caller owns its lifetime. */
    public record BuiltAgent(Agent agent, java.util.function.Function<List<com.cafecli.core.log.LogEntry>,
            List<com.cafecli.ai.message.Message>> projectMessages) { }

    public BuiltAgent createWithProjection(String agentId, AgentProfile profile) {
        Assembly assembly = assemble(agentId, profile);
        return new BuiltAgent(assembly.agent(), assembly.projectMessages());
    }

    /** Publishes immutable projected history on the agent owner, including in-flight continuation updates. */
    public BuiltAgent createWithProjection(String agentId, AgentProfile profile,
            java.util.function.Consumer<List<com.cafecli.ai.message.Message>> observer) {
        Assembly assembly = assemble(agentId, profile, Objects.requireNonNull(observer));
        return new BuiltAgent(assembly.agent(), assembly.projectMessages());
    }

    record Assembly(Agent agent, MessageKindRegistry messageKinds, Runnable invalidateSystemPrompt,
                    java.util.function.Function<List<com.cafecli.core.log.LogEntry>, List<com.cafecli.ai.message.Message>> projectMessages) { }

    Assembly assemble(String agentId, AgentProfile profile) {
        return assemble(agentId, profile, null);
    }

    private Assembly assemble(String agentId, AgentProfile profile,
            java.util.function.Consumer<List<com.cafecli.ai.message.Message>> observer) {
        if (capabilities == null) throw new IllegalStateException("factory has not been opened by a session");
        for (Capability attached : profile.capabilities()) {
            if (!capabilities.contains(attached)) {
                throw new IllegalArgumentException("the profile attaches a capability the session does not have: "
                        + attached);
            }
        }
        AgentContributions[] contributions = new AgentContributions[1];
        var prompt = new SystemPrompt(() -> profile.systemPrompt() != null ? profile.systemPrompt() : prompts.assemble(profile.prompt(),
                contributions[0] == null ? PromptContext.empty() : contributions[0].promptContext()));
        AgentContributions scope = new AgentContributions(agentId, profile.model(), prompt, events);
        contributions[0] = scope;
        try {
            for (Capability capability : capabilities) {
                if (profile.capabilities().contains(capability)) {
                    capability.attachAgent(scope);
                }
            }
            if (observer != null) {
                scope.runStart(token -> observer.accept(scope.messages()));
                LogWriter publish = (log, token) -> { observer.accept(scope.projectMessages(log)); return List.of(); };
                scope.iterationBoundaryWriter(publish);
                scope.contextOverflowWriter(publish);
                scope.iterationEnd((message, results) -> {
                    observer.accept(scope.messages());
                    return com.cafecli.core.IterationDecision.end();
                });
            }
            prompt.invalidate();
            Agent agent = Agent.builder()
                    .id(agentId)
                    .provider(provider)
                    .model(profile.model())
                    .systemPrompt(prompt.current())
                    .maxIterations(profile.maxIterations())
                    .tools(scope.tools())
                    .toolBatchTimeout(toolBatchTimeout)
                    .callbacks(scope.close())
                    .build();
            scope.bind(agent);
            agent.subscribe(events);
            return new Assembly(agent, scope.messageKinds(), prompt::invalidate, scope::projectMessages);
        } catch (RuntimeException | Error failure) {
            try { scope.cleanup(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
