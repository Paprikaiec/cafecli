package com.cafecli.ui;

import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceStreamRendererTest {

    @Test
    void reasoningWithoutACompleteLineWaitsForFinalizationBeforeShowingItsHeading() {
        var plain = new LinearRenderer();
        var source = new SourceStreamRenderer(plain);
        source.onReasoningDelta("inspect the sources");
        assertFalse(plain.transcript().contains("思考过程"));
        source.resetBetweenIterations();
        assertTrue(plain.transcript().contains("思考过程"));
        assertTrue(plain.transcript().contains("inspect the sources"));
    }

    @Test
    void whitespaceOnlyReasoningNeverCreatesAHeading() {
        var plain = new LinearRenderer();
        var source = new SourceStreamRenderer(plain);
        source.onReasoningDelta("  \n");
        source.resetBetweenIterations();
        assertFalse(plain.transcript().contains("思考过程"));
    }

    @Test
    void oneRunKeepsOneReasoningHeadingAcrossToolIterations() {
        var plain = new LinearRenderer();
        var source = new SourceStreamRenderer(plain);
        source.onReasoningDelta("first thought\n");
        source.resetBetweenIterations();
        source.onReasoningDelta("next thought\n");
        source.finish();
        assertEquals(1,countOccurrences(plain.transcript(),"思考过程"));
        assertTrue(plain.transcript().contains("first thought"));
        assertTrue(plain.transcript().contains("next thought"));
    }

    @Test
    void inlineThinkingPanelReceivesReasoningInsteadOfTranscriptHeading() throws Exception {
        ThinkingRenderer thinkingRenderer = new ThinkingRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(thinkingRenderer);

        renderer.beginThinking();
        renderer.onReasoningDelta("我先判断用户意图。\n");
        renderer.onContentDelta("好的");
        renderer.finish();

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
        SourceStreamRenderer renderer = newStreamRenderer(eventRenderer);

        renderer.onReasoningDelta("**inspect** the renderer seam\n");
        renderer.finish();

        assertEquals("**inspect** the renderer seam\n", eventRenderer.reasoning.toString());
    }

    @Test
    void lateReasoningUsesASecondTypedReasoningBlock() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(eventRenderer);

        renderer.onReasoningDelta("initial reasoning");
        renderer.onContentDelta("answer");
        renderer.onReasoningDelta("late reasoning");
        renderer.finish();

        assertEquals(List.of("initial reasoning", "late reasoning"),
                eventRenderer.reasoningBlocks);
    }

    @Test
    void reasoningAlreadyClosedByProseIsNotPublishedAgainAtMessageEnd() {
        var renderer = new EventRenderer();
        var source = new SourceStreamRenderer(renderer);
        source.onReasoningDelta("initial reasoning\n");
        source.onContentDelta("answer\n");
        source.finish();
        assertEquals(List.of("initial reasoning\n"),renderer.reasoningBlocks);
    }

    @Test
    void routesAnswerProseThroughTypedRendererEvent() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("## answer prose\n");
        renderer.finish();

        assertEquals("## answer prose\n", eventRenderer.answer.toString());
    }

    @Test
    void routesRawFencedCodeThroughTypedCodeBlockEvent() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("Before\n```ja");
        renderer.onContentDelta("va\nSystem.out.print");
        renderer.onContentDelta("ln(1);\n``");
        renderer.onContentDelta("`\nAfter\n");
        renderer.finish();

        assertEquals("Before\nAfter\n", eventRenderer.answer.toString());
        assertEquals(List.of("java"), eventRenderer.codeLanguages);
        assertEquals(List.of("System.out.println(1);\n"), eventRenderer.codeBodies);
    }

    @Test
    void finishesUnclosedFenceAsTypedCodeBlock() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("```python\nprint('done')");
        renderer.finish();

        assertEquals(List.of("python"), eventRenderer.codeLanguages);
        assertEquals(List.of("print('done')"), eventRenderer.codeBodies);
    }

    @Test
    void typedAnswerStartsWithoutADetachedMarkerAndPreservesInterleavedCode() throws Exception {
        LinearRenderer linearRenderer = new LinearRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(linearRenderer);

        renderer.onContentDelta("Before\n```java\nint value = 1;\n```\nAfter\n");
        renderer.finish();

        String transcript = linearRenderer.transcript();
        assertEquals("Before\n\nint value = 1;\n\nAfter\n\n",
                stripAnsi(transcript));
        assertFalse(transcript.contains("▪"), transcript);
        assertFalse(transcript.contains("\u001B[32m"), transcript);
    }

    @Test
    void inlineThinkingPanelPathLeavesInterBlockBlanksToTheLiveStrip() throws Exception {
        InlineLikeRenderer inline = new InlineLikeRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(inline);

        // Two ReAct iterations, each streaming answer prose through the typed strip events.
        renderer.onContentDelta("first answer\n");
        renderer.resetBetweenIterations();
        renderer.onContentDelta("second answer\n");
        renderer.finish();

        // The live strip owns inter-block spacing (exactly one blank between frozen units). On the
        // inline path the StreamRenderer must not push its own inter-iteration / end-of-turn blank
        // lines into the renderer stream — doing so is what produced the double blank in issue #5A.
        assertEquals("", inline.streamText(),
                "inline path must not write inter-block blank lines to the renderer stream: <"
                        + inline.streamText() + ">");
        assertEquals(List.of("first answer\n", "second answer\n"), inline.answers);
    }

    @Test
    void finishingStreamCompletesRendererTurnOnce() throws Exception {
        EventRenderer eventRenderer = new EventRenderer();
        SourceStreamRenderer renderer = newStreamRenderer(eventRenderer);

        renderer.onContentDelta("done\n");
        renderer.finish();

        assertEquals(1, eventRenderer.turnsEnded);
    }

    private SourceStreamRenderer newStreamRenderer(Renderer renderer) {
        return new SourceStreamRenderer(renderer);
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
        public void appendToolCalls(List<ToolCallView> toolCalls) {
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
        public void appendToolCalls(List<ToolCallView> toolCalls) {
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

    /**
     * A double for the inline renderer: it advertises a thinking panel and routes reasoning, answer
     * prose, and code through typed block events into buffers (the real InlineRenderer routes them to
     * the live strip, not the raw stream). {@link #stream()} therefore only captures whatever the
     * StreamRenderer writes directly, which on the inline path must be nothing structural.
     */
    private static final class InlineLikeRenderer implements Renderer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        private final List<String> answers = new java.util.ArrayList<>();

        @Override
        public void start() {
        }

        @Override
        public boolean supportsThinkingPanel() {
            return true;
        }

        @Override
        public void beginThinking(String label) {
        }

        @Override
        public void appendThinking(String delta) {
        }

        @Override
        public void endThinking() {
        }

        @Override
        public BlockWriter beginReasoning() {
            return new BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                }

                @Override
                public void finish() {
                }
            };
        }

        @Override
        public BlockWriter beginAnswer() {
            StringBuilder block = new StringBuilder();
            return new BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                    block.append(sourceDelta);
                }

                @Override
                public void finish() {
                    answers.add(block.toString());
                }
            };
        }

        @Override
        public void appendCodeBlock(String language, String code) {
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public void appendToolCalls(List<ToolCallView> toolCalls) {
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

        private String streamText() {
            return output.toString(StandardCharsets.UTF_8);
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
        public void appendToolCalls(List<ToolCallView> toolCalls) {
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
