package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.prompt.PromptMode;

import java.util.List;
import java.util.Objects;

/**
 * What an agent is built from ({@link AgentFactory}): the main agent and, from ticket 17, every sub-agent.
 *
 * @param model         the model it asks
 * @param prompt        the template its system prompt is assembled from, once, when the agent is built
 * @param capabilities  the session's capabilities attached to it; every other capability contributes nothing to it
 *                      (Capability Closure). Their hooks run in the session's declared order, not in this list's.
 * @param maxIterations its hard iteration cap per run
 */
public record AgentProfile(Model model, PromptMode prompt, List<Capability> capabilities, int maxIterations) {

    public AgentProfile {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");
        capabilities = List.copyOf(capabilities);
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive, was " + maxIterations);
        }
    }

    /** The main agent: the agent template, with every capability of the session attached. */
    public static AgentProfile mainAgent(Model model, List<Capability> sessionCapabilities, int maxIterations) {
        return new AgentProfile(model, PromptMode.AGENT, sessionCapabilities, maxIterations);
    }
}
