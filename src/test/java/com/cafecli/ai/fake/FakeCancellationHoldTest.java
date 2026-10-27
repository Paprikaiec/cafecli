package com.cafecli.ai.fake;

import com.cafecli.ai.*;
import com.cafecli.ai.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class FakeCancellationHoldTest {
    @Test void aPartialResponseRemainsInFlightUntilItsActualRequestTokenIsCancelled() throws Exception {
        var held = new CountDownLatch(1);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().text("partial")
                .holdUntilCancelled(held::countDown).text("must never finish").build()));
        var token = new CancellationToken();
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> {
                try (var stream = provider.stream(new Model("fake", "fake", "fake", 128000, 8192, Set.of()),
                        new Context("system", List.of(UserMessage.text("cancel")), List.of()), new StreamOptions(token))) {
                    return stream.complete();
                }
            });
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone(), "the script cannot race ahead of cancellation");
                token.cancel();
                var failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertEquals(ErrorKind.ABORTED, assertInstanceOf(ProviderException.class, failure.getCause()).kind());
            } finally { token.cancel(); }
        }
    }
}
