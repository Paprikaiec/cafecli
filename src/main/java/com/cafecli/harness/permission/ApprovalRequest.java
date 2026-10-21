package com.cafecli.harness.permission;

import com.cafecli.ai.message.ToolCall;
import java.util.Objects;

/** Immutable approval card, tagged with the requesting agent. */
public record ApprovalRequest(String agentId, ToolCall call) {
    public ApprovalRequest {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(call, "call");
    }
}
