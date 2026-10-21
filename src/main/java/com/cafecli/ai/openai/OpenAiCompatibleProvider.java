package com.cafecli.ai.openai;

import com.cafecli.ai.*;
import com.cafecli.ai.message.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Shared OpenAI chat-completions wire adapter. Conversation state belongs to the caller; each stream holds only its
 * HTTP exchange. Provider quirks stay in subclasses, without changing the neutral event contract.
 */
public abstract class OpenAiCompatibleProvider implements Provider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OkHttpClient http;
    private final String apiKey;
    private final String endpoint;

    protected OpenAiCompatibleProvider(String apiKey, String baseUrl, HttpTimeouts timeouts) {
        this.apiKey = apiKey;
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        this.endpoint = trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions";
        this.http = new OkHttpClient.Builder()
                .connectTimeout(timeouts.connect(), TimeUnit.SECONDS)
                .readTimeout(timeouts.read(), TimeUnit.SECONDS)
                .writeTimeout(timeouts.write(), TimeUnit.SECONDS)
                .callTimeout(timeouts.call(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    protected boolean sendThinkingHistory() {
        return false;
    }

    @Override
    public final AssistantStream stream(Model model, Context context, StreamOptions options) {
        return AssistantStream.of(model, options.cancellation(), new Exchange(model, context, options.cancellation()));
    }

    private ObjectNode request(Model model, Context context) {
        ObjectNode body = JSON.createObjectNode().put("model", model.id()).put("stream", true);
        ArrayNode messages = body.putArray("messages");
        if (!context.systemPrompt().isEmpty()) {
            messages.addObject().put("role", "system").put("content", context.systemPrompt());
        }
        for (Message message : context.messages()) {
            ObjectNode wire = messages.addObject();
            switch (message) {
                case UserMessage user -> {
                    wire.put("role", "user");
                    content(wire, user.content());
                }
                case ToolResultMessage tool -> {
                    wire.put("role", "tool").put("tool_call_id", tool.toolCallId());
                    content(wire, tool.content());
                }
                case AssistantMessage assistant -> {
                    wire.put("role", "assistant").put("content", assistant.text());
                    StringBuilder thinking = new StringBuilder();
                    for (AssistantContent block : assistant.content()) {
                        if (block instanceof ThinkingBlock thought) thinking.append(thought.thinking());
                    }
                    if (sendThinkingHistory() && !thinking.toString().isBlank()) {
                        wire.put("reasoning_content", thinking.toString());
                    }
                    if (!assistant.toolCalls().isEmpty()) {
                        ArrayNode calls = wire.putArray("tool_calls");
                        for (ToolCall call : assistant.toolCalls()) {
                            calls.addObject().put("id", call.id()).put("type", "function")
                                    .putObject("function").put("name", call.name()).put("arguments", call.arguments());
                        }
                    }
                }
            }
        }
        if (!context.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : context.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name()).put("description", tool.description()).set("parameters", tool.parameters());
            }
        }
        return body;
    }

    private static void content(ObjectNode wire, List<UserContent> blocks) {
        boolean images = blocks.stream().anyMatch(ImageBlock.class::isInstance);
        if (!images) {
            StringBuilder text = new StringBuilder();
            for (UserContent block : blocks) text.append(((TextBlock) block).text());
            wire.put("content", text.toString());
            return;
        }
        ArrayNode content = wire.putArray("content");
        for (UserContent block : blocks) {
            switch (block) {
                case TextBlock text -> content.addObject().put("type", "text").put("text", text.text());
                case ImageBlock image -> content.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:" + image.mimeType() + ";base64," + image.base64());
            }
        }
    }

    private final class Exchange implements AssistantStream.Source {
        private final Model model;
        private final Context context;
        private final CancellationToken cancellation;
        // The emitter has one open block. Buffer indexed calls until their ids and complete names are known,
        // then replay the original argument chunks consecutively, in source order. Text between chunks is safe.
        private final Map<Integer, CallParts> calls = new TreeMap<>();
        private Response response;
        private BufferedSource source;
        private CancellationToken.Registration registration;
        private Usage usage = Usage.ZERO;
        private String finishReason;

        private Exchange(Model model, Context context, CancellationToken cancellation) {
            this.model = model;
            this.context = context;
            this.cancellation = cancellation;
        }

        @Override
        public boolean pull(AssistantStream.Emitter out) throws IOException {
            if (source == null) {
                Request request = new Request.Builder().url(endpoint)
                        .header("Authorization", "Bearer " + apiKey)
                        .post(RequestBody.create(request(model, context).toString(), MediaType.get("application/json")))
                        .build();
                Call call = http.newCall(request);
                registration = cancellation.onCancel(call::cancel);
                try {
                    response = call.execute();
                } catch (IOException e) {
                    out.fail(ErrorKind.RETRYABLE, "HTTP transport failed");
                    return false;
                }
                if (!response.isSuccessful()) {
                    out.fail(response.code() == 429 || response.code() >= 500 ? ErrorKind.RETRYABLE : ErrorKind.FATAL,
                            "HTTP " + response.code());
                    return false;
                }
                if (response.body() == null) throw new IOException("empty response body");
                source = response.body().source();
            }
            String line;
            try {
                line = source.readUtf8Line();
            } catch (IOException e) {
                out.fail(ErrorKind.RETRYABLE, "HTTP stream interrupted");
                return false;
            }
            if (line == null) {
                finish(out);
                return false;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) return true;
            String data = trimmed.substring(5).trim();
            if (data.isEmpty()) return true;
            if (data.equals("[DONE]")) {
                finish(out);
                return false;
            }
            JsonNode root = JSON.readTree(data);
            if (root.hasNonNull("usage")) {
                JsonNode u = root.path("usage");
                int cached = u.path("cached_tokens").asInt(usage.cachedInputTokens());
                cached = u.path("prompt_cache_hit_tokens").asInt(cached);
                cached = u.path("input_cache_hit_tokens").asInt(cached);
                cached = u.path("prompt_tokens_details").path("cached_tokens").asInt(cached);
                cached = u.path("input_tokens_details").path("cached_tokens").asInt(cached);
                usage = new Usage(u.path("prompt_tokens").asInt(usage.inputTokens()),
                        u.path("completion_tokens").asInt(usage.outputTokens()), cached);
                out.usage(usage);
            }
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.hasNonNull("delta") ? choice.path("delta") : choice.path("message");
            out.thinkingDelta(reasoning(delta));
            out.textDelta(delta.path("content").asText(""));
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(calls.size());
                    if (index < 0) throw new IOException("negative tool call index");
                    CallParts parts = calls.computeIfAbsent(index, ignored -> new CallParts());
                    if (!tc.path("id").asText("").isEmpty()) parts.id = tc.path("id").asText();
                    parts.name.append(tc.path("function").path("name").asText(""));
                    String arguments = tc.path("function").path("arguments").asText("");
                    if (!arguments.isEmpty()) parts.arguments.add(arguments);
                }
            }
            if (choice.hasNonNull("finish_reason")) finishReason = choice.path("finish_reason").asText();
            return true;
        }

        private void finish(AssistantStream.Emitter out) throws IOException {
            if (finishReason == null) throw new IOException("provider stream ended without a finish reason");
            if (finishReason.equals("content_filter")) {
                out.fail(ErrorKind.FATAL, "provider rejected content (content_filter)");
                return;
            }
            StopReason reason = switch (finishReason) {
                case "stop" -> StopReason.END_TURN;
                case "tool_calls", "function_call" -> StopReason.TOOL_USE;
                case "length" -> StopReason.LENGTH;
                default -> throw new IOException("unknown finish reason: " + finishReason);
            };
            for (CallParts parts : calls.values()) {
                if (parts.id == null || parts.id.isBlank() || parts.name.toString().isBlank()) {
                    throw new IOException("tool call is missing id or name");
                }
                out.toolCallStart(parts.id, parts.name.toString());
                for (String chunk : parts.arguments) out.toolCallDelta(chunk);
            }
            out.done(reason);
        }

        @Override
        public void close() {
            if (response != null) response.close();
            if (registration != null) registration.close();
        }
    }

    private static String reasoning(JsonNode delta) {
        String text = delta.path("reasoning_content").asText("");
        if (!text.isEmpty()) return text;
        text = delta.path("reasoning").asText("");
        if (!text.isEmpty()) return text;
        StringBuilder details = new StringBuilder();
        JsonNode nodes = delta.path("reasoning_details");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                String part = node.path("text").asText("");
                details.append(part.isEmpty() ? node.path("content").asText("") : part);
            }
        }
        return details.toString();
    }

    private static final class CallParts {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final List<String> arguments = new ArrayList<>();
    }
}
