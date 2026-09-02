package com.cafecli.render.inline;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ordered record of everything the renderer has committed to [[冻结回卷]], in write order — and
 * the session's <b>retained model</b> (ADR 0010 amendment A10/A17).
 *
 * <p>It exists because native scrollback is no longer durable: a [[destructive resize reset]] erases
 * the terminal's saved scrollback along with the visible screen, so whatever is not held here cannot
 * be repainted and is simply gone (A4/A8). Live-region content is excluded by definition rather than
 * by policy — the spinner, approval cards, the [[输入框]] and the dock are never committed in the first
 * place, so they never reach this log.
 *
 * <p><b>A position is a source span, not an entry</b> (ADR 0016 D5). A position used to be fixed at an
 * entry's <em>first</em> commit and idempotent by identity, which put anything committed while a
 * streamed answer was still growing after that answer's entire eventual output — unconditionally
 * visible in the [[转写浏览层]], which renders an answer whole rather than to its committed prefix. So
 * an entry that commits a piece at a time occupies one {@link CommittedSpan} per uninterrupted run,
 * and the item that interrupted it sits between them. Everything else still takes exactly one
 * position, covering all of it, and is still idempotent by identity.
 *
 * <p><b>It keeps the whole session</b>, with no turn-based eviction. Two bounded views are cut from
 * it and they answer to different consumers: the [[bounded replay window]] is row-budgeted because it
 * is repainted by the terminal automatically on every geometry change, while the [[转写浏览层]] browses
 * the log unbounded because it costs no terminal I/O and is opened deliberately. Collapsing the two
 * into one number would let the cheap consumer set the expensive consumer's cost.
 *
 * <p>What grows with session length is retained <b>source</b>, which is small. The items in here hold
 * <b>no rendered rows at all</b> (ADR 0016 D1): laying an entry out at a width belongs to the view
 * showing it, and each view caches and releases its own ({@link RetainedRenderCache}). This log
 * therefore never reaches into an entry it does not own — it used to, to sum and to release their
 * caches, and that was the same object graph the [[转写浏览层]] and the render thread were sharing.
 *
 * <p>In memory only, and it dies with the process — deliberately, because users will come to rely on
 * it: there is no durable journal, no recovery, no reconstruction after exit (A5 unchanged).
 * {@code /clear} is the one way to drop it during a session.
 *
 * <p>Appended on the render thread and read by the input thread when the overlay opens, so it is
 * synchronized. Appends are idempotent because a streaming answer commits incrementally and is
 * offered to the log on every frame.
 */
final class ReplayLog {

    private final List<CommittedSpan> spans = new ArrayList<>();
    /** The newest position each source occupies, so a resumed source continues where it stopped. */
    private final Map<StripEntry, CommittedSpan> newestBySource = new IdentityHashMap<>();

    /**
     * Record that {@code item} has committed something to [[冻结回卷]].
     *
     * <p>Three outcomes, and the order of the checks is the policy:
     * <ol>
     *   <li>the item's position is already the newest one — it just grew, and nothing moves;</li>
     *   <li>the item is committed in one piece and already has a position — nothing at all;</li>
     *   <li>otherwise it takes a <b>new</b> position, and whatever held the newest one stops there.</li>
     * </ol>
     */
    synchronized void append(StripEntry item) {
        if (item == null) {
            return;
        }
        CommittedSpan newest = spans.isEmpty() ? null : spans.get(spans.size() - 1);
        if (newest != null && newest.source() == item) {
            return;
        }
        CommittedSpan previous = newestBySource.get(item);
        if (previous != null && !item.commitsIncrementally()) {
            return;
        }
        int from = previous == null ? 0 : previous.to();
        if (item.commitsIncrementally() && from >= item.committedSourceLines()) {
            // Nothing new has reached scrollback since this source's last position closed, so there
            // is nothing for a new position to hold. An empty one would be a hole in the log that
            // renders as nothing and owns the trailing separator blank of the source it belongs to.
            return;
        }
        if (newest != null) {
            newest.close();
        }
        if (previous != null) {
            previous.supersede();
        }
        CommittedSpan span = new CommittedSpan(item, from);
        spans.add(span);
        newestBySource.put(item, span);
    }

    /** The retained session as positions, oldest first. */
    synchronized List<StripEntry> items() {
        return List.copyOf(spans);
    }

    synchronized int size() {
        return spans.size();
    }

    synchronized void clear() {
        spans.clear();
        newestBySource.clear();
    }
}
