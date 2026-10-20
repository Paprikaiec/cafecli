package com.cafecli.ai;

import com.cafecli.ai.message.AssistantContent;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds stream events into the assistant message they describe. Blocks stream one after another, so at most one
 * block is open at a time and its index is the number of blocks already closed.
 */
final class PartialMessage {

    enum Kind { TEXT, THINKING, TOOL_CALL }

    /** The open block; {@code id} / {@code name} for a tool call, {@code signature} for thinking. */
    private static final class Open {
        final Kind kind;
        final String id;
        final String name;
        final StringBuilder text = new StringBuilder();
        String signature;

        Open(Kind kind, String id, String name) {
            this.kind = kind;
            this.id = id;
            this.name = name;
        }
    }

    private final Model model;
    private final List<AssistantContent> closed = new ArrayList<>();
    private Open open;
    private Usage usage = Usage.ZERO;

    PartialMessage(Model model) {
        this.model = model;
    }

    void apply(StreamEvent event) {
        switch (event) {
            case StreamEvent.TextStart ignored -> open = new Open(Kind.TEXT, null, null);
            case StreamEvent.ThinkingStart ignored -> open = new Open(Kind.THINKING, null, null);
            case StreamEvent.ToolCallStart start -> open = new Open(Kind.TOOL_CALL, start.id(), start.name());
            case StreamEvent.TextDelta delta -> open.text.append(delta.delta());
            case StreamEvent.ThinkingDelta delta -> open.text.append(delta.delta());
            case StreamEvent.ToolCallDelta delta -> open.text.append(delta.delta());
            case StreamEvent.TextEnd end -> close(end.block());
            case StreamEvent.ThinkingEnd end -> close(end.block());
            case StreamEvent.ToolCallEnd end -> close(end.toolCall());
            case StreamEvent.UsageUpdate update -> usage = update.usage();
            case StreamEvent.Start ignored -> { }
            case StreamEvent.Retry ignored -> { }
            case StreamEvent.Done ignored -> { }
            case StreamEvent.Failed ignored -> { }
        }
    }

    /** The index of the open block, or of the next block when none is open. */
    int index() {
        return closed.size();
    }

    /** The kind of the open block, or {@code null}. */
    Kind openKind() {
        return open == null ? null : open.kind;
    }

    /** Sets the signature of the open thinking block. The caller checks that one is open. */
    void signOpenThinking(String signature) {
        open.signature = signature;
    }

    /** The open block as it stands, or {@code null}. */
    AssistantContent openBlock() {
        if (open == null) {
            return null;
        }
        return switch (open.kind) {
            case TEXT -> new TextBlock(open.text.toString());
            case THINKING -> new ThinkingBlock(open.text.toString(), open.signature);
            case TOOL_CALL -> new ToolCall(open.id, open.name, open.text.toString());
        };
    }

    AssistantMessage snapshot(StopReason stopReason) {
        List<AssistantContent> content = new ArrayList<>(closed);
        if (open != null) {
            content.add(openBlock());
        }
        return new AssistantMessage(content, stopReason, usage, model.provider(), model.id());
    }

    private void close(AssistantContent block) {
        closed.add(block);
        open = null;
    }
}
