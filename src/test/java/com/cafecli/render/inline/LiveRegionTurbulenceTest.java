package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cafecli.render.inline.TerminalBytes.displayedCharacters;
import static com.cafecli.render.inline.TerminalBytes.occurrencesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the [[活区]] writes across [[geometry turbulence]] (ADR 0010 amendment A3b).
 *
 * <p>Geometry, time and the turbulence constants are all injected, so a whole animated gesture is
 * driven with no terminal and no real waiting. The frame string {@code renderFrame()} returns is
 * exactly what reaches the terminal, which is why these assertions can be made about writes at all
 * without naming an expected byte sequence: what is asserted is the <b>absence of content</b>.
 */
class LiveRegionTurbulenceTest {

    private static final String DOCK_ROW = "DOCK-ROW";
    private static final String INPUT_TEXT = "> half a thought";

    private final AtomicReference<Size> geometry = new AtomicReference<>(new Size(80, 12));
    private final AtomicInteger probes = new AtomicInteger();
    private long nanos;
    private final Terminal terminal = mockTerminal();
    private final TurbulenceGate gate = new TurbulenceGate(
            TurbulenceGate.DEFAULT_QUIET_MILLIS, TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS);
    private final GeometrySampler sampler = new GeometrySampler(() -> {
        probes.incrementAndGet();
        return geometry.get();
    });
    private final LiveRegionStrip strip =
            new LiveRegionStrip(terminal, sampler, () -> nanos, gate);

    private Terminal mockTerminal() {
        Terminal mock = Mockito.mock(Terminal.class);
        Mockito.when(mock.getType()).thenReturn("xterm-256color");
        Mockito.when(mock.getSize()).thenAnswer(ignored -> geometry.get());
        Mockito.when(mock.getBufferSize()).thenAnswer(ignored -> geometry.get());
        Mockito.when(mock.getStringCapability(Mockito.any())).thenReturn("");
        return mock;
    }

    private void advance(long millis) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /** Change the geometry and deliver the wake-up hint, exactly as a signal would. */
    private void resizeTo(int columns, int rows) {
        geometry.set(new Size(columns, rows));
        strip.armGeometrySample();
    }

    private void bindLiveRegionFurniture() {
        strip.bindDock(width -> List.of(new AttributedString(DOCK_ROW)));
        strip.bindInputBox(() -> new CaptureDisplay.Frame(
                List.of(new AttributedString(INPUT_TEXT)),
                INPUT_TEXT.length(), Math.max(1, geometry.get().getColumns()), false));
    }

    private AnswerSegment committedAnswer(String text) {
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        strip.add(answer);
        answer.append(text);
        answer.finish();
        return answer;
    }

    /**
     * Frame composition reads a sampled field; it never asks the terminal how big it is (ADR 0010
     * amendment A3c).
     *
     * <p>This is the largest fixed cost that leaves the write path: on an exec-pty
     * {@code Terminal.getSize()} forks {@code stty}, measured at a 3.3 ms median and 10.7 ms p90 —
     * about half a frame, paid inside the one method that must not stall. The standing rule that
     * geometry must not be re-measured immediately before writing was a consequence of that fork and
     * retires with it: there is nothing left to re-measure.
     */
    @Test
    void composingAFrameNeverProbesTheGeometrySource() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.bindLeadingEntry(spinner("Thinking... (esc to cancel, 1s)"));

        int beforeComposing = probes.get();
        for (int frame = 0; frame < 60; frame++) {
            advance(33);
            strip.renderFrame();
        }

