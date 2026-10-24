package com.cafecli.harness;

import com.cafecli.core.RunOutcome;
import java.util.Objects;

/** Interactive run completion after the agent's lifecycle cleanup, including exceptional callbacks. */
public sealed interface SessionCompletion {
    record Completed(RunOutcome outcome) implements SessionCompletion {
        public Completed { Objects.requireNonNull(outcome); }
    }
    record Failed(Throwable failure) implements SessionCompletion {
        public Failed { Objects.requireNonNull(failure); }
    }
}
