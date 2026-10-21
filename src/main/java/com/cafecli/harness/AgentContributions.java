package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentCallbacks;
import com.cafecli.core.log.CustomEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@link AgentScope} of one agent being built: collects what its attached capabilities register, then composes
 * it into the agent's one {@link AgentCallbacks}. Contributions of one kind run in registration order — capabilities in
 * declared order, each in the order it registered.
 */
final class AgentContributions implements AgentScope {

    private final String agentId;
    private final List<RunStartWriter> runStartWriters = new ArrayList<>();
    private boolean closed;

    AgentContributions(String agentId) {
        this.agentId = agentId;
    }

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

    /**
     * Ends registration — the agent hooks have run — and composes the contributions. Every callback with nothing
     * contributed keeps core's default.
     */
    AgentCallbacks close() {
        closed = true;
        List<RunStartWriter> writers = List.copyOf(runStartWriters);
        return new AgentCallbacks() {
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
