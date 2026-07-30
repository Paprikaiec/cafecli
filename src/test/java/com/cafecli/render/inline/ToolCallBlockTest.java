package com.cafecli.render.inline;

import com.cafecli.llm.LlmClient;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallBlockTest {

    @Test
    void toolLabelsKeepTheirDimStyleAndTextWithoutEmoji() {
        Terminal terminal = Mockito.mock(Terminal.class);
        List<LlmClient.ToolCall> calls = List.of(
                call("web_search", "{\"query\":\"terminal rendering\"}"),
                call("web_fetch", "{\"url\":\"https://example.com/docs\"}"),
                call("search_code", "{\"query\":\"LiveRegionStrip\"}"),
                call("read_file", "{\"path\":\"src/Main.java\"}"),
                call("write_file", "{\"path\":\"notes.md\"}"),
                call("list_dir", "{\"path\":\"src\"}"),
                call("execute_command", "{\"command\":\"mvn test\"}"),
                call("create_project", "{\"name\":\"demo\"}"),
                call("save_memory", "{\"fact\":\"stable preference\"}"),
                call("mcp__github__search", "{}"),
                call("custom_tool", "{}"));
        StringBuilder rendered = new StringBuilder();

        for (LlmClient.ToolCall call : calls) {
            ToolCallBlock block = new ToolCallBlock(terminal, List.of(call));
            AttributedString collapsed = block.render(120, FoldState.COLLAPSED).getFirst();
            assertNotEquals(AttributedStyle.DEFAULT, collapsed.styleAt(0));
            rendered.append(collapsed).append('\n');
            block.render(120, FoldState.EXPANDED).forEach(line -> rendered.append(line).append('\n'));
        }

        String labels = rendered.toString();
        assertFalse(containsEmoji(labels), labels);
        assertFalse(labels.contains("▎"), labels);
        assertTrue(labels.contains("ReadFile(src/Main.java)"), labels);
        assertFalse(labels.contains("ctrl+o to expand"), labels);
        assertFalse(labels.contains("collapse"), labels);
        assertTrue(labels.contains("读取 1 个文件"), labels);
        assertTrue(labels.contains("调用 MCP 工具 github.search"), labels);
        assertTrue(labels.contains("custom_tool × 1"), labels);
    }

    private static LlmClient.ToolCall call(String name, String arguments) {
        return new LlmClient.ToolCall(name, new LlmClient.ToolCall.Function(name, arguments));
    }

    private static boolean containsEmoji(String text) {
        return text.codePoints().anyMatch(codePoint ->
                codePoint >= 0x1F300
                        || codePoint >= 0x2600 && codePoint <= 0x27BF
                        || codePoint == 0xFE0F);
    }
}
