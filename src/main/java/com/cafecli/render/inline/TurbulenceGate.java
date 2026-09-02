package com.cafecli.render.inline;

import java.util.concurrent.TimeUnit;

/**
 * The [[geometry turbulence]] state machine: it owns the [[geometry epoch]] decision and answers one
 * question per frame — what is this frame allowed to write? (ADR 0010 amendment A3b / A3d.)
 *
 * <p>A [[destructive resize reset]] splits along the only line that matters, geometry-sensitivity:
 *
 * <ul>
 *   <li>The <b>erase</b> — visible screen, saved scrollback, cursor home — does the same thing
 *       whenever it is consumed, at any size. It goes out <b>eagerly, on every observed dimension
 *       change</b>.</li>
 *   <li>The <b>redraw</b> is the only half that can be wrong, so it goes out <b>once</b>, when the
 *       geometry is observed to have settled.</li>
 * </ul>
 *
 * <p>Between the two the gate is <b>closed</b>: no content byte reaches the terminal and the screen
 * is deliberately blank. Producers keep filling the source model throughout. This is not a
 * performance choice — Java observes when it submits bytes, not when the terminal consumes them
 * relative to its own reflow queue, so keeping queue depth at one repaint has to be true <em>by
 * construction</em> rather than asserted as a timing property nothing can verify.
 */
final class TurbulenceGate {

    /** What a single frame is permitted to write. */
    enum Permit {
        /** Stable geometry: flush pending [[冻结回卷]] and compose the [[活区]], as within any epoch. */
        CONTENT,
        /** A dimension change was observed: write the geometry-independent erase and nothing else. */
        ERASE_ONLY,
        /** Turbulence continues and no new dimension arrived: write nothing; the screen stays blank. */
        NOTHING,
        /**
         * The redraw is released: perform a whole [[destructive resize reset]] — erase, then the
         * [[bounded replay window]] at the settled geometry — and compose the [[活区]] beneath it.
         *
         * <p>The reset carries its own erase rather than trusting the eager one still to be the last
         * thing on the wire, which keeps the redraw half self-contained and costs a dozen bytes.
         */
        RESET
    }

    /**
     * How long the geometry must hold still before the redraw is released.
     *
     * <p><b>Measured 2026-08-13</b>, 5 captures (3 × fullscreen, 2 × drag) at 1 ms sampling: the
     * authentic macOS fullscreen animation's worst intra-gesture gap was 42.8 ms (n=800 gaps), so
     * 100 ms clears it by 2.3×. It deliberately does not cover the synthetic drag's tail (~7 gaps per
     * run land in 100–200 ms, n=654): a premature redraw is self-correcting, because the next change
     * erases and repaints it, and the whole repaint is ≤12 KB.
     */
    static final long DEFAULT_QUIET_MILLIS = 100;

    /**
     * How long the screen may stay blank before a keep-alive redraw goes out anyway.
     *
     * <p><b>Measured 2026-08-13</b>, same captures: the fullscreen gesture lasts 274.9 ms at p50 and
     * 275.7 ms at most (n=36 gestures), a fixed macOS animation constant, so 500 ms sits above it and
     * the authentic gesture never pays a wasted mid-animation repaint. It exists for long drags: a
     * 19-second sweep caps blank screen at half a second and costs roughly 38 keep-alive repaints
     * instead of 328.
     */
    static final long DEFAULT_MAX_DEFER_MILLIS = 500;

    /** Override for {@link #DEFAULT_QUIET_MILLIS}; 120 ms is the conservative alternative. */
    static final String QUIET_PROPERTY = "cafecli.render.turbulence.quiet-ms";

    /** Override for {@link #DEFAULT_MAX_DEFER_MILLIS}. */
    static final String MAX_DEFER_PROPERTY = "cafecli.render.turbulence.max-defer-ms";

    private final long quietNanos;
    private final long maxDeferNanos;

    // Render-thread-owned. Dimensions are held as ints, never as a Size: JLine hands out geometry
    // objects that callers mutate in place, and a change detector that aliased one would never fire.
    private boolean epochEstablished;
    private int columns;
    private int rows;
    private boolean turbulent;
    private long lastChangeNanos;
    private long blankSinceNanos;

