package com.cafecli.core;

import com.cafecli.ai.message.ToolCall;

/** Completed non-model invocation, delivered only on the agent owner thread. Null parent means run-start work. */
public record AuxiliaryToolCall(ToolCall call, ToolResult result, String parentCallId, com.cafecli.ai.CancellationToken cancellation) { }
