package com.cafecli.harness.permission;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.ToolPreflight;
import com.cafecli.core.ToolResult;
import java.util.Objects;

/** Invocation-local approval routing, folded before approval. A completion never invokes a leaf. */
public record PermissionRoute(Mode mode, ToolResult result) {
    public enum Mode { CONTINUE, ALLOW, ASK, COMPLETE }
    public static final PermissionRoute CONTINUE = new PermissionRoute(Mode.CONTINUE, null);
    public static final PermissionRoute ALLOW = new PermissionRoute(Mode.ALLOW, null);
    public static final PermissionRoute ASK = new PermissionRoute(Mode.ASK, null);
    public PermissionRoute {
        Objects.requireNonNull(mode, "mode");
        if ((mode == Mode.COMPLETE) != (result != null)) throw new IllegalArgumentException("completion requires a result");
    }
    public static PermissionRoute complete(ToolResult result) { return new PermissionRoute(Mode.COMPLETE, result); }

    @FunctionalInterface
    public interface Approval {
        ToolPreflight apply(ToolCall call, CancellationToken cancellation, PermissionRoute route);
    }
}
