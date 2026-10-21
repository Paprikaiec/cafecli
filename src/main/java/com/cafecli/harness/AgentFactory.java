package com.cafecli.harness;

import com.cafecli.ai.Provider;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentCallbacks;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptContext;

import java.util.List;
import java.util.Objects;
import java.time.Duration;

/**
 * Builds every agent of a session — the main agent, and sub-agents from ticket 17 — from an {@link AgentProfile}. For
 * each agent: run the agent hooks of the capabilities its profile attaches, in the session's declared order, with a
 * fresh {@link AgentScope}; compose what they registered into the agent's one {@link AgentCallbacks}; assemble its
 * system prompt — once, now, not per run, so the request prefix stays stable; build it; and connect it to the
 * session's event stream, so its events reach every session listener and subscriber.
 *
 * <p>The system prompt is assembled from the templates ({@code prompts/*.md} on the class path, with the user's and the
 * project's overrides under their config homes) through the prompt leaf. Its memory, external-context and skill-index
 * sections stay empty until capabilities contribute them (11, 13, 14). The leaf also writes the current date and the
 * JVM's default time zone into it.
 */
public final class AgentFactory {

    private final Provider provider;
    private final PromptAssembler prompts;
    private final List<Capability> capabilities;
    private final SessionEvents events;
    private final Duration toolBatchTimeout;

    /**
     * @param capabilities the session's capabilities in declared order; their session hooks have run
     */
    AgentFactory(Provider provider, PromptAssembler prompts, List<Capability> capabilities, SessionEvents events,
                 Duration toolBatchTimeout) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.capabilities = List.copyOf(capabilities);
        this.events = Objects.requireNonNull(events, "events");
        this.toolBatchTimeout = Objects.requireNonNull(toolBatchTimeout, "toolBatchTimeout");
    }

    /**
     * @param agentId names the agent in every event it emits
     * @throws IllegalArgumentException if the profile attaches a capability the session was not opened with: its
     *                                  session hook never ran
     */
    public Agent create(String agentId, AgentProfile profile) {
        for (Capability attached : profile.capabilities()) {
            if (!capabilities.contains(attached)) {
                throw new IllegalArgumentException("the profile attaches a capability the session does not have: "
                        + attached);
            }
        }
        AgentContributions scope = new AgentContributions(agentId);
        for (Capability capability : capabilities) {
            if (profile.capabilities().contains(capability)) {
                capability.attachAgent(scope);
            }
        }
        Agent agent = Agent.builder()
                .id(agentId)
                .provider(provider)
                .model(profile.model())
                .systemPrompt(prompts.assemble(profile.prompt(), PromptContext.empty()))
                .maxIterations(profile.maxIterations())
                .tools(scope.tools())
                .toolBatchTimeout(toolBatchTimeout)
                .callbacks(scope.close())
                .build();
        agent.subscribe(events);
        return agent;
    }
}
