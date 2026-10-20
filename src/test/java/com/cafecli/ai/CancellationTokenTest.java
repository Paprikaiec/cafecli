package com.cafecli.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a provider is told about cancellation: registrations on {@code ai}'s own token. */
class CancellationTokenTest {

    @Test
    void aClosedRegistrationIsNotRunOnCancel() {
        CancellationToken token = new CancellationToken();
        List<String> ran = new ArrayList<>();
        token.onCancel(() -> ran.add("still registered"));
        token.onCancel(() -> ran.add("closed")).close();

        token.cancel();

        assertEquals(List.of("still registered"), ran);
    }

    @Test
    void oneFailingListenerDoesNotKeepTheOthersFromRunningAndItsFailureSurfaces() {
        CancellationToken token = new CancellationToken();
        List<String> ran = new ArrayList<>();
        token.onCancel(() -> {
            throw new IllegalStateException("abort failed");
        });
        token.onCancel(() -> ran.add("second"));

        IllegalStateException e = assertThrows(IllegalStateException.class, token::cancel);

        assertEquals("abort failed", e.getMessage());
        assertEquals(List.of("second"), ran);
        assertTrue(token.isCancelled());
    }
}
