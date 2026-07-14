package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStreamRendererTest {

    @Test
    void shouldNotPrintEmptyReasoningHeadingBeforeTextIsFlushable() throws Exception {
        LlmClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            renderer.onReasoningDelta("继续查看 src 目录下的包结构");
            String afterDelta = output.toString(StandardCharsets.UTF_8);
            assertFalse(afterDelta.contains("思考过程"),
                    "没有完整行时不应先打印空的思考标题: " + afterDelta);

            invokeNoArg(renderer, "resetBetweenIterations");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("思考过程"));
        assertTrue(rendered.contains("继续查看 src 目录下的包结构"));
    }

    @Test
    void shouldIgnoreWhitespaceOnlyReasoningAcrossIterationReset() throws Exception {
        LlmClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.onReasoningDelta("  \n");
            invokeNoArg(renderer, "resetBetweenIterations");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("思考过程"),
                "空白 reasoning 不应打印空的思考标题: " + rendered);
    }

    @Test
    void shouldPrintReasoningHeadingOnlyOnceAcrossToolIterations() throws Exception {
        LlmClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.onReasoningDelta("我先查找 ROADMAP.md。\n");
            invokeNoArg(renderer, "resetBetweenIterations");
            renderer.onReasoningDelta("已经读到内容，接下来总结给用户。\n");
            invokeNoArg(renderer, "finish");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("我先查找 ROADMAP.md。"));
        assertTrue(rendered.contains("已经读到内容，接下来总结给用户。"));
        assertTrue(rendered.contains("思考过程"));
        assertTrue(countOccurrences(rendered, "思考过程") == 1,
                "同一次 ReAct 运行中工具调用前后的 reasoning 应归到同一个思考区: " + rendered);
    }

    @Test
    void inlineThinkingPanelReceivesReasoningInsteadOfTranscriptHeading() throws Exception {
        ThinkingRenderer thinkingRenderer = new ThinkingRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(thinkingRenderer);

        invokeNoArg(renderer, "beginThinking");
        renderer.onReasoningDelta("我先判断用户意图。\n");
        renderer.onContentDelta("好的");
        invokeNoArg(renderer, "finish");

        String transcript = thinkingRenderer.transcript();
        assertTrue(thinkingRenderer.started);
        assertTrue(thinkingRenderer.ended);
        assertTrue(thinkingRenderer.thinking().contains("我先判断用户意图。"));
        assertEquals("我先判断用户意图。\n", thinkingRenderer.completedReasoning());
        assertFalse(transcript.contains("思考过程"), transcript);
        assertTrue(transcript.contains("Thinking..."), transcript);
        assertTrue(transcript.contains("│ 我先判断用户意图。"), transcript);
        assertFalse(transcript.contains("π 回复"), transcript);
        assertTrue(transcript.contains("好的"), transcript);
    }

    @Test
    void routesVisibleReasoningThroughTypedRendererEvent() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(eventRenderer);

        renderer.onReasoningDelta("**inspect** the renderer seam\n");
        invokeNoArg(renderer, "finish");

        assertEquals("**inspect** the renderer seam\n", eventRenderer.reasoning.toString());
    }

    @Test
    void lateReasoningUsesASecondTypedReasoningBlock() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(eventRenderer);

        renderer.onReasoningDelta("initial reasoning");
        renderer.onContentDelta("answer");
        renderer.onReasoningDelta("late reasoning");
        invokeNoArg(renderer, "finish");

        assertEquals(List.of("initial reasoning", "late reasoning"),
                eventRenderer.reasoningBlocks);
    }

    @Test
    void routesAnswerProseThroughTypedRendererEvent() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("## answer prose\n");
        invokeNoArg(renderer, "finish");

        assertEquals("## answer prose\n", eventRenderer.answer.toString());
    }

    @Test
    void routesRawFencedCodeThroughTypedCodeBlockEvent() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("Before\n```ja");
        renderer.onContentDelta("va\nSystem.out.print");
        renderer.onContentDelta("ln(1);\n``");
        renderer.onContentDelta("`\nAfter\n");
        invokeNoArg(renderer, "finish");

        assertEquals("Before\nAfter\n", eventRenderer.answer.toString());
        assertEquals(List.of("java"), eventRenderer.codeLanguages);
        assertEquals(List.of("System.out.println(1);\n"), eventRenderer.codeBodies);
    }

    @Test
    void finishesUnclosedFenceAsTypedCodeBlock() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("```python\nprint('done')");
        invokeNoArg(renderer, "finish");

        assertEquals(List.of("python"), eventRenderer.codeLanguages);
        assertEquals(List.of("print('done')"), eventRenderer.codeBodies);
    }

    @Test
    void defaultRendererPreservesInterleavedProseAndCodeGolden() throws Exception {
        LinearRenderer linearRenderer = new LinearRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(linearRenderer);

        renderer.onContentDelta("Before\n```java\nint value = 1;\n```\nAfter\n");
        invokeNoArg(renderer, "finish");

        assertEquals("▪ Before\n\n┌─ code: java\n    int value = 1;\n└─ end\n\nAfter\n\n",
                stripAnsi(linearRenderer.transcript()));
    }

    @Test
    void finishingStreamCompletesRendererTurnOnce() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("done\n");
        invokeNoArg(renderer, "finish");

        assertEquals(1, eventRenderer.turnsEnded);
    }

    private LlmClient.StreamListener newStreamRenderer() throws Exception {
        Class<?> rendererClass = Class.forName("com.paicli.agent.Agent$StreamRenderer");
        Constructor<?> constructor = rendererClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (LlmClient.StreamListener) constructor.newInstance();
    }

    private LlmClient.StreamListener newStreamRenderer(Renderer renderer) throws Exception {
        Class<?> rendererClass = Class.forName("com.paicli.agent.Agent$StreamRenderer");
        Constructor<?> constructor = rendererClass.getDeclaredConstructor(Renderer.class);
        constructor.setAccessible(true);
        return (LlmClient.StreamListener) constructor.newInstance(renderer);
    }

    private void invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class ThinkingRenderer implements Renderer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder completedReasoning = new StringBuilder();
        private final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        private boolean started;
        private boolean ended;

        @Override
        public void start() {
        }

        @Override
        public boolean supportsThinkingPanel() {
            return true;
        }

        @Override
        public void beginThinking(String label) {
            started = true;
        }

        @Override
        public void appendThinking(String delta) {
            thinking.append(delta);
        }

        @Override
        public void endThinking() {
            ended = true;
        }

        @Override
        public BlockWriter beginReasoning() {
            return new BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                    completedReasoning.append(sourceDelta);
                }

                @Override
                public void finish() {
                    String reasoning = completedReasoning.toString()
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                            .trim();
                    if (reasoning.isEmpty()) {
                        return;
                    }
                    stream.println("Thinking...");
                    for (String line : reasoning.split("\\R+")) {
                        String normalized = line.replaceAll("\\s+", " ").trim();
                        if (!normalized.isEmpty()) {
                            stream.println("│ " + normalized);
                        }
                    }
                    stream.println();
                }
            };
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("test");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }

        @Override
        public void close() {
            stream.close();
        }

        private String transcript() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private String thinking() {
            return thinking.toString();
        }

        private String completedReasoning() {
            return completedReasoning.toString();
        }
    }

    private static final class EventRenderer implements Renderer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        private final StringBuilder reasoning = new StringBuilder();
        private final List<String> reasoningBlocks = new java.util.ArrayList<>();
        private final StringBuilder answer = new StringBuilder();
        private final List<String> codeLanguages = new java.util.ArrayList<>();
        private final List<String> codeBodies = new java.util.ArrayList<>();
        private int turnsEnded;

        @Override
        public void start() {
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public BlockWriter beginReasoning() {
            StringBuilder block = new StringBuilder();
            return new BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                    block.append(sourceDelta);
                    reasoning.append(sourceDelta);
                }

                @Override
                public void finish() {
                    reasoningBlocks.add(block.toString());
                }
            };
        }

        @Override
        public BlockWriter beginAnswer() {
            return collecting(answer);
        }

        @Override
        public void appendCodeBlock(String language, String code) {
            codeLanguages.add(language);
            codeBodies.add(code);
        }

        @Override
        public void endTurn() {
            turnsEnded++;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("test");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }

        @Override
        public void close() {
            stream.close();
        }

        private BlockWriter collecting(StringBuilder target) {
            return new BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                    target.append(sourceDelta);
                }

                @Override
                public void finish() {
                }
            };
        }
    }

    private static final class LinearRenderer implements Renderer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);

        @Override
        public void start() {
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("test");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }

        @Override
        public void close() {
            stream.close();
        }

        private String transcript() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[;\\d]*m", "");
    }
}
