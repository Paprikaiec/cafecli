package com.cafecli.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Default-deny comparator: only nondeterministic duration is normalized initially. */
final class ParityComparison {
    private ParityComparison() { }
    static List<JsonNode> trace(Path file) throws Exception {
        return Files.readAllLines(file).stream().map(line -> {
            try {
                JsonNode record = ParityScript.JSON.readTree(line);
                if (record.path("type").asText().equals("result")) {
                    org.junit.jupiter.api.Assertions.assertTrue(record.path("wall_ms").isIntegralNumber()
                                    && record.path("wall_ms").canConvertToLong() && record.path("wall_ms").asLong() >= 0,
                            "result.wall_ms must be present, integral and nonnegative before timing normalization");
                    ((ObjectNode) record).put("wall_ms", 0);
                }
                return record;
            } catch (Exception e) { throw new IllegalArgumentException(e); }
        }).toList();
    }
    static void compare(String scenario, List<JsonNode> v1, List<JsonNode> v2) {
        if (scenario.equals("headless-parallel-children")) {
            v1 = taskIds(v1); v2 = taskIds(v2);
        }
        if (scenario.equals("budget-completed-usage") || scenario.equals("failed-completed-usage")) {
            v1 = new ArrayList<>(v1);
            assertEquals(tokens(0, 0, 0), v1.getLast().path("tokens"), "exception 12: actual v1 nonnatural-run usage");
            assertEquals(tokens(3, 2, 1), v2.getLast().path("tokens"), "exception 12: precisely the completed MAIN response, excluding partial usage");
            var result = (ObjectNode) v1.getLast().deepCopy(); result.set("tokens", tokens(3, 2, 1)); v1.set(v1.size() - 1, result);
        }
        if (scenario.equals("headless-snapshot")) {
            v1 = new ArrayList<>(v1);
            assertEquals("❌ 找不到最近第 1 个 pre-turn 快照", v1.get(7).path("content").asText(), "exception 11: actual v1 headless has no anchor");
            org.junit.jupiter.api.Assertions.assertTrue(v2.get(7).path("content").asText().matches("✅ 已恢复到快照 [0-9a-f]{10}\\n   写回文件: 1\\n   删除文件: 0"),
                    "exception 11: anchored headless restore writes exactly the one fixture file");
            var result = (ObjectNode) v1.get(7).deepCopy(); result.set("content", v2.get(7).path("content")); v1.set(7, result);
        }
        String retainedFinalReasoning = switch (scenario) {
            case "retained-reasoning" -> "checked fixture reasoning";
            case "control-only-reasoning" -> "\u0000";
            case "mixed-unicode-blank-reasoning" -> " real ";
            case "mixed-controls-reasoning" -> " after ";
            case "nonblank-unicode-reasoning" -> "\u2003real\u2003";
            default -> null;
        };
        if (retainedFinalReasoning != null) {
            v1 = new ArrayList<>(v1); int index = v1.size() - 2;
            assertEquals("assistant", v1.get(index).path("type").asText());
            org.junit.jupiter.api.Assertions.assertFalse(v1.get(index).has("tool_calls"));
            org.junit.jupiter.api.Assertions.assertFalse(v1.get(index).has("reasoning"));
            assertEquals(retainedFinalReasoning, v2.get(index).path("reasoning").asText(), "exception 12: only exact retained final reasoning");
            var assistant = (ObjectNode) v1.get(index).deepCopy(); assistant.put("reasoning", retainedFinalReasoning); v1.set(index, assistant);
        }
        if (scenario.equals("compaction")) {
            v1 = new ArrayList<>(v1);
            assertEquals("[已压缩的历史对话摘要]\nretained fixture goal", v1.get(1).path("text").asText());
            assertEquals(v1.get(1), v2.get(1), "exception 5 preserves the actual summary");
            assertEquals(ParityScript.JSON.createObjectNode().put("type", "assistant").put("text", "好的，我已了解之前的上下文，请继续。"), v1.remove(2),
                    "exception 5: only v1's compaction acknowledgement is absent");
            var result = (ObjectNode) v1.getLast().deepCopy();
            assertEquals(v2.getLast().path("turns").asInt() + 1, result.path("turns").asInt(), "exception 5: one derived acknowledgement turn");
            result.put("turns", v2.getLast().path("turns").asInt()); v1.set(v1.size() - 1, result);
        }
        if (scenario.equals("busy-notification")) {
            v1 = new ArrayList<>(v1);
            assertEquals(ParityScript.JSON.createObjectNode().put("type", "assistant").put("text", "parent complete"), v1.remove(6),
                    "exception 10: v1 finishes its current run before the queued completion starts another");
            assertEquals("<task-notification task_id=\"task_1\">\n[task_1 · done]\n\nchild fixture result\n</task-notification>",
                    v1.get(6).path("text").asText(), "exception 10 cannot change notification bytes");
            var result = (ObjectNode) v1.getLast().deepCopy();
            assertEquals(v2.getLast().path("turns").asInt() + 1, result.path("turns").asInt(), "exception 10: exactly one derived extra run response");
            result.put("turns", v2.getLast().path("turns").asInt()); v1.set(v1.size() - 1, result);
        }
        assertEquals(v1.size(), v2.size(), scenario + ": compatible trace record count");
        for (int i = 0; i < v1.size(); i++) equal(scenario + "/trace/" + i, v1.get(i), v2.get(i));
    }

