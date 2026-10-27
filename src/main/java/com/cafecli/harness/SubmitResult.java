package com.cafecli.harness;

import com.cafecli.core.RunOutcome;
import java.util.Objects;

/** Whether ordinary input ran, was blocked before the run, or was accepted as steering. */
public sealed interface SubmitResult {
    record Blocked(String reason) implements SubmitResult {}
    record Completed(RunOutcome outcome) implements SubmitResult {
        public Completed { Objects.requireNonNull(outcome, "outcome"); }
    }
    record Queued() implements SubmitResult { }
    record SteeringAccepted() implements SubmitResult { }
}
