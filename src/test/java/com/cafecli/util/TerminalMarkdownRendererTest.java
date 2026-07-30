package com.cafecli.util;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMarkdownRendererTest {
    static {
        System.setProperty("paicli.render.color", "false");
    }

    @Test
    void rendersHeadingWithoutAnUnderlineRow() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);

        renderer.append("# Heading\n");
        renderer.finish();

        assertEquals(List.of("Heading", ""), renderer.styledLines().stream()
                .map(AttributedString::toString)
                .toList());
    }

    @Test
    void rendersThematicBreakAsExactlyOneBlankRow() {
        for (String thematicBreak : List.of("---", "***", "___")) {
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);

            renderer.append("Before\n\n" + thematicBreak + "\n\nAfter\n");
            renderer.finish();

            assertEquals(List.of("Before", "", "After"), renderer.styledLines().stream()
                    .map(AttributedString::toString)
                    .toList(), thematicBreak);
        }
    }

    @Test
    void separatesAdjacentTopLevelBlocksButKeepsItemsInsideBlocksCompact() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);

        renderer.append("Paragraph\n- first\n- second\n> quoted one\n> quoted two\nFollowing paragraph\n");
        renderer.finish();

        assertEquals(List.of(
                "Paragraph",
                "",
                "- first",
                "- second",
                "",
                "│ quoted one",
                "│ quoted two",
                "",
                "Following paragraph"), renderer.styledLines().stream()
                .map(AttributedString::toString)
                .toList());
    }

    @Test
    void keepsMixedNestedListItemsTogetherWithoutBlankRows() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);

        renderer.append("1. first\n  - nested\n2. second\n");
        renderer.finish();

        assertEquals(List.of("1. first", "  - nested", "2. second"), renderer.styledLines().stream()
                .map(AttributedString::toString)
                .toList());
    }

    @Test
    void keepsIndentedListItemContinuationInsideTheCompactListBlock() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);

        renderer.append("- first\n  continuation\n- second\n");
        renderer.finish();

        assertEquals(List.of("- first", "  continuation", "- second"),
                renderer.styledLines().stream()
                        .map(AttributedString::toString)
                        .toList());
    }

    @Test
    void compactsBlankSeparatedItemsWithinOneList() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);

        renderer.append("- first\n\n- second\n");
        renderer.finish();

        assertEquals(List.of("- first", "- second"), renderer.styledLines().stream()
                .map(AttributedString::toString)
                .toList());
    }

    @Test
    void rendersHeadingListTableAndCodeBlockToTerminalFriendlyText() {
        String markdown = """
                # 规划思考
                                
                1. **分析请求**
                - 列出当前目录
                                
                | 名称 | 说明 |
                | --- | --- |
                | src | 源码 |
                | pom.xml | Maven 配置 |
                                
                ```java
                System.out.println("hello");
                ```
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("规划思考"));
        assertTrue(rendered.contains("1. 分析请求"));
        assertTrue(rendered.contains("- 列出当前目录"));
        assertTrue(rendered.contains("| 名称"));
        assertTrue(rendered.contains("| src"));
        assertTrue(rendered.contains("源码"));
        assertFalse(rendered.contains("┌─"), rendered);
        assertFalse(rendered.contains("└─"), rendered);
        assertTrue(rendered.contains("\nSystem.out.println(\"hello\");\n"), rendered);
    }

    @Test
    void supportsIncrementalStreamingAppend() {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stream = new java.io.PrintStream(output);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream);

        renderer.append("## 标题\n- 第一");
        renderer.append("项\n- 第二项\n");
        renderer.finish();

        String rendered = output.toString();
        assertTrue(rendered.contains("标题"));
        assertTrue(rendered.contains("- 第一项"));
        assertTrue(rendered.contains("- 第二项"));
    }

    @Test
    void preservesNestedListIndentation() {
        String markdown = """
                1. 总体分析
                  - 第一层补充
                    - 第二层补充
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("1. 总体分析"));
        assertTrue(rendered.contains("  - 第一层补充"));
        assertTrue(rendered.contains("    - 第二层补充"));
    }

    @Test
    void fallsBackToKeyValueLayoutForLongTwoColumnTable() {
        String markdown = """
                | 目录名 | 说明 |
                | --- | --- |
                | src/main/java/com/cafecli | 这里存放 Cafecli 的主要 Java 源码实现与相关模块 |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("目录名 / 说明"));
        assertTrue(rendered.contains("- src/main/java/com/cafecli"));
        assertTrue(rendered.contains("这里存放 Cafecli 的主要 Java 源码实现与相关模块"));
    }

    @Test
    void wrapsWideMultiColumnTableInsideTerminalWidth() {
        String markdown = """
                | 特性 | StepFun (Step) | Kimi | GLM | DeepSeek |
                | --- | --- | --- | --- | --- |
                | 基础 URL | https://api.stepfun.com/v1 | https://api.moonshot.ai/v1 | 动态选择（glm-5v用多模态API，其他用编码API） | https://api.deepseek.com/chat/completions |
                | 推理能力 | ✅（需配置 reasoningformat="deepseek-style"） | ✅（需发送推理历史） | ✅ | ✅ |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 72);

        assertTrue(rendered.contains("| 特性"));
        assertFalse(rendered.contains("https://api.deepseek.com/chat/completions |"));
        for (String line : rendered.split("\\R")) {
            assertTrue(line.length() <= 72, "line exceeds table width: " + line);
        }
    }

    @Test
    void producesStyledLinesWithoutLosingStateAcrossStreamingChunks() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);
        renderer.append("| Key | Value |\n| --- | --- |\n| one | two |\n\n```ja");
        renderer.append("va\nSystem.out.println(1);\n```\n");
        renderer.finish();

        List<AttributedString> lines = renderer.styledLines();
        String rendered = String.join("\n", lines.stream().map(AttributedString::toString).toList());
        assertTrue(rendered.contains("| Key"), rendered);
        assertTrue(rendered.contains("| one"), rendered);
        assertFalse(rendered.contains("┌─"), rendered);
        assertFalse(rendered.contains("└─"), rendered);
        assertTrue(rendered.contains("\nSystem.out.println(1);\n"), rendered);
    }

    @Test
    void tailPreviewPreservesPendingTableStateWithoutMutatingTheParser() {
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(80);
        renderer.append("| Key | Value |\n| --- | --- |\n| one | two |\n");

        assertTrue(renderer.styledLines().isEmpty());
        List<AttributedString> preview = renderer.previewStyledLines("");
        String rendered = String.join("\n", preview.stream().map(AttributedString::toString).toList());
        assertTrue(rendered.contains("| Key"), rendered);
        assertTrue(rendered.contains("| one"), rendered);
        assertTrue(renderer.styledLines().isEmpty(), "preview must not flush the live parser");
    }
}
