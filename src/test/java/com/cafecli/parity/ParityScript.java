package com.cafecli.parity;

import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.ai.message.Usage;
import com.cafecli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One readable external-model script; counters are independent for MAIN, children and compaction. */
final class ParityScript {
    static final ObjectMapper JSON = new ObjectMapper();
    final JsonNode scenario;
    final List<List<LlmClient.Message>> legacyRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> positions = new ConcurrentHashMap<>();
    final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch finishLegacyStream = new java.util.concurrent.CountDownLatch(1);
    final java.util.Map<String, java.util.concurrent.CountDownLatch> entered = new ConcurrentHashMap<>();
    final java.util.Map<String, java.util.concurrent.CountDownLatch> releases = new ConcurrentHashMap<>();
    final java.util.Map<String, Thread> workers = new ConcurrentHashMap<>();

    ParityScript(Path path) throws IOException { scenario = JSON.readTree(path.toFile()); }

    String name() { return scenario.path("name").asText(); }

    List<String> inputs() {
        var inputs = new ArrayList<String>();
        for (JsonNode input : scenario.path("inputs")) inputs.add(input.isTextual() ? input.asText()
                : input.path("text").asText().repeat(input.path("repeat").asInt()));
        return List.copyOf(inputs);
    }

    synchronized JsonNode next(String system, String firstUser) {
        String route = system.equals("你是一个对话摘要助手，只输出摘要本身，不输出元描述。") ? "summary"
                : scenario.path("routes").path(firstUser).asText("main");
        if (firstUser.startsWith("CHILD:") && route.equals("main")) throw new AssertionError("unrouted child request: " + firstUser);
        int index = positions.computeIfAbsent(route, ignored -> new AtomicInteger()).getAndIncrement();
        JsonNode response = scenario.path("responses").path(route).path(index);
        if (response.isMissingNode()) throw new AssertionError(name() + ": no " + route + " response " + index);
        return response;
    }

    FakeProvider provider() {
        return FakeProvider.responding(request -> {
            String first = request.context().messages().stream().filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast).map(user -> user.content().stream().filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast).map(TextBlock::text).collect(java.util.stream.Collectors.joining()))
                    .findFirst().orElse("");
            JsonNode node = next(request.context().systemPrompt(), first);
            barrier(node);
            var response = FakeResponse.builder();
            if (node.has("thinking")) response.thinking(node.path("thinking").asText());
            if (node.has("text")) response.text(node.path("text").asText());
            if (node.path("holdUntilCancel").asBoolean()) response.holdUntilCancelled(held::countDown);
            for (JsonNode call : node.path("toolCalls")) response.toolCall(call.path("id").asText(), call.path("name").asText(), arguments(call));
            response.usage(new Usage(node.path("usage").path("input").asInt(), node.path("usage").path("output").asInt(), node.path("usage").path("cached").asInt()));
            if (node.has("error")) return response.fail(switch (node.path("error").path("kind").asText()) {
                case "fatal" -> com.cafecli.ai.ErrorKind.FATAL;
                case "retryable" -> com.cafecli.ai.ErrorKind.RETRYABLE;
                case "overflow" -> com.cafecli.ai.ErrorKind.CONTEXT_OVERFLOW;
                case "aborted" -> com.cafecli.ai.ErrorKind.ABORTED;
                default -> throw new AssertionError("unknown scripted error kind");
            }, node.path("error").path("message").asText());
            return response.build();
        });
    }

    LlmClient client() {
        return new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                legacyRequests.add(List.copyOf(messages));
                JsonNode node = next(messages.getFirst().content(), messages.stream().filter(m -> m.role().equals("user")).map(Message::content).findFirst().orElse(""));
                barrier(node);
                if (node.path("holdUntilCancel").asBoolean()) {
                    listener.onContentDelta(node.path("text").asText());
                    held.countDown();
                    try { finishLegacyStream.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                }
                if (node.has("error")) throw new IOException(node.path("error").path("message").asText());
                var calls = new ArrayList<ToolCall>();
                for (JsonNode call : node.path("toolCalls")) calls.add(new ToolCall(call.path("id").asText(), new ToolCall.Function(call.path("name").asText(), arguments(call))));
                return new ChatResponse("assistant", node.path("text").asText(""), node.path("thinking").asText(null), calls.isEmpty() ? null : calls,
                        node.path("usage").path("input").asInt(), node.path("usage").path("output").asInt(), node.path("usage").path("cached").asInt());
            }
            @Override public String getProviderName() { return "fake"; }
            @Override public String getModelName() { return "fake-model"; }
            @Override public int maxContextWindow() { return scenario.path("window").asInt(128000); }
        };
    }

    private static String arguments(JsonNode call) {
        JsonNode args = call.path("arguments");
        return args.isTextual() ? args.asText() : args.toString();
    }

    void awaitEntered(String lane) throws InterruptedException {
        org.junit.jupiter.api.Assertions.assertTrue(entered.computeIfAbsent(lane, ignored -> new java.util.concurrent.CountDownLatch(1))
                .await(5, java.util.concurrent.TimeUnit.SECONDS), name() + ": " + lane + " entered");
    }
    void release(String lane) { releases.computeIfAbsent(lane, ignored -> new java.util.concurrent.CountDownLatch(1)).countDown(); }
    private void barrier(JsonNode response) {
        if (!response.has("barrier")) return;
        String lane = response.path("barrier").asText(); workers.put(lane, Thread.currentThread());
        entered.computeIfAbsent(lane, ignored -> new java.util.concurrent.CountDownLatch(1)).countDown();
        try { org.junit.jupiter.api.Assertions.assertTrue(releases.computeIfAbsent(lane, ignored -> new java.util.concurrent.CountDownLatch(1))
                .await(5, java.util.concurrent.TimeUnit.SECONDS), name() + ": " + lane + " released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
