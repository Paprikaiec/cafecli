package com.cafecli.render.inline.replay;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact histories captured from Terminal.app, never generated from the emulator itself. */
class TerminalAppConformanceTest {

    private static final String RESOURCE_ROOT =
            "/com/cafecli/render/inline/replay/conformance/";
    @ParameterizedTest(name = "Terminal.app conformance: {0}")
    @ValueSource(strings = {
            "hard-soft-wrap",
            "exact-multiple-cursor",
            "cjk-right-edge",
            "clear-to-end-of-screen",
            "height-shrink-scrollback",
            "cursor-down-clamp",
            // Terminal.app does not blank the visible rows on CSI 2 J — it pushes them into saved
            // scrollback first, which is why `clear(1)` leaves the pre-clear screen scrollable.
            // Captured 2026-08-21. It is the fact that decides the order of the erase half of a
            // [[destructive resize reset]], and the model was blind to it until this fixture.
            "erase-screen-pushes-scrollback",
            // …and the production erase written in that order leaves nothing reachable behind:
            // neither the screen CSI 2 J deposited nor the scrollback that was there before it.
            "reset-erase-leaves-nothing"
    })
    void replayMatchesTheExactCapturedTerminalAppHistory(String fixture) throws Exception {
        URL traceResource = getClass().getResource(RESOURCE_ROOT + fixture + ".jsonl");
        URL truthResource = getClass().getResource(RESOURCE_ROOT + fixture + ".history.txt");
        assertNotNull(traceResource, "missing captured trace for " + fixture);
        assertNotNull(truthResource, "missing Terminal.app truth for " + fixture);

        ReplayTrace trace = ReplayTrace.read(Path.of(traceResource.toURI())).requireComplete();
        String truth = Files.readString(Path.of(truthResource.toURI()), StandardCharsets.UTF_8);
        TerminalReplay.Result replayed = TerminalReplay.replay(trace);
        TerminalReplay.Comparison comparison = TerminalReplay.compareExact(
                truth, replayed.text(), TerminalConformanceDriver.ANCHOR);

        assertTrue(comparison.matches(), () -> fixture + ": " + comparison.diagnostic()
                + "\nreplayed physical document:\n"
                + TerminalScreenModel.dump(replayed.document()));
    }

    /**
     * Keeps the {@code reset-erase-leaves-nothing} fixture honest — it is not a gate on rendering.
     *
     * <p>A fixture is frozen bytes, so it goes on passing after the sequence it was capturing has
     * changed underneath it, and would then quietly be measuring a terminal fact about something we
     * no longer send. Whether the renderer leaves copies on screen is settled by
     * {@code ScreenTruthRegressionTest} against a screen; this only says "recapture me".
     */
    @org.junit.jupiter.api.Test
    void theResetEraseFixtureStillMeasuresTheSequenceTheRendererSends() throws Exception {
        URL traceResource = getClass().getResource(RESOURCE_ROOT + "reset-erase-leaves-nothing.jsonl");
        assertNotNull(traceResource, "missing captured trace for reset-erase-leaves-nothing");
        ReplayTrace trace = ReplayTrace.read(Path.of(traceResource.toURI()));
        String submitted = trace.events().stream()
                .filter(event -> event instanceof ReplayTrace.WriteEvent)
                .map(event -> ((ReplayTrace.WriteEvent) event).utf8Text())
                .reduce("", String::concat);
        assertTrue(submitted.contains(com.cafecli.render.inline.AnsiSeq.ERASE_SCREEN_AND_SAVED_SCROLLBACK),
                "the erase the renderer sends today is absent from the fixture that measures it; "
                        + "recapture reset-erase-leaves-nothing");
    }
}
