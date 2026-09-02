package com.cafecli.render.inline;

/**
 * The renderer's clock, injected for the same reason {@link FrameTicker} is: so that timing
 * behaviour is asserted without real waiting.
 *
 * <p>Monotonic nanoseconds only — never wall-clock. Everything the renderer times is an interval
 * (how long since geometry was last sampled; later, how long geometry has been quiet).
 */
@FunctionalInterface
interface TimeSource {

    long nanoTime();

    static TimeSource system() {
        return System::nanoTime;
    }
}
