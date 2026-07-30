package com.cafecli.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

public final class StructuredContentRenderer {
    private static final int MAX_SCHEMA_DEPTH = 2;
    private static final int MAX_OBJECT_SCHEMA_KEYS = 10;
    private static final int MAX_ARRAY_SAMPLE_ELEMENTS = 3;
    private static final int MAX_SAMPLE_DEPTH = 2;
    private static final int MAX_STRING_CHARS = 120;

    public String renderReferenceDigest(JsonNode payload) {
        return "Schema: " + renderSchema(payload)
                + "\nSamples: " + renderSample(payload);
    }

    public int compactLength(JsonNode payload) {
        return payload == null ? 0 : payload.toString().length();
    }

    private String renderSchema(JsonNode node) {
        return renderSchema(node, 0);
    }

    private String renderSchema(JsonNode node, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return "[]";
            }
            return "[" + renderSchema(node.get(0), depth + 1) + "]";
        }
        if (node.isObject()) {
            if (depth > MAX_SCHEMA_DEPTH) {
                return "{...}";
            }
            StringBuilder sb = new StringBuilder("{");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            int emitted = 0;
            while (fields.hasNext() && emitted < MAX_OBJECT_SCHEMA_KEYS) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (emitted > 0) {
                    sb.append(", ");
                }
                sb.append(field.getKey()).append(": ").append(renderSchema(field.getValue(), depth + 1));
                emitted++;
            }
            sb.append("}");
            return sb.toString();
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "value";
    }

    private String renderSample(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isArray()) {
            int total = node.size();
            int shown = Math.min(total, MAX_ARRAY_SAMPLE_ELEMENTS);
            StringBuilder sb = new StringBuilder();
            sb.append("array total=").append(total)
                    .append(", first ").append(shown)
                    .append(", omitted=").append(total - shown)
                    .append(": [");
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(renderSampleValue(node.get(i), 1, null));
            }
            sb.append("]");
            return sb.toString();
        }
        return renderSampleValue(node, 0, null);
    }

    private String renderSampleValue(JsonNode node, int depth, String key) {
        if (isSensitiveKey(key)) {
            return "[REDACTED]";
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            if (depth >= MAX_SAMPLE_DEPTH) {
                return "{...}";
            }
            StringBuilder sb = new StringBuilder("{");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            int emitted = 0;
            while (fields.hasNext() && emitted < MAX_OBJECT_SCHEMA_KEYS) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (emitted > 0) {
                    sb.append(", ");
                }
                sb.append(field.getKey()).append(": ").append(renderSampleValue(field.getValue(), depth + 1, field.getKey()));
                emitted++;
            }
            sb.append("}");
            return sb.toString();
        }
        if (node.isArray()) {
            if (depth >= MAX_SAMPLE_DEPTH) {
                return "[...]";
            }
            int shown = Math.min(node.size(), MAX_ARRAY_SAMPLE_ELEMENTS);
            StringBuilder sb = new StringBuilder();
            sb.append("array total=").append(node.size())
                    .append(", first ").append(shown)
                    .append(", omitted=").append(node.size() - shown)
                    .append(": [");
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(renderSampleValue(node.get(i), depth, null));
            }
            sb.append("]");
            return sb.toString();
        }
        if (node.isTextual()) {
            return "\"" + escapeString(truncateString(node.asText())) + "\"";
        }
        return node.toString();
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        return lower.equals("authorization")
                || lower.equals("apikey")
                || lower.equals("api-key")
                || lower.equals("api_key")
                || lower.equals("set-cookie")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("cookie");
    }

    private static String truncateString(String value) {
        if (value.length() <= MAX_STRING_CHARS) {
            return value;
        }
        return value.substring(0, MAX_STRING_CHARS - 3) + "...";
    }

    private static String escapeString(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