    static void summaries(String scenario, JsonNode v1, JsonNode v2) {
        if (scenario.equals("budget-completed-usage") || scenario.equals("failed-completed-usage")) {
            assertEquals(tokens(0, 0, 0), v1.path("tokens"), "exception 12: v1 evaluator reads actual zero result usage");
            assertEquals(tokens(3, 2, 1), v2.path("tokens"), "exception 12: evaluator counts only completed MAIN usage");
            v1 = v1.deepCopy(); ((ObjectNode) v1).set("tokens", tokens(3, 2, 1));
        }
        if (scenario.equals("compaction") || scenario.equals("busy-notification")) {
            assertEquals(v2.path("turns").asInt() + 1, v1.path("turns").asInt(), "exception 5/10: eval counts precisely one derived v1 assistant");
            v1 = v1.deepCopy(); ((ObjectNode) v1).put("turns", v2.path("turns").asInt());
        }
        assertEquals(v1, v2, scenario + ": actual eval normalizer summary");
    }

    private static void equal(String path, JsonNode expected, JsonNode actual) {
        if (expected.equals(actual)) return;
        if (expected.isObject() && actual.isObject()) {
            var fields = new java.util.TreeSet<String>(); expected.fieldNames().forEachRemaining(fields::add); actual.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) equal(path + "/" + field, expected.path(field), actual.path(field));
        } else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) equal(path + "/" + i, expected.get(i), actual.get(i));
        } else org.junit.jupiter.api.Assertions.fail(path + ": unexplained difference; v1=" + preview(expected) + "; v2=" + preview(actual));
    }
    private static String preview(JsonNode node) { String text = node.toString(); return text.length() <= 180 ? text : text.substring(0, 180) + "…"; }
    private static JsonNode tokens(int input, int output, int cached) {
        return ParityScript.JSON.createObjectNode().put("in", input).put("out", output).put("cache", cached);
    }

    private static List<JsonNode> taskIds(List<JsonNode> records) {
        var ids = new java.util.HashSet<String>();
        var normalized = new ArrayList<JsonNode>();
        for (JsonNode record : records) {
            record = record.deepCopy();
            if (record.path("type").asText().equals("tool_result")) {
                var matcher = java.util.regex.Pattern.compile("^\\[(task_[1-9][0-9]*) · done]\\n\\n").matcher(record.path("content").asText());
                org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), "completed task result retains its status and body");
                org.junit.jupiter.api.Assertions.assertTrue(ids.add(matcher.group(1)), "the two actual tasks have distinct ids");
                String content = record.path("content").asText();
                ((ObjectNode) record).put("content", "[task-for-" + record.path("tool_call_id").asText() + " · done]\n\n" + content.substring(matcher.end()));
            }
            normalized.add(record);
        }
        assertEquals(2, ids.size());
        return normalized;
    }

    static void rejectsUnexplainedDifferences(String scenario, List<JsonNode> v1, List<JsonNode> v2, JsonNode summary1, JsonNode summary2) {
        var changed = v2.stream().<JsonNode>map(JsonNode::deepCopy).toList();
        ((ObjectNode) changed.getFirst()).put("unexpected", true);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> compare(scenario, v1, changed), "new fields never gain a waiver");
        for (int i = 1; i < v2.size(); i++) {
            var record = v2.get(i);
            if (record.has("tool_calls")) for (String callField : List.of("name", "arguments")) {
                var mutation = v2.stream().<JsonNode>map(JsonNode::deepCopy).toList();
                ((ObjectNode) mutation.get(i).path("tool_calls").get(0)).put(callField, "unauthorized");
                org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> compare(scenario, v1, mutation), "tool " + callField + " remains strict");
            }
            String field = record.has("reasoning") ? "reasoning" : record.has("text") ? "text" : record.has("content") ? "content" : null;
            if (field == null) continue;
            var mutation = v2.stream().<JsonNode>map(JsonNode::deepCopy).toList();
            ((ObjectNode) mutation.get(i)).put(field, record.path(field).asText() + " unauthorized mutation");
            org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> compare(scenario, v1, mutation), "exception cannot waive " + field + " content");
        }
        var extra = new ArrayList<>(v2); extra.add(v2.size() - 1, ParityScript.JSON.createObjectNode().put("type", "assistant").put("text", "extra"));
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> compare(scenario, v1, extra), "extra assistants remain strict");
        for (String field : List.of("provider", "model")) {
            var mutation = v2.stream().<JsonNode>map(JsonNode::deepCopy).toList();
            ((ObjectNode) mutation.getLast()).put(field, "unauthorized");
            org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> compare(scenario, v1, mutation));
        }
        for (String field : List.of("tool_calls_total", "turns")) {
            JsonNode mutation = summary2.deepCopy(); ((ObjectNode) mutation).put(field, summary2.path(field).asInt() + 1);
            org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> summaries(scenario, summary1, mutation), "eval " + field + " remains strict");
        }
        JsonNode mutatedTokens = summary2.deepCopy(); ((ObjectNode) mutatedTokens.path("tokens")).put("in", summary2.path("tokens").path("in").asInt() + 1);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> summaries(scenario, summary1, mutatedTokens), "eval usage is never blanket-dropped");
        if (!v1.equals(v2)) org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> compare("plain-answer", v1, v2), "exceptions apply only to their named scenario");
    }
}
