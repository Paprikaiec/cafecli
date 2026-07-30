package com.cafecli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredContentRendererTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reportsCompactJsonLengthAndSchemaSignature() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                [
                  {
                    "requestId": "req-1",
                    "url": "https://example.test/checkout",
                    "status": 500,
                    "timing": {
                      "blocked": 1,
                      "dns": 2,
                      "connect": 3
                    }
                  }
                ]
                """);
        StructuredContentRenderer renderer = new StructuredContentRenderer();

        String digest = renderer.renderReferenceDigest(payload);

        assertEquals(payload.toString().length(), renderer.compactLength(payload));
        assertTrue(digest.contains("Schema: [{requestId: string, url: string, status: number, timing: {blocked: number, dns: number, connect: number}}]"), digest);
    }

    @Test
    void samplesArraysWithTotalFirstThreeElementsAndOmittedCount() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                [
                  {"requestId": "req-1", "status": 200},
                  {"requestId": "req-2", "status": 201},
                  {"requestId": "req-3", "status": 202},
                  {"requestId": "req-4", "status": 203},
                  {"requestId": "req-5", "status": 204}
                ]
                """);
        StructuredContentRenderer renderer = new StructuredContentRenderer();

        String digest = renderer.renderReferenceDigest(payload);

        assertTrue(digest.contains("Samples: array total=5, first 3, omitted=2:"), digest);
        assertTrue(digest.contains("req-1"), digest);
        assertTrue(digest.contains("req-2"), digest);
        assertTrue(digest.contains("req-3"), digest);
        assertTrue(!digest.contains("req-4"), digest);
        assertTrue(!digest.contains("req-5"), digest);
    }

    @Test
    void samplesNestedArraysInsideObjects() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "requests": [
                    {"requestId": "req-1", "token": "raw-token"},
                    {"requestId": "req-2", "token": "raw-token"},
                    {"requestId": "req-3", "token": "raw-token"},
                    {"requestId": "req-4", "token": "raw-token"}
                  ]
                }
                """);
        StructuredContentRenderer renderer = new StructuredContentRenderer();

        String digest = renderer.renderReferenceDigest(payload);

        assertTrue(digest.contains("requests: array total=4, first 3, omitted=1"), digest);
        assertTrue(digest.contains("req-1"), digest);
        assertTrue(digest.contains("req-2"), digest);
        assertTrue(digest.contains("req-3"), digest);
        assertTrue(!digest.contains("req-4"), digest);
        assertTrue(digest.contains("token: [REDACTED]"), digest);
        assertTrue(!digest.contains("raw-token"), digest);
    }

    @Test
    void redactsSensitiveValuesTruncatesLongStringsAndCollapsesDeepValues() throws Exception {
        String longMessage = "a".repeat(160);
        JsonNode payload = MAPPER.readTree("""
                {
                  "authorization": "Bearer raw-token",
                  "apiKey": "sk-live-123",
                  "set-cookie": "session=raw-cookie",
                  "message": "%s",
                  "outer": {
                    "inner": {
                      "deep": {
                        "value": "hidden"
                      }
                    }
                  }
                }
                """.formatted(longMessage));
        StructuredContentRenderer renderer = new StructuredContentRenderer();

        String digest = renderer.renderReferenceDigest(payload);

        assertTrue(digest.contains("authorization: [REDACTED]"), digest);
        assertTrue(digest.contains("apiKey: [REDACTED]"), digest);
        assertTrue(digest.contains("set-cookie: [REDACTED]"), digest);
        assertTrue(!digest.contains("raw-token"), digest);
        assertTrue(!digest.contains("sk-live-123"), digest);
        assertTrue(!digest.contains("raw-cookie"), digest);
        assertTrue(!digest.contains(longMessage), digest);
        assertTrue(digest.contains("\"" + "a".repeat(117) + "...\""), digest);
        assertTrue(digest.contains("outer: {inner: {...}}"), digest);
        assertTrue(!digest.contains("hidden"), digest);
    }
}
