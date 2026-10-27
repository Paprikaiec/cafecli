package com.cafecli.harness;

import com.cafecli.core.RunOutcome;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Existing run assertions remain explicit about the facade's completed-submission branch. */
final class SessionTestSupport {
    private SessionTestSupport() { }
    static RunOutcome run(AgentSession session, String input) {
        return assertInstanceOf(SubmitResult.Completed.class, session.submit(input)).outcome();
    }
}
