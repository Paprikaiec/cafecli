package com.cafecli.harness;

import com.cafecli.ai.*;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.openai.*;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.context.CompactionCapability;
import com.cafecli.harness.context.Summary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFailureSessionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void fakeRetryStatusReachesCoreWithoutAnotherRequestIterationOrAssistant() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().retry(2, 3, "HTTP 429")
                .text("answer").build()));
        try (var session = session(provider, "fake")) {
            List<AgentEvent> events = new ArrayList<>(); session.subscribe(events::add);
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "hello"));
            assertEquals(List.of(new AgentEvent.Retry(new AgentEvent.Origin("main"), new StreamEvent.Retry(2, 3, "HTTP 429"))),
                    events.stream().filter(AgentEvent.Retry.class::isInstance).toList());
            assertEquals(1, provider.requests().size());
            assertEquals(1, events.stream().filter(AgentEvent.IterationStart.class::isInstance).count());
            assertEquals(1, session.log().stream().filter(AssistantEntry.class::isInstance).count());
        }
    }

    @ParameterizedTest
    @CsvSource({"deepseek,false", "glm,false", "step,false", "kimi,false", "freellmapi,false",
            "deepseek,true", "glm,true", "step,true", "kimi,true", "freellmapi,true"})
    void eachProviderOverflowCompactsThenRetriesOnceAndASecondOverflowStaysTyped(String name, boolean secondOverflow) throws Exception {
        // DeepSeek/Step/Free gateway overflow code is an explicit OpenAI-shape assumption, not a captured response.
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            int n = requests.size();
            boolean overflow = n == 4 || n == 6 && secondOverflow;
            String body = overflow ? overflowBody(name) : success(n == 5 ? "summary" : "answer");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(overflow ? 400 : 200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
            var provider = provider(name, url);
            try (var session = session(provider, name)) {
                session.submit("one"); session.submit("two"); session.submit("three");
                var original = session.log();
                String bytes = session.messageKinds().json().write(original);
                List<AgentEvent> events = new ArrayList<>(); session.subscribe(events::add);
                var outcome = SessionTestSupport.run(session, "four");
                if (secondOverflow) assertEquals(ErrorKind.CONTEXT_OVERFLOW, assertInstanceOf(RunOutcome.Failed.class, outcome).kind());
                else assertInstanceOf(RunOutcome.Completed.class, outcome);
                assertEquals(6, requests.size());
                assertEquals("[已压缩的历史对话摘要]\nsummary", requests.getLast().path("messages").get(1).path("content").asText());
                assertEquals(1, events.stream().filter(AgentEvent.Compaction.class::isInstance).count());
                assertEquals(1, events.stream().filter(AgentEvent.IterationStart.class::isInstance).count());
                assertEquals(0, events.stream().filter(AgentEvent.Retry.class::isInstance).count(), "overflow is a core recovery, not a transient ai retry");
                assertEquals(1, session.log().stream().filter(Summary.class::isInstance).count());
                assertEquals(secondOverflow ? 3 : 4, session.log().stream().filter(AssistantEntry.class::isInstance).count());
                assertEquals(bytes, session.messageKinds().json().write(session.log().subList(0, original.size())));
            }
        } finally { server.stop(0); }
    }

    @Test void internalSummaryRetriesStayInsideAiWithoutPublishingAnOrdinaryAssistantOrRetry() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"), FakeResponse.text("three"),
                FakeResponse.builder().fail(ErrorKind.CONTEXT_OVERFLOW, "overflow"),
                FakeResponse.builder().retry(2, 3, "HTTP 503").text("summary").build(), FakeResponse.text("answer")));
        try (var session = session(provider, "fake")) {
            session.submit("one"); session.submit("two"); session.submit("three");
            List<AgentEvent> events = new ArrayList<>(); session.subscribe(events::add);
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "four"));
            assertEquals(6, provider.requests().size());
            assertEquals(0, events.stream().filter(AgentEvent.Retry.class::isInstance).count());
            assertEquals(2, events.stream().filter(AgentEvent.MessageEnd.class::isInstance).count());
            assertEquals(List.of("one", "two", "three", "answer"), session.log().stream()
                    .filter(AssistantEntry.class::isInstance).map(AssistantEntry.class::cast).map(a -> a.message().text()).toList());
        }
    }

    private AgentSession session(Provider provider, String name) {
        return AgentSession.builder().provider(provider)
                .model(new Model("fixture", name, "openai-completions", 1000000, 8192, Set.of()))
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(new CompactionCapability(provider))).build();
    }

    private static String success(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n";
    }
    private static String overflowBody(String name) {
        return switch (name) {
            case "glm" -> "{\"error\":{\"code\":\"1261\"}}";
            case "kimi" -> "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Input token length too long\"}}";
            default -> "{\"error\":{\"code\":\"context_length_exceeded\"}}";
        };
    }
    private static Provider provider(String name, String url) {
        return switch (name) {
            case "glm" -> new GlmProvider("fixture-key", url, url, HttpTimeouts.DEFAULT);
            case "step" -> new StepProvider("fixture-key", url, HttpTimeouts.DEFAULT);
            case "kimi" -> new KimiProvider("fixture-key", url, HttpTimeouts.DEFAULT);
            case "freellmapi" -> new FreeLlmApiProvider("fixture-key", url, HttpTimeouts.DEFAULT);
            default -> new DeepSeekProvider("fixture-key", url);
        };
    }
}
