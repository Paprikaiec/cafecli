package com.cafecli.render.inline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One streamed answer's <b>immutable</b> source, as it stood at some instant (ADR 0016 D1).
 *
 * <p>This is the whole of what an {@link AnswerSegment} shares. It is published behind a
 * {@code volatile} reference and replaced rather than mutated, so the reference itself is the
 * happens-before edge: a reader on another thread gets a consistent snapshot or an older consistent
 * snapshot, never a torn one. That is what lets the [[转写浏览层]] follow a running turn from the
 * input thread while the render thread keeps appending to it — committing sits deliberately above the
 * [[geometry turbulence]] gate, so a [[模态租借]] stops terminal writes and not source mutation.
 *
 * <p>The cost is one array copy per <b>completed line</b>, not per delta: {@link #withTail} only
 * re-wraps the same immutable list.
 */
record AnswerSource(List<String> completedLines, String tail, boolean finished) {

    static final AnswerSource EMPTY = new AnswerSource(List.of(), "", false);

    /** The same source with a different still-growing tail — no copy of the completed lines. */
    AnswerSource withTail(String growingTail) {
        return new AnswerSource(completedLines, growingTail, finished);
    }

    /** The source after {@code line} became a completed logical line and the tail started over. */
    AnswerSource completing(String line) {
        List<String> lines = new ArrayList<>(completedLines.size() + 1);
        lines.addAll(completedLines);
        lines.add(line);
        return new AnswerSource(Collections.unmodifiableList(lines), "", finished);
    }

    /** The source once the producer has said there will be no more of it. */
    AnswerSource complete() {
        return finished ? this : new AnswerSource(completedLines, tail, true);
    }
}
