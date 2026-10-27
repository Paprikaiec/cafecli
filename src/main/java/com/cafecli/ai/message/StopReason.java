package com.cafecli.ai.message;

/** Why an assistant message ended. */
public enum StopReason {
    /** The model finished its answer. */
    END_TURN,
    /** The model stopped to have its tool calls executed. */
    TOOL_USE,
    /** The output hit the maximum output length. */
    LENGTH,
    /** The request failed; the message is a partial. */
    ERROR,
    /** The request was cancelled; the message is a partial. */
    ABORTED
}
