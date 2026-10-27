package com.cafecli.ai.fake;

import com.cafecli.ai.AssistantStream;
import com.cafecli.ai.Context;
import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.Model;
import com.cafecli.ai.Provider;
import com.cafecli.ai.StreamOptions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A provider that answers from a script instead of a model, and records every request it receives — so a test (or the
 * parity harness, or a demo run without an API key) can assert what the model would have seen.
 *
 * <p>It honors the {@link AssistantStream} contract like any adapter: realistic deltas (see {@link FakeResponse}), any
 * error kind on demand, and cancellation. It blocks only at an explicit hold-until-cancelled script step. A fixed script that runs out answers with a
 * {@link ErrorKind#FATAL} error saying so. Thread-safe.
 */
public final class FakeProvider implements Provider {

    /** One request as received: the model and the context, both immutable. */
    public record Request(Model model, Context context) {
    }

    private final Function<Request, FakeResponse> responder;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private FakeProvider(Function<Request, FakeResponse> responder) {
        this.responder = responder;
    }

    /** Answers the n-th request with the n-th response, whatever the request says. */
    public static FakeProvider scripted(List<FakeResponse> responses) {
        List<FakeResponse> script = List.copyOf(responses);
        AtomicInteger next = new AtomicInteger();
        return new FakeProvider(request -> {
            int index = next.getAndIncrement();
            if (index < script.size()) {
                return script.get(index);
            }
            return FakeResponse.builder().fail(ErrorKind.FATAL, "the fake provider's script ran out: request "
                    + (index + 1) + " has no response (the script has " + script.size() + ")");
        });
    }

    /** Answers each request with whatever {@code responder} chooses for it. */
    public static FakeProvider responding(Function<Request, FakeResponse> responder) {
        return new FakeProvider(responder);
    }

    /** Every request received so far, in the order received. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public AssistantStream stream(Model model, Context context, StreamOptions options) {
        Request request = new Request(model, context);
        requests.add(request);
        return AssistantStream.of(model, options.cancellation(), respondTo(request).source(options.cancellation()));
    }

    private FakeResponse respondTo(Request request) {
        try {
            FakeResponse response = responder.apply(request);
            if (response != null) {
                return response;
            }
            return FakeResponse.builder().fail(ErrorKind.FATAL, "the fake provider's responder returned no response");
        } catch (RuntimeException e) {
            return FakeResponse.builder().fail(ErrorKind.FATAL, "the fake provider's responder failed: " + e);
        }
    }
}
