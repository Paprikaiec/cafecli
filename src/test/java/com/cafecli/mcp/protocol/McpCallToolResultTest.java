package com.cafecli.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.tool.ToolOutput;
import com.cafecli.image.ImageReferenceParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCallToolResultTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void smallImageBecomesImagePart() {
        String base64 = "aGVsbG8=";
        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, base64, "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertTrue(output.hasImageParts(), "小图片应进入 imageParts");
        assertEquals(1, output.imageParts().size());
        assertEquals(base64, output.imageParts().get(0).base64());
        assertTrue(output.text().contains("base64Length=" + base64.length()));
        assertFalse(output.text().contains("超过"));
    }

    @Test
    void oversizedImageFallsBackToTextOnly() {
        int approxBytes = (int) (ImageReferenceParser.MAX_IMAGE_BYTES + 1024);
        int base64Length = (approxBytes * 4 / 3) + 4;
        StringBuilder sb = new StringBuilder(base64Length);
        for (int i = 0; i < base64Length; i++) {
            sb.append('A');
        }

        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, sb.toString(), "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertFalse(output.hasImageParts(), "超过上限的图片不应进入 imageParts");
        assertTrue(output.text().contains("超过"));
        assertTrue(output.text().contains("take_snapshot"));
    }

    @Test
    void emptyImageDataKeepsFallbackOnly() {
        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, "", "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertFalse(output.hasImageParts());
        assertTrue(output.text().contains("base64Length=0"));
    }

    @Test
    void structuredContentBecomesTypedBlockWithoutLosingTextCompatibility() throws Exception {
        McpCallToolResult result = MAPPER.readValue("""
                {
                  "content": [
                    {"type": "text", "text": "network snapshot"}
                  ],
                  "structuredContent": {
                    "requests": [
                      {"requestId": "req-200", "status": 500}
                    ]
                  },
                  "isError": false
                }
                """, McpCallToolResult.class);

        ToolOutput output = result.toToolOutput();

        assertTrue(output.text().contains("network snapshot"));
        ToolOutput.Block structuredBlock = output.blocks().stream()
                .filter(block -> block.type() == ToolOutput.BlockType.STRUCTURED_JSON)
                .findFirst()
                .orElseThrow();
        assertEquals("req-200", structuredBlock.structuredJson().path("requests").get(0).path("requestId").asText());
        assertTrue(output.renderTextForLlm().contains("network snapshot"));
        assertTrue(output.renderTextForLlm().contains("req-200"));
    }
}
