package com.cafecli.render.inline;

import com.cafecli.render.ApprovalRequest;
import com.cafecli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Ephemeral HITL approval view owned and rendered by the single render thread. */
final class ApprovalBlock implements StripEntry {

    private final Terminal terminal;
    private final ApprovalRequest request;
    private String notice = "";
    private String prompt;
    private final CountDownLatch firstRenderedFrame = new CountDownLatch(1);

    ApprovalBlock(Terminal terminal, ApprovalRequest request) {
        this.terminal = terminal;
        this.request = request;
        this.prompt = options(request);
    }

    /** Whether this card is showing that very request (so re-presenting it does not build a new one). */
    boolean isFor(ApprovalRequest other) {
        return request == other;
    }

    void update(String nextNotice, String nextPrompt) {
        notice = nextNotice == null ? "" : nextNotice;
        prompt = nextPrompt == null ? "" : nextPrompt;
    }

    @Override
    public List<AttributedString> render(int width) {
        int columns = Math.max(1, width);
        List<AttributedString> lines = new ArrayList<>();
        append(lines, AttributedString.fromAnsi(AnsiStyle.heading("⚠️  HITL approval")), columns);
        if (request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank()) {
            append(lines, new AttributedString("  " + request.sensitiveNotice()), columns);
        }
        for (String line : request.toDisplayText().split("\\R", -1)) {
            append(lines, new AttributedString(line), columns);
        }
        if (!notice.isBlank()) {
            append(lines, AttributedString.fromAnsi(AnsiStyle.subtle(notice)), columns);
        }
        if (!prompt.isBlank()) {
            append(lines, AttributedString.fromAnsi("> " + AnsiStyle.subtle(prompt)), columns);
        }
        List<AttributedString> rendered = List.copyOf(lines);
        firstRenderedFrame.countDown();
        return rendered;
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        return List.of();
    }

    boolean awaitFirstRenderedFrame(long timeout, TimeUnit unit) {
        try {
            return firstRenderedFrame.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void append(List<AttributedString> target, AttributedString line, int width) {
        target.addAll(line.columnSplitLength(width, true, true, terminal));
    }

    static String options(ApprovalRequest request) {
        boolean sensitive = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        return sensitive
                ? "[y] approve  [n] reject  [s] skip  [m] modify"
                : "[y] approve  [a] all  [n] reject  [s] skip  [m] modify";
    }
}
