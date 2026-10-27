package com.cafecli.ui;

import com.cafecli.render.Renderer;
import java.io.PrintStream;

/** Splits raw model deltas into typed prose, reasoning and code blocks, preserving v1's stream behavior. */
public final class SourceStreamRenderer {
    private final Renderer renderer;
    private final PrintStream boundOut;
    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder visibleReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private final StringBuilder answerLineBuffer = new StringBuilder();
    private final StringBuilder codeBody = new StringBuilder();
    private Renderer.BlockWriter reasoningBlock;
    private Renderer.BlockWriter answerBlock;
    private String codeLanguage = "";
    private boolean inCodeBlock;
    private boolean answerHasProse;
    private boolean lastAnswerLineBlank;
    private boolean reasoningHeadingPrinted;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean thinkingQuotePrinted;
    private boolean streamedOutput;
    private boolean turnEnded;

    public SourceStreamRenderer(Renderer renderer) {
        this.renderer = java.util.Objects.requireNonNull(renderer);
        this.boundOut = renderer.stream();
    }

    private PrintStream out() {
        return boundOut;
    }

    private boolean hasThinkingPanel() {
        return renderer.supportsThinkingPanel();
    }

    public void beginThinking() {
        if (hasThinkingPanel()) {
            renderer.beginThinking("Thinking");
        }
    }

    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (contentStarted) {
            // Reasoning arriving after prose is published as a separate completed block.
            lateReasoning.append(delta);
            return;
        }
        visibleReasoning.append(delta);
        if (hasThinkingPanel()) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;
            }
            renderer.appendThinking(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            return;
        }
        if (!reasoningStarted) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;
            }
            if (!containsLineBreak(pendingReasoning)) {
                return; // Wait for a complete line before publishing a heading.
            }
            printReasoningHeadingIfNeeded();
            reasoningBlock = newReasoningBlock();
            reasoningBlock.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            streamedOutput = true;
        } else {
            reasoningBlock.append(delta);
        }
        out().flush();
    }

    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!contentStarted) {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            } else if (reasoningStarted && reasoningBlock != null) {
                reasoningBlock.finish();
                reasoningBlock = null;
                out().println();
            } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                printReasoningHeadingIfNeeded();
                Renderer.BlockWriter block = newReasoningBlock();
                block.append(pendingReasoning.toString());
                block.finish();
                out().println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }
            contentStarted = true;
            streamedOutput = true;
        }
        appendAnswerSource(delta);
        out().flush();
    }

    public void resetBetweenIterations() {
        if (hasThinkingPanel()) {
            finishThinkingPanelAndPrintQuote();
        }
        if (reasoningBlock != null) {
            reasoningBlock.finish();
            reasoningBlock = null;
        } else if (!hasThinkingPanel()) {
            flushPendingReasoning();
        }
        finishAnswerSource();
        String late = lateReasoning.toString().trim();
        if (!late.isEmpty()) {
            out().println();
            out().println("🧠 补充思考");
            Renderer.BlockWriter block = newReasoningBlock();
            block.append(late);
            block.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        pendingReasoning.setLength(0);
        visibleReasoning.setLength(0);
        reasoningStarted = false;
        contentStarted = false;
        thinkingQuotePrinted = false;
        if (streamedOutput && !hasThinkingPanel()) {
            // Inline path: the live strip owns exactly one blank between frozen block units, so
            // the SourceStreamRenderer must not add its own inter-iteration blank (that produced the
            // double blank in issue #5A). Plain / legacy fallback still needs this separator.
            out().println();
        }
        streamedOutput = false;
    }

    private void endTurnIfNeeded() {
        if (!turnEnded) {
            turnEnded = true;
            renderer.endTurn();
        }
    }

    public void finish() {
        if (hasThinkingPanel()) {
            finishThinkingPanelAndPrintQuote();
        }
        if (reasoningBlock != null) {
            reasoningBlock.finish();
        } else if (!hasThinkingPanel()) {
            flushPendingReasoning();
        }
        finishAnswerSource();
        String late = lateReasoning.toString().trim();
        if (!late.isEmpty()) {
            out().println();
            out().println("🧠 补充思考");
            Renderer.BlockWriter block = newReasoningBlock();
            block.append(late);
            block.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        if (streamedOutput && !hasThinkingPanel()) {
            // See resetBetweenIterations: inter-block spacing belongs to the live strip on the
            // inline path; only the plain / legacy fallback needs the trailing blank here.
            out().println();
        }
        endTurnIfNeeded();
    }

    private boolean containsLineBreak(CharSequence content) {
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\n' || ch == '\r') {
                return true;
            }
        }
        return false;
    }

    private void flushPendingReasoning() {
        String pending = pendingReasoning.toString();
        if (pending.isBlank()) {
            pendingReasoning.setLength(0);
            return;
        }
        printReasoningHeadingIfNeeded();
        Renderer.BlockWriter block = newReasoningBlock();
        block.append(pending);
        block.finish();
        pendingReasoning.setLength(0);
        streamedOutput = true;
    }

    private Renderer.BlockWriter newReasoningBlock() {
        return renderer.beginReasoning();
    }

    private Renderer.BlockWriter newAnswerBlock() {
        return renderer.beginAnswer();
    }

    private void appendAnswerSource(String delta) {
        answerLineBuffer.append(delta);
        int newline;
        while ((newline = answerLineBuffer.indexOf("\n")) >= 0) {
            String line = answerLineBuffer.substring(0, newline + 1);
            answerLineBuffer.delete(0, newline + 1);
            routeAnswerLine(line);
        }
    }

    private void routeAnswerLine(String line) {
        String trimmed = line.strip().trim();
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                emitCodeBlock();
            } else {
                boolean needsBlockSpacing = answerHasProse && !lastAnswerLineBlank;
                finishAnswerBlock();
                if (needsBlockSpacing && !hasThinkingPanel()) {
                    out().println();
                }
                answerHasProse = false;
                lastAnswerLineBlank = false;
                inCodeBlock = true;
                codeLanguage = trimmed.substring(3).trim();
            }
            return;
        }
        if (inCodeBlock) {
            codeBody.append(line);
            return;
        }
        if (answerBlock == null) {
            answerBlock = newAnswerBlock();
        }
        answerBlock.append(line);
        answerHasProse = true;
        lastAnswerLineBlank = line.isBlank();
    }

    private void finishAnswerSource() {
        if (answerLineBuffer.length() > 0) {
            routeAnswerLine(answerLineBuffer.toString());
            answerLineBuffer.setLength(0);
        }
        if (inCodeBlock) {
            emitCodeBlock();
        }
        finishAnswerBlock();
        answerHasProse = false;
        lastAnswerLineBlank = false;
    }

    private void finishAnswerBlock() {
        if (answerBlock != null) {
            answerBlock.finish();
            answerBlock = null;
        }
    }

    private void emitCodeBlock() {
        renderer.appendCodeBlock(codeLanguage, codeBody.toString());
        codeBody.setLength(0);
        codeLanguage = "";
        inCodeBlock = false;
    }

    private void finishThinkingPanelAndPrintQuote() {
        if (!hasThinkingPanel()) {
            return;
        }
        if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
            renderer.appendThinking(pendingReasoning.toString());
        }
        renderer.endThinking();
        pendingReasoning.setLength(0);
        publishCompletedReasoningIfNeeded();
    }

    private void publishCompletedReasoningIfNeeded() {
        if (thinkingQuotePrinted) {
            return;
        }
        String reasoning = visibleReasoning.toString();
        if (reasoning.isBlank()) {
            return;
        }
        Renderer.BlockWriter block = renderer.beginReasoning();
        block.append(reasoning);
        block.finish();
        thinkingQuotePrinted = true;
        streamedOutput = true;
    }

    private void printReasoningHeadingIfNeeded() {
        if (!reasoningHeadingPrinted) {
            out().println("🧠 思考过程");
            reasoningHeadingPrinted = true;
        }
    }
}
