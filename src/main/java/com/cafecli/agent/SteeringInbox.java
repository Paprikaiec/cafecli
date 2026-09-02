package com.cafecli.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * The [[转向消息]] slot: what the user typed while a turn was already running, waiting for that
 * turn's next iteration boundary (issue 10, ADR 0016 D5).
 *
 * <p>It is the third sibling of the two injections that already share the ReAct pre-call channel —
 * LSP diagnostics and PostToolUse hook context — and it has the same shape: a concurrent slot that
 * the loop drains just before it asks the model anything. Granularity is the iteration boundary for
 * the same reason [[取消键]] stops there: a streaming answer cannot be interrupted mid-flight.
 *
 * <p><b>Acceptance is atomic, and that is the whole point of the {@code open} flag.</b> The caller
 * cannot ask "is a turn running?" and then hand over a message, because the turn can end in between
 * and the message would sit in a slot nobody will ever drain. {@link #offer} answers both questions
 * at once under this object's lock: it returns {@code false} when there is no turn that can take the
 * message, and the caller then submits it as an ordinary user turn instead.
 *
 * <p>{@link #closeForTurn()} is the other half of that guarantee — the end-of-turn fallback. A turn
 * that finishes without reaching another boundary (a final answer with no tool calls) leaves the slot
 * full, and those messages are handed back so the event loop can re-post them as ordinary
 * submissions. Dropping them would be worse than the swallowed keystroke this feature replaces: the
 * user has already seen the echo and believes the message was accepted.
 *
 * <p>Written by the input thread and drained by the session's serial executor, so every method is
 * synchronized. It holds nothing but strings.
 */
public final class SteeringInbox {

    private final List<String> pending = new ArrayList<>();
    private boolean open;

    /** A turn is starting: from here until {@link #closeForTurn()} it can absorb corrections. */
    synchronized void openForTurn() {
        open = true;
        pending.clear();
    }

    /**
     * The turn is over. Whatever never reached an iteration boundary comes back here so the caller
     * can re-post it as an ordinary submission rather than losing it.
     */
    synchronized List<String> closeForTurn() {
        open = false;
        List<String> undelivered = List.copyOf(pending);
        pending.clear();
        return undelivered;
    }

    /**
     * Offer a correction to the running turn. Returns {@code false} when no turn can take it, in
     * which case the caller must submit it the ordinary way.
     */
    public synchronized boolean offer(String message) {
        if (!open || message == null || message.isBlank()) {
            return false;
        }
        pending.add(message);
        return true;
    }

    /** Take everything waiting, at an iteration boundary. */
    synchronized List<String> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> taken = List.copyOf(pending);
        pending.clear();
        return taken;
    }

    /**
     * Throw away corrections that have not taken effect (ESC / Ctrl+C). The turn they were aimed at
     * is being cancelled, so carrying them into whatever the user types next would attach a stale
     * instruction to a fresh request.
     */
    public synchronized void discard() {
        pending.clear();
    }

    /** Test/diagnostic seam: how many corrections are waiting for the next iteration boundary. */
    public synchronized int pendingCount() {
        return pending.size();
    }
}
