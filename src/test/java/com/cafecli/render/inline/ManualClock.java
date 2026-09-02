package com.cafecli.render.inline;

import java.util.concurrent.TimeUnit;

/**
 * A monotonic clock the test advances by hand, mirroring {@link ManualFramePump}'s precedent.
 *
 * <p>[[geometry turbulence]] is timed — a quiet interval releases the deferred redraw, a max defer
 * bounds how long the screen may stay blank (ADR 0010 amendment A3d) — and none of that may be
 * asserted by sleeping. Real waiting would make the suite both slow and non-deterministic, and the
 * numbers under test are measured to the millisecond.
 */
final class ManualClock implements TimeSource {

    private volatile long nanos;

    @Override
    public long nanoTime() {
        return nanos;
    }

    void advance(long millis) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