    TurbulenceGate(long quietMillis, long maxDeferMillis) {
        this.quietNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, quietMillis));
        this.maxDeferNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, maxDeferMillis));
    }

    /** The measured defaults, unless this session overrode them. */
    static TurbulenceGate fromSystemProperties() {
        return new TurbulenceGate(
                millisProperty(QUIET_PROPERTY, DEFAULT_QUIET_MILLIS),
                millisProperty(MAX_DEFER_PROPERTY, DEFAULT_MAX_DEFER_MILLIS));
    }

    private static long millisProperty(String key, long fallback) {
        try {
            String configured = System.getProperty(key);
            if (configured == null || configured.isBlank()) {
                return fallback;
            }
            long millis = Long.parseLong(configured.trim());
            return millis < 0 ? fallback : millis;
        } catch (RuntimeException ignored) {
            // A renderer must never fail to start over a malformed tuning knob.
            return fallback;
        }
    }

    /** Feed this frame's observed geometry and monotonic timestamp; get back what it may write. */
    Permit observe(int observedColumns, int observedRows, long nowNanos) {
        return observe(observedColumns, observedRows, nowNanos, false);
    }

    /**
     * The same, told whether a modal currently holds the terminal (ADR 0010 amendment A12).
     *
     * <p>A lease is a per-frame <b>fact</b> rather than gate state, like the geometry and the clock:
     * the depth belongs to whoever hands the terminal over, and this state machine only needs to know
     * whether it may write. While one is held every permit is {@link Permit#NOTHING} — not even the
     * eager erase, which is safe at any size but not on somebody else's screen.
     *
     * <p>A dimension change observed under a lease is still recorded and still ends the
     * [[geometry epoch]]. Nothing else is needed to make the return repaint exactly once: the ordinary
     * quiet-interval path is already "one redraw, when the geometry has held still", and by the time a
     * modal closes it has.
     */
    Permit observe(int observedColumns, int observedRows, long nowNanos, boolean leased) {
        if (leased) {
            observeUnderLease(observedColumns, observedRows, nowNanos);
            return Permit.NOTHING;
        }
        if (!epochEstablished) {
            // The first frame of a session has no previous screen of ours to declare void.
            epochEstablished = true;
            columns = observedColumns;
            rows = observedRows;
            return Permit.CONTENT;
        }
        if (observedColumns != columns || observedRows != rows) {
            columns = observedColumns;
            rows = observedRows;
            if (!turbulent) {
                turbulent = true;
                blankSinceNanos = nowNanos;
            }
            lastChangeNanos = nowNanos;
            // A change that lands after the max defer still erases — but it may as well carry the
            // keep-alive redraw with it, since the reset writes its own erase anyway. Deferring the
            // keep-alive to the next quiet frame would never fire during a gesture whose changes
            // outpace the frame cadence, which is exactly the long-drag case the bound exists for.
            return claimKeepAliveRedraw(nowNanos) ? Permit.RESET : Permit.ERASE_ONLY;
        }
        if (!turbulent) {
            return Permit.CONTENT;
        }
        if (nowNanos - lastChangeNanos >= quietNanos) {
            turbulent = false;
            return Permit.RESET;
        }
        return claimKeepAliveRedraw(nowNanos) ? Permit.RESET : Permit.NOTHING;
    }

    /**
     * Record what this frame saw while a modal held the terminal, writing nothing.
     *
     * <p>The keep-alive is deliberately not consulted: it bounds how long <em>our</em> screen may stay
     * blank, and while a modal owns the screen it is not blank at all — it holds what the modal drew.
     */
    private void observeUnderLease(int observedColumns, int observedRows, long nowNanos) {
        if (!epochEstablished) {
            epochEstablished = true;
            columns = observedColumns;
            rows = observedRows;
            return;
        }
        if (observedColumns == columns && observedRows == rows) {
            return;
        }
        columns = observedColumns;
        rows = observedRows;
        if (!turbulent) {
            turbulent = true;
            blankSinceNanos = nowNanos;
        }
        lastChangeNanos = nowNanos;
    }

    /**
     * Take the keep-alive redraw if the screen has been blank long enough to owe the user one, and
     * restart the bound when it does. Turbulence continues either way: the bound caps blank time, it
     * does not decide that a gesture has ended.
     *
     * <p>Counted from the moment the screen last went blank rather than from the start of the gesture,
     * which is what makes a long drag come back periodically instead of once.
     */
    private boolean claimKeepAliveRedraw(long nowNanos) {
        if (nowNanos - blankSinceNanos < maxDeferNanos) {
            return false;
        }
        blankSinceNanos = nowNanos;
        return true;
    }

    /** Whether a redraw is still owed — that is, whether the gate is closed and the screen blank. */
    boolean isTurbulent() {
        return turbulent;
    }
}

