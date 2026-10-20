package com.cafecli.ai;

import com.cafecli.ai.message.AssistantMessage;

/**
 * A model request that ended in a {@link StreamEvent.Failed} event, thrown by {@link AssistantStream#complete()}.
 * Handle it by {@link #kind()}, never by the message text.
 */
public final class ProviderException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorKind kind;
    private final transient AssistantMessage partial;

    public ProviderException(StreamEvent.Failed failure) {
        super(failure.message());
        this.kind = failure.kind();
        this.partial = failure.partial();
    }

    public ErrorKind kind() {
        return kind;
    }

    /** Whatever the stream had delivered before it failed. */
    public AssistantMessage partial() {
        return partial;
    }
}
