package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.AbstractPosixTerminal;

import com.cafecli.render.inline.replay.NativeTerminalSizeProbe;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Samples the terminal's geometry off the write path (ADR 0010 amendment A3c).
 *
 * <p><b>Geometry is sampled from the kernel, not delivered by signal.</b> SIGWINCH is not a faithful
 * record of dimension changes in either direction: one paired capture recorded 406 delivered WINCH
 * markers against 284 distinct sampled transitions — signals arrive without a new geometry, and
 * distinct geometries are coalesced out of delivery. An epoch model built on the signal would both
 * start epochs that never happened and miss ones that did. So {@link #arm()} is a <b>wake-up hint</b>
 * and nothing more: it says "look now", and whether a [[geometry epoch]] ended is decided by
 * comparing what the look returned against the previous sample.
 *
 * <p><b>Sampling is a push, reading it is a field read.</b> {@link #sample()} and
 * {@link #lastSample()} — the render thread's only entry points — never touch the source. Every
 * probe happens either on the signal thread ({@link #arm()}) or on the sampler's own driver thread
 * ({@link #pollAt(long)}). That is what takes the largest fixed cost off frame composition: on an
 * exec-pty {@code Terminal.getSize()} forks {@code stty}, measured at a 3.3 ms median and 10.7 ms
 * p90, about half a frame. The standing rule that geometry must not be re-measured immediately
 * before writing was a consequence of that fork, and it retires with it.
 *
 * <p><b>The driver polls fast while the geometry moves and parks when it settles.</b> An idle
 * session pays one probe per {@link #DEFAULT_HEARTBEAT_MILLIS}, and that heartbeat doubles as the
 * only channel on a terminal that delivers no signal at all.
 */
final class GeometrySampler implements AutoCloseable {

    /** Slow enough to be free on a quiet session, brisk enough to be the only channel if it must be. */
    static final long DEFAULT_HEARTBEAT_MILLIS = 500;

    /**
     * The cadence while the geometry is moving. The measured turbulence trace it has to resolve has a
     * p50 intra-change gap of 8.8 ms and a p99 of 41.6 ms (n=800, 2026-08-13), so a millisecond
     * separates every distinct geometry the window passes through.
     */
    static final long DEFAULT_FAST_POLL_MILLIS = 1;

    /**
     * How long after the last observed change the driver keeps polling fast before parking.
     *
     * <p>It is the quiet interval of amendment A3d: the sampler must stay awake at least as long as
     * the [[geometry turbulence]] gate might still be deciding whether the gesture ended. Parking
     * earlier would be safe but pointless; parking later would burn probes on a still terminal.
     */
    static final long DEFAULT_SETTLE_MILLIS = TurbulenceGate.DEFAULT_QUIET_MILLIS;

    private final GeometrySource source;
    private final long fastPollNanos;
    private final long heartbeatNanos;
    private final long settleNanos;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong transitions = new AtomicLong();

    /** The published sample. Written under {@code this}, read without any lock by the render thread. */
    private volatile Size current = new Size(80, 24);
    /** Whether any probe has ever succeeded; until one has, the fallback above is not a geometry. */
    private boolean primed;

    private volatile Thread driver;
    private volatile boolean closed;
    private volatile Runnable onTransition = () -> { };

    // Driver-thread-owned polling cadence state.
    private long lastActivityNanos;
    private boolean fastWindowOpen;

    GeometrySampler(GeometrySource source) {
        this(source, DEFAULT_HEARTBEAT_MILLIS, DEFAULT_SETTLE_MILLIS);
    }

    GeometrySampler(GeometrySource source, long heartbeatMillis, long settleMillis) {
        this.source = source;
        this.fastPollNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_FAST_POLL_MILLIS);
        this.heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, heartbeatMillis));
        this.settleNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, settleMillis));
        // Prime once at construction so the very first frame reads a field like every later one.
        observe();
    }

    /**
     * The production sampler for a terminal: the native {@code TIOCGWINSZ} probe when this really is
     * the terminal on file descriptor 1, and the terminal's own answer otherwise.
     */
    static GeometrySampler forTerminal(Terminal terminal) {
        return create(terminal, terminal instanceof AbstractPosixTerminal
                ? NativeTerminalSizeProbe.forSystemOutput()
                : null);
    }

    /**
     * Test seam for the fallback criterion: pass {@code null} for the probe to build the sampler a
     * session gets when the native library, or the file descriptor, is not there.
     */
    static GeometrySampler create(Terminal terminal, LongSupplier nativeProbe) {
        return new GeometrySampler(new SystemGeometrySource(terminal, nativeProbe));
    }

    /**
     * A signal says the geometry may have moved.
     *
     * <p>It takes the probe inline, which is the fastest arming there is, and wakes the driver so the
     * burst that follows is resolved at {@link #DEFAULT_FAST_POLL_MILLIS}. The signal contributes no
     * geometry information of its own: one that arrives without a dimension change produces no
     * transition and ends no epoch.
     *
     * <p>Returns the signal generation, which the replay recorder pairs with the geometry the frame
     * eventually observes.
     */
    long arm() {
        armed.set(true);
        if (observe()) {
            onTransition.run();
        }
        Thread waiting = driver;
        if (waiting != null) {
            LockSupport.unpark(waiting);
        }
        return generation.incrementAndGet();
    }

    /**
     * What to run when a transition is observed.
     *
     * <p>An observation off the render thread is worthless on its own: a [[geometry epoch]] ends on a
     * frame, and after the last change of a gesture — or on a terminal that signals nothing at all —
     * nothing else is going to post one. So the sampler asks for the frame that will consume what it
     * saw. The callback runs on whichever thread observed, and must only post.
     */
    void onTransitionObserved(Runnable request) {
        onTransition = request == null ? () -> { } : request;
    }

    /** The number of wake-up hints delivered so far. */
    long generation() {
        return generation.get();
    }

    /**
     * The number of distinct geometries observed since the first. Deliberately independent of
     * {@link #generation()}: the two counts disagreed 284 to 406 in the capture that motivated
     * sampling, and either may lead.
     */
    long transitions() {
        return transitions.get();
    }

    /** The current geometry — a field read. This is what frame composition calls. */
    Size sample() {
        return current;
    }

    /** The most recent sample; callers off the frame path share the frame's view. */
    Size lastSample() {
        return current;
    }

    /**
     * One step of the polling policy, driven by whoever owns the sampler's thread. Probes the source,
     * publishes what it found, and returns how long to wait before the next step: the fast cadence
     * while the geometry is still moving, the heartbeat once it has settled.
     *
     * <p>Taking {@code nowNanos} as an argument rather than reading a clock is what makes parking and
     * unparking assertable with fabricated time and no thread at all.
     */
    long pollAt(long nowNanos) {
        boolean wokenBySignal = armed.getAndSet(false);
        boolean transition = observe();
        if (transition || wokenBySignal) {
            lastActivityNanos = nowNanos;
            fastWindowOpen = true;
        }
        if (transition) {
            onTransition.run();
        }
        if (fastWindowOpen && nowNanos - lastActivityNanos >= settleNanos) {
            fastWindowOpen = false;
        }
        return fastWindowOpen && source.pollableAtMillisecondResolution()
                ? fastPollNanos
                : heartbeatNanos;
    }

    /**
     * Probe once and publish. Returns whether this was a <b>transition</b> — a geometry different
     * from the previous one — which the first sample of a session never is.
     *
     * <p>The published value is always a fresh {@link Size}: JLine hands out geometry objects that
     * callers mutate in place, and a change detector that aliased one would never fire.
     */
    private synchronized boolean observe() {
        Size probed;
        try {
            probed = source.get();
        } catch (RuntimeException | LinkageError ignored) {
            // A probe that fails leaves the last known geometry standing; the renderer keeps drawing.
            return false;
        }
        if (probed == null || probed.getColumns() <= 0 || probed.getRows() <= 0) {
            return false;
        }
        Size previous = current;
        if (primed
                && previous.getColumns() == probed.getColumns()
                && previous.getRows() == probed.getRows()) {
            return false;
        }
        current = new Size(probed.getColumns(), probed.getRows());
        if (!primed) {
            primed = true;
            return false;
        }
        transitions.incrementAndGet();
        return true;
    }

    /**
     * Start the driver that owns every probe from here on. Idempotent, and never started by a test
     * that injects its own source — those drive {@link #pollAt(long)} directly.
     */
    void start() {
        if (closed || driver != null) {
            return;
        }
        Thread thread = Thread.ofPlatform()
                .daemon(true)
                .name("cafecli-geometry-sampler")
                .unstarted(this::pollLoop);
        driver = thread;
        thread.start();
    }

    private void pollLoop() {
        while (!closed) {
            long waitNanos = pollAt(System.nanoTime());
            if (closed) {
                return;
            }
            LockSupport.parkNanos(this, waitNanos);
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread thread = driver;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    /**
     * Where a real session's geometry comes from: the kernel, asked with an allocation-free
     * {@code TIOCGWINSZ} ioctl, with the terminal's own answer as the fallback.
     *
     * <p>The fallback is not merely a different answer, it is a different <b>cost</b>: on an exec-pty
     * {@code Terminal.getSize()} forks {@code stty} (3.3 ms median, 10.7 ms p90), which must never be
     * asked a thousand times a second. So a session without the native probe keeps working and simply
     * never opens the fast window — the signal still arms an immediate probe, and the heartbeat still
     * covers a terminal that delivers no signal at all.
     *
     * <p>A native probe that stops answering degrades <b>stickily</b>: the fast cadence is withdrawn
     * along with it, rather than being handed to the subprocess fork that replaces it.
     */
    private static final class SystemGeometrySource implements GeometrySource {

        private final Terminal terminal;
        private volatile LongSupplier nativeProbe;

        SystemGeometrySource(Terminal terminal, LongSupplier nativeProbe) {
            this.terminal = terminal;
            this.nativeProbe = nativeProbe;
        }

        @Override
        public Size get() {
            LongSupplier probe = nativeProbe;
            if (probe != null) {
                long packed = probe.getAsLong();
                if (packed != 0) {
                    return new Size(NativeTerminalSizeProbe.columnsOf(packed),
                            NativeTerminalSizeProbe.rowsOf(packed));
                }
                nativeProbe = null;
            }
            return TerminalCapabilities.safeSize(terminal);
        }

        @Override
        public boolean pollableAtMillisecondResolution() {
            return nativeProbe != null;
        }
    }
}
