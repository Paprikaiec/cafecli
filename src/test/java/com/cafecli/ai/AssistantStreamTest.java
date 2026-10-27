package com.cafecli.ai;

import com.cafecli.ai.StreamEvent.Failed;
import com.cafecli.ai.StreamEvent.Start;
import com.cafecli.ai.StreamEvent.TextDelta;
import com.cafecli.ai.StreamEvent.TextStart;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.Usage;
import com.cafecli.ai.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream contract every provider gets for free, driven through hand-written providers that behave like a real
 * adapter would: blocking on I/O, throwing, or stopping short.
 */
class AssistantStreamTest {

    private static final Model MODEL = new Model("m", "p", "test", 128_000, 8_192, Set.of());
    private static final Context CONTEXT = new Context("", List.of(UserMessage.text("hi")), List.of());

    @Test
    void cancellingWakesAProviderBlockedInIoAndEndsTheStreamAborted() throws Exception {
        CountDownLatch blockedInRead = new CountDownLatch(1);
        Provider hanging = (model, context, options) -> {
            CountDownLatch connection = new CountDownLatch(1);
            // An adapter aborts its HTTP call here; this one releases the "socket read" below.
            CancellationToken.Registration abortCall = options.cancellation().onCancel(connection::countDown);
            return AssistantStream.of(model, options.cancellation(), new AssistantStream.Source() {
                @Override
                public boolean pull(AssistantStream.Emitter out) throws Exception {
                    out.textDelta("Let me think");
                    blockedInRead.countDown();
                    connection.await();
                    throw new IOException("Socket closed");
                }

                @Override
                public void close() {
                    abortCall.close();
                }
            });
        };
        CancellationToken cancellation = new CancellationToken();

        ExecutorService consumer = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<List<StreamEvent>> events =
                    consumer.submit(() -> drain(hanging.stream(MODEL, CONTEXT, new StreamOptions(cancellation))));
            assertTrue(blockedInRead.await(5, TimeUnit.SECONDS), "the provider never started reading");

            cancellation.cancel();

            assertEquals(List.of(
                    new Start(),
                    new TextStart(0),
                    new TextDelta(0, "Let me think"),
                    new Failed(ErrorKind.ABORTED, "request cancelled", partial("Let me think", StopReason.ABORTED))),
                    events.get(5, TimeUnit.SECONDS));
        } finally {
            // Not close(): that waits for a consumer that, if this test fails, is still parked in the provider.
            consumer.shutdownNow();
        }
    }

    @Test
    void aRequestCancelledBeforeItStartsIsAbortedWithoutBeingPulledAndItsCallIsAbortedAtOnce() {
        AtomicBoolean callAborted = new AtomicBoolean();
        AtomicBoolean pulled = new AtomicBoolean();
        Provider provider = (model, context, options) -> {
            options.cancellation().onCancel(() -> callAborted.set(true));
            return AssistantStream.of(model, options.cancellation(), out -> {
                pulled.set(true);
                out.done(StopReason.END_TURN);
                return true;
            });
        };
        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();

        List<StreamEvent> events = drain(provider.stream(MODEL, CONTEXT, new StreamOptions(cancellation)));

        assertEquals(List.of(
                new Start(),
                new Failed(ErrorKind.ABORTED, "request cancelled",
                        new AssistantMessage(List.of(), StopReason.ABORTED, Usage.ZERO, "p", "m"))), events);
        assertTrue(callAborted.get(), "a provider registering after cancellation is told at once");
        assertFalse(pulled.get(), "a cancelled request is never pulled");
    }

    @Test
    void aProviderThatThrowsEndsTheStreamWithOneFatalErrorAfterWhatItProduced() {
        Provider broken = (model, context, options) -> AssistantStream.of(model, options.cancellation(), out -> {
            out.textDelta("Partial");
            throw new IllegalStateException("unexpected chunk");
        });

        List<StreamEvent> events = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> drain(broken.stream(MODEL, CONTEXT, StreamOptions.defaults())));

        assertEquals(List.of(new Start(), new TextStart(0), new TextDelta(0, "Partial")), events.subList(0, 3));
        Failed failed = assertInstanceOf(Failed.class, events.get(3));
        assertEquals(ErrorKind.FATAL, failed.kind());
        assertTrue(failed.message().contains("unexpected chunk"), failed.message());
        assertEquals(partial("Partial", StopReason.ERROR), failed.partial());
        assertEquals(4, events.size());
    }

    @Test
    void aProviderThatRunsOutWithoutFinishingEndsTheStreamWithOneFatalError() {
        Provider truncated = (model, context, options) -> {
            Iterator<String> lines = List.of("Partial").iterator();
            return AssistantStream.of(model, options.cancellation(), out -> {
                if (!lines.hasNext()) {
                    return false;
                }
                out.textDelta(lines.next());
                return true;
            });
        };

        List<StreamEvent> events = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> drain(truncated.stream(MODEL, CONTEXT, StreamOptions.defaults())));

        assertEquals(List.of(
                new Start(),
                new TextStart(0),
                new TextDelta(0, "Partial"),
                new Failed(ErrorKind.FATAL, "the provider stream ended without a terminal event",
                        partial("Partial", StopReason.ERROR))), events);
    }

    private static AssistantMessage partial(String text, StopReason stopReason) {
        return new AssistantMessage(List.of(new TextBlock(text)), stopReason, Usage.ZERO, "p", "m");
    }

    private static List<StreamEvent> drain(AssistantStream stream) {
        List<StreamEvent> events = new ArrayList<>();
        try (stream) {
            for (StreamEvent event : stream) {
                events.add(event);
            }
        }
        return events;
    }
}
