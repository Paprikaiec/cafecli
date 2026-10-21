package com.cafecli.harness;

import com.cafecli.core.RunOutcome;
import java.util.Objects;

/** Whether a submission ran synchronously or was accepted by the run already in flight. */
public sealed interface SubmitResult {
    record Completed(RunOutcome outcome) implements SubmitResult {
        public Completed { Objects.requireNonNull(outcome, "outcome"); }
    }
    record SteeringAccepted() implements SubmitResult { }
}
