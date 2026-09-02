package com.cafecli.render.inline;

import com.cafecli.render.inline.replay.NativeTerminalSizeProbe;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Geometry is sampled from an injected source (ADR 0010 amendment A3c), so the whole observation
 * mechanism — wake-up hint, fast polling, parking, heartbeat — is drivable with no terminal, no
 * thread and no real time.
 *
 * <p>These tests replace the previous pull-based ones. Under the old contract the render thread
 * probed the terminal whenever it was armed or the heartbeat had elapsed, and the tests asserted
 * exactly that; this slice moves every probe off the write path, so assertions that a
 * {@code sample()} reads the source are wrong rather than obsolete.
 */
class GeometrySamplerTest {

    private final AtomicReference<Size> reported = new AtomicReference<>(new Size(80, 24));
    private final AtomicInteger reads = new AtomicInteger();

    private GeometrySampler sampler() {
        return new GeometrySampler(
                () -> {
                    reads.incrementAndGet();
                    return reported.get();
                },
                GeometrySampler.DEFAULT_HEARTBEAT_MILLIS,
                GeometrySampler.DEFAULT_SETTLE_MILLIS);
    }

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }

    /** A terminal that answers {@code getSize()} and nothing else — the fallback source's only input. */
    private static Terminal terminalReporting(Size size) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(size);
        return terminal;
    }

    @Test
    void readingTheSampleNeverProbesTheSource() {
        GeometrySampler sampler = sampler();
        int afterPriming = reads.get();

        reported.set(new Size(60, 20));
        for (int i = 0; i < 100; i++) {
            sampler.sample();
            sampler.lastSample();
        }

        assertEquals(afterPriming, reads.get(),
                "every probe belongs to the signal thread or the sampler's driver, never to a reader");
        assertEquals(new Size(80, 24), sampler.sample(), "readers see the last published sample");
    }

    @Test
    void everySignalAdvancesTheGenerationSoTheRecorderCanPairThem() {
        GeometrySampler sampler = sampler();

        assertEquals(1, sampler.arm());
        assertEquals(2, sampler.arm());
        assertEquals(2, sampler.generation());
    }

    /**
     * The measurement this slice exists for: one paired capture recorded 406 delivered signal markers
     * against 284 distinct sampled transitions. Signals are coalesced, so a gesture that passes
     * through many geometries may deliver one signal — and every geometry must still be observed.
     */
    @Test
    void geometriesCoalescedOutOfSignalDeliveryAreStillObserved() {
        GeometrySampler sampler = sampler();
        long now = 0;

        // One authentic fullscreen gesture: 22 distinct geometries at the measured p50 intra-change
        // gap of 8.8 ms (n=800, 2026-08-13), with a single signal delivered for the whole burst.
        sampler.arm();
        for (int step = 0; step < 22; step++) {
            reported.set(new Size(79 - step, 25 + step));
            for (int poll = 0; poll < 9; poll++) {
                now += millis(1);
                sampler.pollAt(now);
            }
        }

        assertEquals(22, sampler.transitions(),
                "every geometry the window passed through must be observed, signalled or not");
        assertEquals(1, sampler.generation(), "sanity: the kernel delivered one signal for all of it");
    }

    /**
     * A signal is a wake-up hint, never the geometry authority: one that arrives without a dimension
     * change observes no transition and therefore ends no [[geometry epoch]].
     */
    @Test
    void aSignalThatBringsNoNewGeometryIsNotATransition() {
        GeometrySampler sampler = sampler();

        for (int signal = 0; signal < 20; signal++) {
            sampler.arm();
            sampler.pollAt(millis(signal));
        }

        assertEquals(20, sampler.generation());
        assertEquals(0, sampler.transitions(),
                "signals arrive without a new geometry; the sample decides, not the signal");
    }

    /** While the geometry is moving the driver is told to come back in about a millisecond. */
    @Test
    void theDriverPollsAtMillisecondResolutionWhileTheGeometryIsChanging() {
        GeometrySampler sampler = sampler();
        long now = 0;
        sampler.pollAt(now);

        reported.set(new Size(76, 24));
        now += millis(1);
        assertEquals(millis(GeometrySampler.DEFAULT_FAST_POLL_MILLIS), sampler.pollAt(now),
                "an observed change opens the fast window");

        // The window stays open across the gaps of a gesture, which is what resolves the next change.
        for (int gap = 0; gap < 5; gap++) {
            now += millis(9);
            assertEquals(millis(GeometrySampler.DEFAULT_FAST_POLL_MILLIS), sampler.pollAt(now),
                    "a gesture's intra-change gaps must not close the fast window");
        }
    }

    /** And it parks once the geometry settles, so an idle session performs no polling. */
    @Test
    void theDriverParksWhenTheGeometrySettles() {
        GeometrySampler sampler = sampler();
        long now = 0;
        reported.set(new Size(76, 24));
        sampler.pollAt(now);

        now += millis(GeometrySampler.DEFAULT_SETTLE_MILLIS);
        assertEquals(millis(GeometrySampler.DEFAULT_HEARTBEAT_MILLIS), sampler.pollAt(now),
                "a settled geometry parks the driver back onto the heartbeat");

        int afterSettling = reads.get();
        for (long idle = 0; idle < 5_000; idle += GeometrySampler.DEFAULT_HEARTBEAT_MILLIS) {
            now += millis(GeometrySampler.DEFAULT_HEARTBEAT_MILLIS);
            sampler.pollAt(now);
        }
        assertEquals(10, reads.get() - afterSettling,
                "five idle seconds cost ten probes — one per heartbeat, and nothing else");
    }

    /**
     * On a posix system terminal the source is the native {@code TIOCGWINSZ} probe already used by
     * the replay recorder — an allocation-free ioctl, cheap enough to run a thousand times a second.
     */
    @Test
    void aNativeProbeIsPolledAtMillisecondResolution() {
        AtomicReference<long[]> nativeSize = new AtomicReference<>(new long[]{24, 80});
        GeometrySampler sampler = GeometrySampler.create(
                terminalReporting(new Size(999, 999)),
                () -> NativeTerminalSizeProbe.packed(
                        (int) nativeSize.get()[0], (int) nativeSize.get()[1]));

        assertEquals(new Size(80, 24), sampler.sample(),
                "the kernel is the authority when it can be asked cheaply");

        nativeSize.set(new long[]{20, 60});
        assertEquals(millis(GeometrySampler.DEFAULT_FAST_POLL_MILLIS), sampler.pollAt(0),
                "a cheap probe may be polled at millisecond resolution");
        assertEquals(new Size(60, 20), sampler.sample());
    }

    /**
     * Without the native probe the renderer still works: the terminal answers instead. It is simply
     * never asked at a millisecond cadence, because on an exec-pty that question forks a subprocess.
     */
    @Test
    void theSamplerFallsBackToTheTerminalWhenTheNativeProbeIsUnavailable() {
        GeometrySampler sampler = GeometrySampler.create(terminalReporting(new Size(100, 30)), null);

        assertEquals(new Size(100, 30), sampler.sample(), "the renderer still has a geometry");
        assertEquals(millis(GeometrySampler.DEFAULT_HEARTBEAT_MILLIS), sampler.pollAt(0),
                "an expensive probe is never polled at millisecond resolution");
    }

    /** A native probe that fails at runtime leaves the last geometry standing rather than a zero. */
    @Test
    void aFailingNativeProbeLeavesTheLastGeometryStanding() {
        GeometrySampler sampler = GeometrySampler.create(
                terminalReporting(new Size(100, 30)), () -> 0L);

        sampler.arm();
        sampler.pollAt(0);

        assertEquals(new Size(100, 30), sampler.sample(),
                "an unusable probe must degrade to the terminal's own answer, never to a blank size");
    }

    @Test
    void theHeartbeatObservesAChangeWithNoSignalAtAll() {
        // Terminals that expose no signal handling are covered by this path alone.
        GeometrySampler sampler = sampler();
        long now = 0;
        assertEquals(millis(GeometrySampler.DEFAULT_HEARTBEAT_MILLIS), sampler.pollAt(now),
                "a still geometry is polled at the heartbeat");

        reported.set(new Size(60, 20));
        now += millis(GeometrySampler.DEFAULT_HEARTBEAT_MILLIS);
        sampler.pollAt(now);

        assertEquals(new Size(60, 20), sampler.sample());
        assertEquals(1, sampler.transitions());
    }
}
