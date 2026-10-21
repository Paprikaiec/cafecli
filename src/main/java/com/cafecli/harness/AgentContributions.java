package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentCallbacks;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolFailure;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.CustomEntry;

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
    private final List<BiFunction<ToolCall, CancellationToken, ToolResult>> beforeTools = new ArrayList<>();
    private final List<BiFunction<ToolCall, ToolResult, ToolResult>> afterTools = new ArrayList<>();
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

    @Override
    public void tool(AgentTool tool) {
        requireOpen();
        tools.add(Objects.requireNonNull(tool, "tool"));
    }

    @Override
    public void beforeToolCall(BiFunction<ToolCall, CancellationToken, ToolResult> participant) {
        requireOpen();
        beforeTools.add(Objects.requireNonNull(participant, "participant"));
    }

    @Override
    public void afterToolCall(BiFunction<ToolCall, ToolResult, ToolResult> participant) {
        requireOpen();
        afterTools.add(Objects.requireNonNull(participant, "participant"));
    }

    List<AgentTool> tools() {
        return List.copyOf(tools);
    }

    /**
     * Ends registration — the agent hooks have run — and composes the contributions. Every callback with nothing
     * contributed keeps core's default.
     */
    AgentCallbacks close() {
        closed = true;
        List<RunStartWriter> writers = List.copyOf(runStartWriters);
        var before = List.copyOf(beforeTools);
        var after = List.copyOf(afterTools);
        return new AgentCallbacks() {
            @Override
            public ToolResult beforeToolCall(ToolCall call, CancellationToken cancellation) {
                for (var participant : before) {
                    ToolResult result = participant.apply(call, cancellation);
                    if (result != null) return result;
                }
                return null;
            }

            @Override
            public ToolResult afterToolCall(ToolCall call, ToolResult result) {
                for (var participant : after) result = Objects.requireNonNull(participant.apply(call, result), "result");
                return result;
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
