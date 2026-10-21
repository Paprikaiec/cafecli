package com.cafecli.harness;

import com.cafecli.core.log.CustomEntry;

/** An agent-scoped capability producer: enqueue only; the loop is the sole writer of the agent log. */
@FunctionalInterface
public interface BoundaryInbox {
    void post(CustomEntry entry);
}
