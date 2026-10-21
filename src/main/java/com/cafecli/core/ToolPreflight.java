package com.cafecli.core;

import com.cafecli.ai.message.ToolCall;
import java.util.Objects;

/** A preflight participant either forwards an effective call or supplies its result without execution. */
public sealed interface ToolPreflight {
    ToolCall call();
    record Allow(ToolCall call) implements ToolPreflight {
        public Allow { Objects.requireNonNull(call, "call"); }
    }
    record Complete(ToolCall call, ToolResult result) implements ToolPreflight {
        public Complete { Objects.requireNonNull(call, "call"); Objects.requireNonNull(result, "result"); }
    }
    static ToolPreflight allow(ToolCall call) { return new Allow(call); }
    static ToolPreflight complete(ToolCall call, ToolResult result) { return new Complete(call, result); }
}
