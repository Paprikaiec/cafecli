package com.cafecli.ai;

/**
 * How a failed model request is classified. Each layer handles exactly the kinds that belong to it, by kind — never
 * by reading the message text.
 */
public enum ErrorKind {
    /** Transient (429, 5xx, network). Retried inside {@code ai}; seen by callers only once retries are used up. */
    RETRYABLE,
    /** The request did not fit the model's context window. The caller may compact and retry. */
    CONTEXT_OVERFLOW,
    /** The request was cancelled through its {@link CancellationToken}. */
    ABORTED,
    /** Anything else; retrying the same request will not help. */
    FATAL
}
