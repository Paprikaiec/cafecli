package com.cafecli.harness.hooks;

import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;
import java.util.Objects;

/** Additional PostToolUse context, posted once through the agent's boundary inbox. */
public record HookContext(String text) implements CustomEntry {
    public static final MessageKind<HookContext> KIND = MessageKind.tagged("hook-context", HookContext.class,
            "[Hook 上下文 / PostToolUse]", HookContext::text);
    public HookContext { Objects.requireNonNull(text, "text"); }
}
