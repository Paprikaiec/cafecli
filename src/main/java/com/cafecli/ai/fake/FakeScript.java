package com.cafecli.ai.fake;

import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.message.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Loads a {@link FakeProvider} script from a JSON file:
 *
 * <pre>{@code
 * {"responses": [
 *   {"thinking": "Need the file.",
 *    "text": "Reading it.",
 *    "toolCalls": [{"id": "call_1", "name": "read_file", "arguments": {"path": "a.txt"}}],
 *    "usage": {"input": 10, "output": 5, "cached": 0}},
 *   {"text": "Half an answer", "error": {"kind": "overflow", "message": "prompt is too long"}}
 * ]}
 * }</pre>
 *
 * <p>One response per model request, in order. Every field of a response is optional, but a response needs at least
 * one of {@code thinking}, {@code text}, {@code toolCalls}, {@code error}. A response streams its thinking, then its
 * text, then its tool calls, then its usage, and ends normally — stop reason tool use if it has tool calls, end of
 * turn otherwise — or, if it has an {@code error}, fails after that content with the error's kind:
 * {@code retryable}, {@code overflow}, {@code aborted} or {@code fatal}. The error {@code message} is optional.
 *
 * <ul>
 *   <li>{@code id}, {@code name}: required, not blank.</li>
 *   <li>{@code arguments}: a JSON object, sent as its compact JSON text; or a string, sent as exactly that raw text
 *       (which need not be valid JSON); absent means {@code {}}.</li>
 *   <li>{@code usage}: {@code input} (the whole prompt), {@code output}, {@code cached} (the part of the input served
 *       from the prompt cache); each defaults to 0.</li>
 * </ul>
 *
 * <p>The format deliberately holds nothing a v1 {@code LlmClient.ChatResponse} cannot express (text, reasoning text,
 * tool calls with id / name / arguments, input / output / cached tokens — plus errors, which a v1 stub throws), so
 * the parity harness can feed one file to both v1 and v2. Retry events and explicit stop reasons are therefore
 * available only through {@link FakeResponse.Builder}. Unknown fields are rejected, naming where they are.
 */
public final class FakeScript {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RESPONSE_FIELDS = Set.of("thinking", "text", "toolCalls", "usage", "error");
    private static final Set<String> TOOL_CALL_FIELDS = Set.of("id", "name", "arguments");
    private static final Set<String> USAGE_FIELDS = Set.of("input", "output", "cached");
    private static final Set<String> ERROR_FIELDS = Set.of("kind", "message");

    private FakeScript() {
    }

    /**
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if it is not a valid script; the message names the offending place
     */
    public static List<FakeResponse> load(Path path) throws IOException {
        String json = Files.readString(path);
        try {
            return parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("fake script " + path + " is not valid JSON: " + e.getOriginalMessage(),
                    e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("fake script " + path + ": " + e.getMessage(), e);
        }
    }

    private static List<FakeResponse> parse(JsonNode root) {
        requireFields(root, "the script", Set.of("responses"));
        JsonNode responses = root.path("responses");
        if (!responses.isArray()) {
            throw new IllegalArgumentException("\"responses\" must be an array");
        }
        List<FakeResponse> script = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            script.add(response(responses.get(i), "responses[" + i + "]"));
        }
        return script;
    }

    private static FakeResponse response(JsonNode node, String where) {
        requireFields(node, where, RESPONSE_FIELDS);
        if (!node.has("thinking") && !node.has("text") && !node.has("toolCalls") && !node.has("error")) {
            throw new IllegalArgumentException(where + " is empty: it needs thinking, text, toolCalls or error");
        }
        FakeResponse.Builder response = FakeResponse.builder();
        if (node.has("thinking")) {
            response.thinking(string(node.get("thinking"), where + ".thinking"));
        }
        if (node.has("text")) {
            response.text(string(node.get("text"), where + ".text"));
        }
        if (node.has("toolCalls")) {
            JsonNode calls = node.get("toolCalls");
            if (!calls.isArray()) {
                throw new IllegalArgumentException(where + ".toolCalls must be an array");
            }
            for (int i = 0; i < calls.size(); i++) {
                toolCall(response, calls.get(i), where + ".toolCalls[" + i + "]");
            }
        }
        if (node.has("usage")) {
            response.usage(usage(node.get("usage"), where + ".usage"));
        }
        if (node.has("error")) {
            return error(response, node.get("error"), where + ".error");
        }
        return response.build();
    }

    private static void toolCall(FakeResponse.Builder response, JsonNode node, String where) {
        requireFields(node, where, TOOL_CALL_FIELDS);
        String id = nonBlank(node.path("id"), where + ".id");
        String name = nonBlank(node.path("name"), where + ".name");
        JsonNode arguments = node.path("arguments");
        String raw;
        if (arguments.isMissingNode()) {
            raw = "{}";
        } else if (arguments.isTextual()) {
            raw = arguments.asText();
        } else if (arguments.isObject()) {
            raw = arguments.toString();
        } else {
            throw new IllegalArgumentException(where + ".arguments must be an object or a string");
        }
        response.toolCall(id, name, raw);
    }

    private static Usage usage(JsonNode node, String where) {
        requireFields(node, where, USAGE_FIELDS);
        return new Usage(count(node, "input", where), count(node, "output", where), count(node, "cached", where));
    }

    private static int count(JsonNode usage, String field, String where) {
        JsonNode value = usage.path(field);
        if (value.isMissingNode()) {
            return 0;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber() || value.asInt() < 0) {
            throw new IllegalArgumentException(where + "." + field + " must be a non-negative integer");
        }
        return value.asInt();
    }

    private static FakeResponse error(FakeResponse.Builder response, JsonNode node, String where) {
        requireFields(node, where, ERROR_FIELDS);
        String kindName = string(node.path("kind"), where + ".kind");
        ErrorKind kind = switch (kindName) {
            case "retryable" -> ErrorKind.RETRYABLE;
            case "overflow" -> ErrorKind.CONTEXT_OVERFLOW;
            case "aborted" -> ErrorKind.ABORTED;
            case "fatal" -> ErrorKind.FATAL;
            default -> throw new IllegalArgumentException(where + ".kind must be one of retryable, overflow, aborted, "
                    + "fatal; was \"" + kindName + "\"");
        };
        String message = node.has("message")
                ? string(node.get("message"), where + ".message")
                : "scripted " + kindName + " error";
        return response.fail(kind, message);
    }

    private static void requireFields(JsonNode node, String where, Set<String> allowed) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " must be a JSON object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(where + " has an unknown field \"" + name + "\"");
            }
        }
    }

    /** v1 silently drops a tool call with a blank id, so a blank one would make v1 and v2 replay different scripts. */
    private static String nonBlank(JsonNode value, String where) {
        String text = string(value, where);
        if (text.isBlank()) {
            throw new IllegalArgumentException(where + " must not be blank");
        }
        return text;
    }

    private static String string(JsonNode value, String where) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException(where + " must be a string");
        }
        return value.asText();
    }
}
