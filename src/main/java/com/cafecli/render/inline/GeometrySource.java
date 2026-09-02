package com.cafecli.render.inline;

import org.jline.terminal.Size;

/**
 * Where the renderer learns the terminal's current geometry.
 *
 * <p>Injected rather than reached for (ADR 0010 amendment A3c). Geometry is <b>sampled</b>, not
 * signalled: SIGWINCH is not a faithful record of dimension changes in either direction — one paired
 * capture recorded 406 delivered WINCH markers against 284 distinct sampled transitions — so signals
 * are demoted to a wake-up hint that arms {@link GeometrySampler} and the sample itself decides
 * whether a [[geometry epoch]] ended.
 *
 * <p>Injecting it is also what lets the epoch tests drive geometry without a real terminal.
 */
@FunctionalInterface
interface GeometrySource {

    Size get();

    /**
     * Whether this source may be polled at millisecond resolution.
     *
     * <p>It is a question about <b>cost</b>, not accuracy. A native {@code TIOCGWINSZ} ioctl is a
     * field read by comparison and can be asked a thousand times a second; {@code Terminal.getSize()}
     * on an exec-pty forks {@code stty} (measured 3.3 ms median, 10.7 ms p90) and must never be. A
     * source that says no still works — the signal arms an immediate probe and the heartbeat covers
     * a terminal that delivers no signal — it simply never opens the fast window.
     */
    default boolean pollableAtMillisecondResolution() {
        return true;
    }
}
