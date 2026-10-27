package com.cafecli.harness.permission;

import com.cafecli.ai.message.ToolCall;

/** Harness-owned approval events, delivered synchronously on the requesting agent's loop thread. */
public sealed interface PermissionEvent {
    String agentId();
    ToolCall call();
    record HookAllowed(String agentId, ToolCall call, String identity) implements PermissionEvent {}
    record Requested(String agentId, ToolCall call) implements PermissionEvent {}
    /** The effective call differs only in arguments; refused calls retain their original arguments. */
    record Decided(String agentId, ToolCall call, ToolCall effectiveCall, ApprovalResult reply,
                   long durationMs) implements PermissionEvent {}
}
