package com.cafecli.harness.context;

import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cached agent prompt. Changes are requested from any thread and applied only while preparing a request. */
public final class SystemPrompt {
    private final Supplier<String> build;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private String text;

    public SystemPrompt(Supplier<String> build) {
        this.build = build;

    }
    public void invalidate() { dirty.set(true); }
    /** The already assembled prompt, without consuming invalidation or reading templates again. */
    public String cached() {
        if (text == null) throw new IllegalStateException("system prompt has not been assembled");
        return text;
    }

    public String current() {
        if (dirty.getAndSet(false) || text == null) {
            try { text = build.get(); }
            catch (RuntimeException | Error failure) { dirty.set(true); throw failure; }
        }
        return text;
    }
}
