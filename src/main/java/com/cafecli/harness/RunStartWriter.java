package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;

import java.util.List;

/**
 * A log writer (CONTEXT.md 「日志写入者」) triggered at run start: the entries it returns are appended to the agent log
 * right after the run's user entry, before the first request — memory recall (11), skill bodies (14), the freshness
 * search (15). Registered through {@link AgentScope#runStartWriter}; runs on the agent's thread.
 */
@FunctionalInterface
public interface RunStartWriter {

    /**
     * @param input        what the user submitted in this run
     * @param cancellation the run's token: a writer that blocks (on a search, say) gives up when it is cancelled
     * @return the entries to append, in order; empty for none
     */
    List<CustomEntry> write(UserMessage input, CancellationToken cancellation);

    default List<CustomEntry> write(UserMessage input, CancellationToken cancellation, com.cafecli.core.ToolInvoker invoker) {
        return write(input, cancellation);
    }

    @FunctionalInterface interface WithTools extends RunStartWriter {
        @Override List<CustomEntry> write(UserMessage input, CancellationToken cancellation, com.cafecli.core.ToolInvoker invoker);
        @Override default List<CustomEntry> write(UserMessage input, CancellationToken cancellation) {
            throw new IllegalStateException("tool invocation context required");
        }
    }
}
