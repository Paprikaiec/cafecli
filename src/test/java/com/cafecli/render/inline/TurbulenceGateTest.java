package com.cafecli.render.inline;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The [[geometry turbulence]] state machine (ADR 0010 amendment A3b / A3d).
 *
 * <p>Every assertion here runs on an injected clock: no real waiting, no terminal, no renderer. That
 * is the whole point of extracting the epoch decision out of frame composition — the timing rule
 * that decides when the geometry-sensitive redraw is released becomes a pure, enumerable transition
 * table instead of a property that could only be observed by dragging a real window.
 */
class TurbulenceGateTest {

    private static long at(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static TurbulenceGate gate() {
        return new TurbulenceGate(TurbulenceGate.DEFAULT_QUIET_MILLIS,
                TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS);
    }

    /**
     * The first frame of a session establishes the [[geometry epoch]]: there is no previous screen of
     * ours to declare void, so it composes normally and the gate stays open.
     */
    @Test
    void theFirstObservationEstablishesTheEpochAndWritesContent() {
        TurbulenceGate gate = gate();

        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(80, 24, at(0)));
        assertFalse(gate.isTurbulent(), "starting up is not turbulence");
    }

    /** Unchanged geometry is an epoch that has not ended: nothing is erased, content keeps flowing. */
    @Test
    void anUnchangedGeometryKeepsWritingContent() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));

        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(80, 24, at(33)));
        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(80, 24, at(66)));
        assertFalse(gate.isTurbulent());
    }

    /**
     * The opening transition. A dimension change permits the geometry-independent erase and
     * <b>nothing else</b>: the gate closes, so no content byte — streaming text, spinner tick, dock
     * row, freeze — may reach the terminal until the redraw is released.
     */
    @Test
    void aDimensionChangeErasesEagerlyAndClosesTheGate() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));

        assertEquals(TurbulenceGate.Permit.ERASE_ONLY, gate.observe(78, 24, at(10)));
        assertTrue(gate.isTurbulent(), "the gate must be closed while a redraw is owed");
    }

    /** Height alone ends the epoch too — the dimensions are never classified (A3a). */
    @Test
    void aHeightOnlyChangeAlsoErasesEagerlyAndClosesTheGate() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));

        assertEquals(TurbulenceGate.Permit.ERASE_ONLY, gate.observe(80, 25, at(10)));
        assertTrue(gate.isTurbulent());
    }

    /**
     * While the gesture is still running, a frame that observes no new dimension writes
     * <b>nothing at all</b>. The screen stays blank on purpose: it is the difference between
     * "settling" and "broken", and it is what keeps the terminal's repaint queue one deep.
     */
    @Test
    void turbulenceWritesNothingUntilTheQuietIntervalElapses() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));
        gate.observe(78, 24, at(10));

        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(20)));
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(109)),
                "99 ms of quiet is not yet the 100 ms quiet interval");
        assertTrue(gate.isTurbulent());
    }

    /**
     * The measured shape of a real gesture: changes keep arriving faster than the quiet interval, so
     * every frame either erases or writes nothing, and not one of them repaints.
     */
    @Test
    void aRunOfChangesErasesEachTimeAndNeverRepaintsMidGesture() {
        TurbulenceGate gate = gate();
        gate.observe(100, 30, at(0));

        // p50 intra-gesture gap for the authentic fullscreen animation was 8.8 ms (n=800).
        for (int step = 1; step <= 20; step++) {
            assertEquals(TurbulenceGate.Permit.ERASE_ONLY,
                    gate.observe(100 + step, 30 + step, at(step * 9L)),
                    "step " + step + " must erase, never repaint");
        }
        assertTrue(gate.isTurbulent());
    }

    /**
     * The closing transition, and the criterion the whole slice exists for: <b>one</b> redraw per
     * settled gesture. Twenty dimension changes queue twenty erases and exactly one repaint.
     */
    @Test
    void aSettledGestureReleasesExactlyOneRedrawAndReopensTheGate() {
        TurbulenceGate gate = gate();
        gate.observe(100, 30, at(0));
        for (int step = 1; step <= 20; step++) {
            gate.observe(100 + step, 30 + step, at(step * 9L));
        }

        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(120, 50, at(180 + 100)),
                "the quiet interval must release the geometry-sensitive half");
        assertFalse(gate.isTurbulent(), "the gate reopens once the redraw has gone out");
        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(120, 50, at(180 + 133)),
                "a released gesture must not repaint a second time");
        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(120, 50, at(180 + 400)));
    }

    /** Quiet is measured from the <em>last</em> change, so a late change extends the gesture. */
    @Test
    void aLateChangeRestartsTheQuietIntervalRatherThanReleasingTheRedraw() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));
        gate.observe(78, 24, at(10));
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(95)));

        assertEquals(TurbulenceGate.Permit.ERASE_ONLY, gate.observe(76, 24, at(99)));
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(76, 24, at(150)),
                "quiet is counted from the last change, not from the start of the gesture");
        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(76, 24, at(199)));
    }

    /**
     * A gesture longer than the max defer must not leave the user staring at an empty terminal. The
     * redraw goes out anyway as a <b>keep-alive</b>, and turbulence continues afterwards — the gate
     * closes again on the next dimension change.
     *
     * <p>Changes here arrive every 50 ms — the scripted step of the storm script, which the decision
     * record labels a floor for a real drag — well below the 100 ms quiet interval, so the gesture
     * never settles and only the max defer can release anything.
     */
    @Test
    void aGestureLongerThanTheMaxDeferKeepsRedrawingWithoutSettling() {
        TurbulenceGate gate = gate();
        gate.observe(100, 30, at(0));

        int changes = 0;
        int keepAlives = 0;
        for (long millis = 50; millis <= 2000; millis += 50) {
            changes++;
            TurbulenceGate.Permit permit = gate.observe(100 - (int) (millis / 50), 30, at(millis));
            if (permit == TurbulenceGate.Permit.RESET) {
                keepAlives++;
                assertTrue(gate.isTurbulent(),
                        "a keep-alive redraw does not end the gesture at " + millis + " ms");
            }
        }

        // Two seconds of continuous change under a 500 ms bound: blank time is capped at half a
        // second apiece, which costs three repaints rather than one per change.
        assertEquals(40, changes);
        assertEquals(3, keepAlives, "the max defer must bound blank time, periodically");
    }

    /** The same bound with no further change arriving — a stalled gesture still comes back. */
    @Test
    void aStalledGestureIsRedrawnByTheMaxDeferEvenWithNoFurtherChange() {
        TurbulenceGate gate = new TurbulenceGate(10_000, TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS);
        gate.observe(80, 24, at(0));
        gate.observe(78, 24, at(10));

        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(400)));
        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(78, 24, at(510)),
                "the max defer releases the redraw even when the quiet interval never will");
        assertTrue(gate.isTurbulent(), "a keep-alive leaves the gesture unsettled");
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(600)),
                "and the gate closes again straight after it");
    }

    /**
     * The max defer bounds <em>blank time</em>, so it is counted from the last redraw rather than
     * from the start of the gesture: a second keep-alive is a further max defer away.
     */
    @Test
    void theMaxDeferIsCountedFromTheLastRedrawNotTheStartOfTheGesture() {
        TurbulenceGate gate = new TurbulenceGate(10_000, TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS);
        gate.observe(80, 24, at(0));
        gate.observe(78, 24, at(10));
        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(78, 24, at(510)));

        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(1000)));
        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(78, 24, at(1011)));
    }

    // --- the modal lease (ADR 0010 amendment A12) -------------------------------------------------

    /**
     * A modal owns the terminal for its duration, so no frame of the [[活区]] may write anything while
     * one is open — not content, and not the geometry-independent erase either. The erase is eager
     * precisely because it is safe at any size, but it is not safe on somebody else's screen: the
     * modal's screen is the alternate one, and erasing it would wipe what the modal drew.
     */
    @Test
    void everyFrameIsSilencedWhileAModalHoldsTheLease() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));

        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(80, 24, at(10), true),
                "an idle frame writes nothing while a modal owns the terminal");
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(70, 20, at(20), true),
                "not even the eager erase may go out under a lease");
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(70, 20, at(400), true),
                "and the quiet interval must not release a redraw onto the modal's screen");
    }

    /**
     * The geometry change is <b>recorded</b> under the lease and ends the [[geometry epoch]] all the
     * same, so returning from the modal owes exactly one [[destructive resize reset]] — the ordinary
     * quiet-interval path, released by the first frame that is allowed to write.
     */
    @Test
    void exactlyOneResetFiresOnReturnWhenTheGeometryChangedUnderTheLease() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));
        gate.observe(70, 20, at(20), true);
        assertTrue(gate.isTurbulent(), "a change under a lease still ends the epoch");

        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(70, 20, at(5_000)),
                "returning from a modal that spanned a resize repaints once");
        assertFalse(gate.isTurbulent());
        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(70, 20, at(5_010)),
                "and only once");
        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(70, 20, at(5_500)));
    }

    /** No change, no reset: closing a modal on an unchanged geometry is not an epoch boundary. */
    @Test
    void noResetFiresOnReturnWhenTheGeometryHeldStill() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));
        for (long millis = 10; millis <= 3_000; millis += 100) {
            assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(80, 24, at(millis), true));
        }

        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(80, 24, at(3_100)),
                "an unchanged geometry must not cost the user a repaint on return");
        assertFalse(gate.isTurbulent());
    }

    /**
     * Rapid open / close across a resize must not leave the owed reset stranded. The reset is owed by
     * the epoch, not by the return, so re-leasing before any frame could release it defers it again
     * rather than dropping it — and the first frame that is allowed to write still pays it.
     */
    @Test
    void reLeasingBeforeTheOwedResetFiresDefersItRatherThanDroppingIt() {
        TurbulenceGate gate = gate();
        gate.observe(80, 24, at(0));
        gate.observe(70, 20, at(10), true);

        // Closed and immediately reopened, with no frame in between — the reset is still owed.
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(70, 20, at(300), true));
        assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(70, 20, at(600), true));

        assertEquals(TurbulenceGate.Permit.RESET, gate.observe(70, 20, at(900)),
                "the owed reset survives an arbitrary number of modal round trips");
        assertEquals(TurbulenceGate.Permit.CONTENT, gate.observe(70, 20, at(1_000)));
    }

    // --- the measured constants, and the escape hatch from them ----------------------------------

    /**
     * The defaults are the measured ones (2026-08-13, 5 captures at 1 ms sampling): 100 ms of quiet,
     * 500 ms of max defer. Asserted as observable behaviour rather than as field values — 99 ms of
     * quiet must still be blank, 100 ms must repaint.
     */
    @Test
    void theDefaultQuietIntervalIsOneHundredMillisecondsAndTheMaxDeferFiveHundred() {
        assertEquals(100, TurbulenceGate.DEFAULT_QUIET_MILLIS);
        assertEquals(500, TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS);

        TurbulenceGate quiet = TurbulenceGate.fromSystemProperties();
        quiet.observe(80, 24, at(0));
        quiet.observe(78, 24, at(0));
        assertEquals(TurbulenceGate.Permit.NOTHING, quiet.observe(78, 24, at(99)));
        assertEquals(TurbulenceGate.Permit.RESET, quiet.observe(78, 24, at(100)));

        TurbulenceGate defer = TurbulenceGate.fromSystemProperties();
        defer.observe(80, 24, at(0));
        for (long millis = 0; millis < 500; millis += 50) {
            assertEquals(TurbulenceGate.Permit.ERASE_ONLY,
                    defer.observe(80 - (int) (millis / 50) - 1, 24, at(millis)),
                    "no keep-alive before the max defer, at " + millis + " ms");
        }
        assertEquals(TurbulenceGate.Permit.RESET, defer.observe(69, 24, at(500)));
    }

    /** Both constants are overridable, so a terminal or a gesture that needs different numbers can. */
    @Test
    void bothConstantsAreOverridable() {
        String previousQuiet = System.getProperty(TurbulenceGate.QUIET_PROPERTY);
        String previousDefer = System.getProperty(TurbulenceGate.MAX_DEFER_PROPERTY);
        try {
            System.setProperty(TurbulenceGate.QUIET_PROPERTY, "250");
            System.setProperty(TurbulenceGate.MAX_DEFER_PROPERTY, "1200");
            TurbulenceGate gate = TurbulenceGate.fromSystemProperties();
            gate.observe(80, 24, at(0));
            gate.observe(78, 24, at(0));

            assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(249)),
                    "the overridden quiet interval must be honoured");
            assertEquals(TurbulenceGate.Permit.RESET, gate.observe(78, 24, at(250)));

            // A gesture that keeps changing every 100 ms never satisfies the 250 ms quiet interval, so
            // only the overridden 1200 ms max defer can release it — the 500 ms default would have
            // fired a keep-alive somewhere inside this loop.
            TurbulenceGate deferring = TurbulenceGate.fromSystemProperties();
            deferring.observe(80, 24, at(0));
            for (long millis = 100; millis <= 1200; millis += 100) {
                assertEquals(TurbulenceGate.Permit.ERASE_ONLY,
                        deferring.observe(80 - (int) (millis / 100), 24, at(millis)),
                        "the overridden max defer must not fire at " + millis + " ms");
            }
            assertEquals(TurbulenceGate.Permit.RESET, deferring.observe(67, 24, at(1300)),
                    "the overridden max defer must be honoured");
        } finally {
            restore(TurbulenceGate.QUIET_PROPERTY, previousQuiet);
            restore(TurbulenceGate.MAX_DEFER_PROPERTY, previousDefer);
        }
    }

    /** A malformed override is ignored rather than crashing the renderer at startup. */
    @Test
    void aMalformedOverrideFallsBackToTheMeasuredDefault() {
        String previousQuiet = System.getProperty(TurbulenceGate.QUIET_PROPERTY);
        try {
            System.setProperty(TurbulenceGate.QUIET_PROPERTY, "not-a-number");
            TurbulenceGate gate = TurbulenceGate.fromSystemProperties();
            gate.observe(80, 24, at(0));
            gate.observe(78, 24, at(0));

            assertEquals(TurbulenceGate.Permit.NOTHING, gate.observe(78, 24, at(99)));
            assertEquals(TurbulenceGate.Permit.RESET, gate.observe(78, 24, at(100)));
        } finally {
            restore(TurbulenceGate.QUIET_PROPERTY, previousQuiet);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
