package com.cafecli.core;

import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.message.AssistantMessage;

import java.util.Objects;

/** How a run ended. Typed, never text: what to tell the user is the UI's business. */
public sealed interface RunOutcome {

    /** The run ended normally; {@code message} is the last answer of the run (usually the final answer). */
    record Completed(AssistantMessage message) implements RunOutcome {
        public Completed {
            Objects.requireNonNull(message, "message");
        }
    }

    /** The run's token was cancelled; the answer of the request it cut short is not logged. */
    record Cancelled() implements RunOutcome {
    }

    /** A budget ran out; {@code reason} says which. */
    record BudgetExhausted(BudgetReason reason) implements RunOutcome {
        public BudgetExhausted {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * A request failed and could not be recovered: {@link ErrorKind#RETRYABLE} once {@code ai}'s retries are used up,
     * {@link ErrorKind#CONTEXT_OVERFLOW} when the overflow callback could not make it fit, or {@link ErrorKind#FATAL}.
     * {@code detail} is the provider's diagnostic (e.g. its error message); the UI may show it. Core adds no text of
     * its own.
     */
    record Failed(ErrorKind kind, String detail) implements RunOutcome {
        public Failed {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