        assertEquals(beforeComposing, probes.get(),
                "frame composition must read the sampled geometry, never probe for it");
    }

    /**
     * Observing geometry is a <b>sampling event</b>, never a side effect of drawing.
     *
     * <p>The consequence is worth pinning because it is a trap: a geometry that changes while nothing
     * samples ends no [[geometry epoch]], however many frames are composed in the meantime. Under the
     * previous contract frame composition itself probed the terminal, so drawing was a third way to
     * observe; that way is gone with the fork it cost (ADR 0010 amendment A3c), leaving exactly two —
     * the wake-up hint and the sampler's driver. Both are established by {@code Renderer.start()},
     * which is why an {@link InlineRenderer} that is constructed but never started does not react to
     * a resize.
     */
    @Test
    void aGeometryChangeThatNothingSamplesEndsNoEpoch() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        // A live row that changes every frame, so that "this frame wrote content" is observable at
        // all: the JLine Display is differential, and an unchanged live region writes zero bytes for
        // reasons that have nothing to do with the gate. What is asserted is therefore the presence
        // of content, exactly as its absence is asserted during turbulence — not a chosen token,
        // because the diff legitimately writes only the columns that moved.
        AtomicReference<String> live = new AtomicReference<>("live0");
        strip.bindLeadingEntry(new StripEntry() {
            @Override
            public List<AttributedString> render(int width) {
                return List.of(new AttributedString(live.get()));
            }

            @Override
            public List<AttributedString> freezeTopRows(int minimumRows, int width) {
                return List.of();
            }
        });
        strip.renderFrame();

        geometry.set(new Size(40, 12));
        for (int frame = 1; frame <= 20; frame++) {
            live.set("live" + frame);
            advance(500);
            String frameBytes = strip.renderFrame();
            assertTrue(!displayedCharacters(frameBytes).isEmpty(),
                    "an unsampled geometry change must not end the epoch by itself, but frame "
                            + frame + " wrote no content:\n" + frameBytes);
        }

        // The moment something samples — here the driver's poll; in a session, that or the signal —
        // the epoch ends and the screen goes deliberately blank until the geometry settles.
        sampler.pollAt(nanos);
        advance(1);
        assertEquals("", displayedCharacters(strip.renderFrame()),
                "a sampled change ends the epoch on the very next frame");
    }

    /**
     * The criterion the whole slice exists for. Between the eager erase and the deferred redraw the
     * screen is deliberately blank: the committed transcript, the spinner, the dock and the
     * [[输入框]] all keep updating the source model, and not one of them reaches the terminal.
     */
    @Test
    void duringTurbulenceOnlyTheEraseReachesTheTerminal() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.bindLeadingEntry(spinner("Thinking... (esc to cancel, 1s)"));

        String stable = strip.renderFrame();
        assertTrue(displayedCharacters(stable).contains("alreadycommitted"),
                "sanity: a stable epoch writes content:\n" + stable);
        assertTrue(displayedCharacters(stable).contains(DOCK_ROW), stable);

        resizeTo(76, 12);
        String eraseFrame = strip.renderFrame();
        assertEquals("", displayedCharacters(eraseFrame),
                "a dimension change may write the erase and no content whatsoever:\n" + eraseFrame);

        advance(50);
        String heldFrame = strip.renderFrame();
        assertEquals("", heldFrame,
                "a frame inside turbulence writes nothing at all, not even an erase:\n" + heldFrame);

        advance(60);
        String redrawFrame = strip.renderFrame();
        String redrawn = displayedCharacters(redrawFrame);
        assertTrue(redrawn.contains("alreadycommitted"),
                "the settled redraw brings the replay window back:\n" + redrawFrame);
        assertTrue(redrawn.contains(DOCK_ROW), redrawFrame);
        assertTrue(redrawn.contains("esctocancel"), redrawFrame);
    }

    /**
     * A whole animated gesture — the measured fullscreen shape, alternating a height step and a width
     * step every 9 ms — costs the terminal <b>one</b> repaint. Drawing a reset per change would queue
     * one full repaint per step, which is the queue depth this slice exists to prevent, and "the last
     * one cleans up" is precisely what cannot be verified from Java.
     */
    @Test
    void anAnimatedGestureCostsExactlyOneRedraw() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.bindLeadingEntry(spinner("Thinking... (esc to cancel, 1s)"));
        strip.renderFrame();

        int width = 80;
        int rows = 12;
        int framesWithContent = 0;
        for (int step = 0; step < 22; step++) {
            resizeTo(width, ++rows);
            advance(9);
            if (!displayedCharacters(strip.renderFrame()).isEmpty()) {
                framesWithContent++;
            }
            resizeTo(++width, rows);
            advance(9);
            if (!displayedCharacters(strip.renderFrame()).isEmpty()) {
                framesWithContent++;
            }
        }
        assertEquals(0, framesWithContent,
                "no frame inside the gesture may write content");

        // The gesture ends: the geometry holds still for the quiet interval.
        advance(TurbulenceGate.DEFAULT_QUIET_MILLIS);
        assertTrue(displayedCharacters(strip.renderFrame()).contains("alreadycommitted"),
                "the settled gesture is repainted");
        advance(TurbulenceGate.DEFAULT_QUIET_MILLIS * 5);
        assertEquals("", displayedCharacters(strip.renderFrame()),
                "and repainted only once — a settled gesture owes nothing further");
    }

    /**
     * A drag long enough to outlive the max defer must not be an indefinitely blank terminal. The
     * screen comes back periodically — the [[bounded replay window]] plus the [[活区]], repainted as a
     * keep-alive — and the gesture carries on regardless.
     */
    @Test
    void aGestureLongerThanTheMaxDeferBringsTheScreenBackPeriodically() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.renderFrame();

        int width = 80;
        int keepAliveRedraws = 0;
        int framesWritingNoContent = 0;
        // A five-second drag stepping every 50 ms: the quiet interval is never satisfied.
        for (int step = 0; step < 100; step++) {
            resizeTo(--width < 20 ? width = 79 : width, 12);
            advance(50);
            String frame = strip.renderFrame();
            if (displayedCharacters(frame).isEmpty()) {
                framesWritingNoContent++;
            } else {
                assertTrue(displayedCharacters(frame).contains("alreadycommitted"),
                        "the only content a gesture may write is a keep-alive redraw: " + frame);
                keepAliveRedraws++;
                assertTrue(gate.isTurbulent(), "a keep-alive must not end the gesture");
            }
        }

        assertEquals(9, keepAliveRedraws,
                "five seconds of continuous dragging must come back about every 500 ms");
        assertEquals(91, framesWritingNoContent,
                "every other frame of the gesture writes the erase and no content");
    }

    /**
     * Producers are not blocked, only the terminal is: text that streams in while the screen is blank
     * is retained in the source model and is on screen the moment the geometry settles.
     */
    @Test
    void streamingOutputProducedDuringTurbulenceAppearsInTheRedraw() {
        bindLiveRegionFurniture();
        strip.renderFrame();

        resizeTo(76, 12);
        strip.renderFrame();

        AnswerSegment duringTurbulence = new AnswerSegment(terminal, () -> { });
        strip.add(duringTurbulence);
        duringTurbulence.append("streamed while the screen was blank\n");
        duringTurbulence.finish();
        advance(20);
        assertEquals("", strip.renderFrame(),
                "the streamed line must not reach the terminal while the gate is closed");

        advance(TurbulenceGate.DEFAULT_QUIET_MILLIS);
        String redraw = displayedCharacters(strip.renderFrame());
        assertEquals(1, occurrencesOf(redraw, "streamedwhilethescreenwasblank"),
                "output produced during turbulence must survive into the redraw, exactly once: "
                        + redraw);
    }

    /** The same for the user's own half-written line: a gesture must never cost them their draft. */
    @Test
    void textTypedDuringTurbulenceAppearsInTheRedraw() {
        AtomicReference<String> typed = new AtomicReference<>("half a thought");
        strip.bindDock(width -> List.of(new AttributedString(DOCK_ROW)));
        strip.bindInputBox(() -> new CaptureDisplay.Frame(
                List.of(new AttributedString("> " + typed.get())),
                2 + typed.get().length(), Math.max(1, geometry.get().getColumns()), false));
        strip.renderFrame();

        resizeTo(76, 12);
        strip.renderFrame();
        typed.set("half a thought completed while blank");
        advance(30);
        assertEquals("", strip.renderFrame(), "keystrokes do not repaint while the gate is closed");

        advance(TurbulenceGate.DEFAULT_QUIET_MILLIS);
        String redraw = displayedCharacters(strip.renderFrame());
        assertEquals(1, occurrencesOf(redraw, "halfathoughtcompletedwhileblank"),
                "text typed during turbulence must appear in the redraw, exactly once: " + redraw);
        assertEquals(0, occurrencesOf(redraw, "halfathought>"),
                "the earlier draft must not be left behind by the redraw: " + redraw);
    }


    // --- the modal lease (ADR 0010 amendment A12) -------------------------------------------------

    /**
     * While a modal owns the terminal the [[活区]] writes <b>nothing</b>, and a resize during it is
     * paid for exactly once on return.
     *
     * <p>This is the defect A12 closes, not merely a new feature: a resize posts a frame regardless of
     * idleness, so before the lease existed resizing during an open [[转写浏览层]] painted live-region
     * rows onto the alternate screen over it — and under the reset it would have erased the visible
     * screen and the saved scrollback while the modal was up, then handed back a primary screen it had
     * just destroyed.
     */
    @Test
    void aModalHoldingTheLeaseSilencesEveryFrameAndIsRepaintedOnceOnReturn() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.bindLeadingEntry(spinner("Thinking... (esc to cancel, 1s)"));
        strip.renderFrame();

        strip.acquireModalLease();
        resizeTo(60, 20);
        for (int frame = 0; frame < 40; frame++) {
            advance(33);
            assertEquals("", strip.renderFrame(),
                    "no byte of the live region may reach the terminal while a modal owns it");
        }
        strip.releaseModalLease();

        advance(33);
        String back = displayedCharacters(strip.renderFrame());
        assertTrue(back.contains("alreadycommitted"),
                "returning from a modal that spanned a resize repaints the replay window: " + back);
        assertTrue(back.contains(DOCK_ROW), back);
        advance(TurbulenceGate.DEFAULT_QUIET_MILLIS * 5);
        assertEquals("", displayedCharacters(strip.renderFrame()),
                "and repaints it once — a returned modal owes nothing further");
    }

    /**
     * No resize, no repaint. A [[destructive resize reset]] erases the terminal's saved scrollback, so
     * firing one just because a modal closed would destroy history the user never asked to lose.
     */
    @Test
    void returningFromAModalOnAnUnchangedGeometryRepaintsNothing() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.renderFrame();

        strip.acquireModalLease();
        for (int frame = 0; frame < 40; frame++) {
            advance(33);
            assertEquals("", strip.renderFrame());
        }
        strip.releaseModalLease();

        advance(33);
        assertEquals("", displayedCharacters(strip.renderFrame()),
                "closing a modal on an unchanged geometry must not erase and repaint anything");
    }

    /**
     * Nested modals hand the terminal back only once, on the outermost return: a palette opened from
     * inside another modal must not let the render loop write over the modal that is still up.
     */
    @Test
    void aNestedModalKeepsTheTerminalUntilTheOutermostOneReturns() {
        bindLiveRegionFurniture();
        committedAnswer("already committed\n");
        strip.renderFrame();

        strip.acquireModalLease();
        strip.acquireModalLease();
        resizeTo(60, 20);
        advance(200);
        assertEquals("", strip.renderFrame());

        strip.releaseModalLease();
        advance(200);
        assertEquals("", strip.renderFrame(),
                "the inner return does not hand the terminal back: one modal is still up");

        strip.releaseModalLease();
        advance(33);
        assertTrue(displayedCharacters(strip.renderFrame()).contains("alreadycommitted"),
                "the outermost return pays the single owed reset");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A fixed one-row live entry standing in for the activity spinner. */
    private static StripEntry spinner(String text) {
        return new StripEntry() {
            @Override
            public List<AttributedString> render(int width) {
                return List.of(new AttributedString(text));
            }

            @Override
            public List<AttributedString> freezeTopRows(int minimumRows, int width) {
                return List.of();
            }
        };
    }

}
