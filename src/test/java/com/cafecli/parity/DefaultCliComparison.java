package com.cafecli.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exception13 adds only literal fixture context and exact formerly-unavailable result pairs. */
final class DefaultCliComparison {
    private static final Path FIXTURES = Path.of("src/test/resources/parity/default-cli");
    private DefaultCliComparison() { }

    static void traces(JsonNode fixture, List<JsonNode> v1, List<JsonNode> v2, boolean approved13) throws Exception {
        var expected = copy(v1);
        if (approved13) for (JsonNode record : expected) {
            if (record.path("type").asText().equals("system")) ((ObjectNode) record).put("text", system(record.path("text").asText(), fixture));
            if (record.path("type").asText().equals("user") && fixture.has("preparedUser")) {
                assertEquals(fixture.path("input").asText(), record.path("text").asText()); ((ObjectNode) record).put("text", fixture.path("preparedUser").asText());
            }
            if (record.path("type").asText().equals("tool_result")) result(record, "tool_call_id", "content", fixture);
        }
        ParityComparison.compare("default-entry-exception13", expected, v2);
    }

    static void wire(JsonNode fixture, List<JsonNode> v1, List<JsonNode> v2) throws Exception {
        var expected = copy(v1); var actual = copy(v2); assertEquals(expected.size(), actual.size(), "MAIN request count");
        for (int i = 0; i < expected.size(); i++) {
            for (JsonNode message : expected.get(i).path("messages")) {
                if (message.path("role").asText().equals("system")) ((ObjectNode) message).put("content", system(message.path("content").asText(), fixture));
                if (message.path("role").asText().equals("user") && fixture.has("preparedUser")) {
                    assertEquals(fixture.path("input").asText(), message.path("content").asText()); ((ObjectNode) message).put("content", fixture.path("preparedUser").asText());
                }
                if (message.path("role").asText().equals("tool")) result(message, "tool_call_id", "content", fixture);
            }
            tools(expected.get(i), false, fixture); tools(actual.get(i), true, fixture);
        }
        ParityComparison.compare("default-entry-wire-exceptions8-and13", expected, actual);
    }
    private static void tools(JsonNode request, boolean v2, JsonNode fixture) throws Exception {
        var definitions = new ArrayList<JsonNode>(); request.path("tools").forEach(definitions::add);
        if (v2 && fixture.path("mcp").asBoolean()) for (JsonNode known : ParityScript.JSON.readTree(FIXTURES.resolve("mcp-tools.json").toFile()))
            assertTrue(definitions.remove(known), "only the exact configured MCP definitions are added under13: " + known);
        definitions.sort(Comparator.comparing(tool -> tool.path("function").path("name").asText()));
        ((ObjectNode) request).set("tools", ParityScript.JSON.valueToTree(definitions));
    }
    private static void result(JsonNode record, String id, String field, JsonNode fixture) {
        if (!fixture.has("result13") || !record.path(id).asText().equals(fixture.path("result13").path("id").asText())) return;
        assertEquals(fixture.path("result13").path("v1").asText(), record.path(field).asText(), "exact original unavailable result");
        ((ObjectNode) record).put(field, fixture.path("result13").path("v2").asText());
    }
    private static String system(String original, JsonNode fixture) throws Exception {
        String anchor = "\n\n## Context Management\n";
        assertEquals(1, original.split(java.util.regex.Pattern.quote(anchor), -1).length - 1, "one context insertion boundary");
        assertFalse(original.contains("## Skills\n")); assertFalse(original.contains("## Project Context\n"));
        String added = fixture.path("mcp").asBoolean() ? Files.readString(FIXTURES.resolve("mcp-section.txt")).stripTrailing() + "\n\n" : "";
        added += Files.readString(FIXTURES.resolve("skill-section.txt")).stripTrailing();
        return original.replace(anchor, "\n\n" + added + anchor);
    }
    static void summaries(JsonNode expected, JsonNode actual) { assertEquals(expected, actual, "exception13 never waives evaluator fields"); }

    static void rejectsMutations(JsonNode fixture, List<JsonNode> v1, List<JsonNode> v2,
                                 List<JsonNode> wire1, List<JsonNode> wire2, JsonNode summary1, JsonNode summary2) throws Exception {
        for (int i = 0; i < v2.size(); i++) {
            JsonNode record = v2.get(i); var mutation = copy(v2);
            String field = record.has("text") ? "text" : record.has("content") ? "content" : null;
            if (field != null) {
                ((ObjectNode) mutation.get(i)).put(field, record.path(field).asText() + " unauthorized content");
                assertThrows(AssertionError.class, () -> traces(fixture, v1, mutation, true));
            }
        }
        for (String replacement : List.of("## Identity changed", "**web-access** changed", "## Context Management changed")) {
            var mutation = copy(v2); String old = replacement.substring(0, replacement.length() - " changed".length());
            ((ObjectNode) mutation.getFirst()).put("text", mutation.getFirst().path("text").asText().replace(old, replacement));
            assertThrows(AssertionError.class, () -> traces(fixture, v1, mutation, true), "unrelated system and exact skill fields remain strict");
        }
        var extra = new ArrayList<>(v2); extra.add(v2.size() - 1, ParityScript.JSON.createObjectNode().put("type", "assistant").put("text", "extra"));
        assertThrows(AssertionError.class, () -> traces(fixture, v1, extra, true));
        for (String field : List.of("provider", "model", "unexpected")) {
            var mutation = copy(v2); ((ObjectNode) mutation.getLast()).put(field, "unauthorized");
            assertThrows(AssertionError.class, () -> traces(fixture, v1, mutation, true));
        }
        var tokenTrace = copy(v2); ((ObjectNode) tokenTrace.getLast().path("tokens")).put("in", 999);
        assertThrows(AssertionError.class, () -> traces(fixture, v1, tokenTrace, true));
        for (String field : List.of("turns", "tool_calls_total")) {
            JsonNode mutation = summary2.deepCopy(); ((ObjectNode) mutation).put(field, summary2.path(field).asInt() + 1);
            assertThrows(AssertionError.class, () -> summaries(summary1, mutation));
        }
        JsonNode tokens = summary2.deepCopy(); ((ObjectNode) tokens.path("tokens")).put("in", 999);
        assertThrows(AssertionError.class, () -> summaries(summary1, tokens));
        var missingTool = copy(wire2); ((com.fasterxml.jackson.databind.node.ArrayNode) missingTool.getFirst().path("tools")).remove(0);
        assertThrows(AssertionError.class, () -> wire(fixture, wire1, missingTool), "availability is never blanket-normalized");
        var wrongSchema = copy(wire2); ((ObjectNode) wrongSchema.getFirst().path("tools").get(0).path("function")).put("description", "unauthorized");
        assertThrows(AssertionError.class, () -> wire(fixture, wire1, wrongSchema));
        if (fixture.path("mcp").asBoolean()) {
            var wrongMcp = copy(wire2);
            for (JsonNode tool : wrongMcp.getFirst().path("tools")) if (tool.path("function").path("name").asText().equals("mcp__demo__echo"))
                ((ObjectNode) tool.path("function").path("parameters")).put("unexpected", true);
            assertThrows(AssertionError.class, () -> wire(fixture, wire1, wrongMcp), "13 cannot waive changed MCP schemas");
        }
        var wrongUser = copy(wire2); ((ObjectNode) wrongUser.getFirst().path("messages").get(1)).put("content", "unauthorized prepared user");
        assertThrows(AssertionError.class, () -> wire(fixture, wire1, wrongUser));
    }
    static List<JsonNode> copy(List<JsonNode> records) { return records.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
}
