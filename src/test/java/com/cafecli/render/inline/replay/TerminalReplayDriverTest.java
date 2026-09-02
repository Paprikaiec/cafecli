package com.cafecli.render.inline.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalReplayDriverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void comparesARecordedTraceWithTerminalTruthFromTheSharedAnchor() throws Exception {
        Path trace = temporaryDirectory.resolve("session.jsonl");
        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                trace, "xterm-256color", 24, 3, List.of())) {
            recorder.recordWrite("CAFECLI_REPLAY_START\r\nmatching history");
        }
        Path truth = temporaryDirectory.resolve("truth.txt");
        Files.writeString(truth,
                "shell launch noise\nCAFECLI_REPLAY_START\nmatching history\n\n",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exitCode = TerminalReplayDriver.run(
                new String[]{"--anchor", "CAFECLI_REPLAY_START", trace.toString(), truth.toString()},
                new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode, standardError.toString(StandardCharsets.UTF_8));
        assertTrue(standardOutput.toString(StandardCharsets.UTF_8).contains("history matches exactly"));
        assertEquals("", standardError.toString(StandardCharsets.UTF_8));
    }

    @Test
    void mismatchExitReportsTheFirstExactDifference() throws Exception {
        Path trace = temporaryDirectory.resolve("mismatch.jsonl");
        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                trace, "xterm-256color", 24, 2, List.of())) {
            recorder.recordWrite("CAFECLI_REPLAY_START\r\nactual");
        }
        Path truth = temporaryDirectory.resolve("mismatch-truth.txt");
        Files.writeString(truth,
                "shell noise\nCAFECLI_REPLAY_START\nexpected",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exitCode = TerminalReplayDriver.run(
                new String[]{"--anchor=CAFECLI_REPLAY_START", trace.toString(), truth.toString()},
                new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertEquals("", standardOutput.toString(StandardCharsets.UTF_8));
        assertTrue(standardError.toString(StandardCharsets.UTF_8)
                .contains("MISMATCH: history differs at UTF-8 byte 21 (line 2, column 1)"));
    }

    @Test
    void incompleteResizeTimelineIsRejectedInsteadOfGuessed() throws Exception {
        Path trace = temporaryDirectory.resolve("incomplete.jsonl");
        try (TerminalReplayRecorder recorder = TerminalReplayRecorder.create(
                trace, "xterm-256color", 24, 2, List.of())) {
            recorder.recordWrite("CAFECLI_REPLAY_START");
            recorder.recordResizeSignal(7);
        }
        Path truth = temporaryDirectory.resolve("unused-truth.txt");
        Files.writeString(truth, "CAFECLI_REPLAY_START", StandardCharsets.UTF_8);
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exitCode = TerminalReplayDriver.run(
                new String[]{trace.toString(), truth.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertTrue(standardError.toString(StandardCharsets.UTF_8)
                .contains("REPLAY ERROR: Replay trace has unresolved resize generations: [7]"));
    }

    @Test
    void serializedResolvedFlagMustAgreeWithResizeDimensions() throws Exception {
        Path trace = temporaryDirectory.resolve("contradictory-resize.jsonl");
        Files.writeString(trace, """
                {"type":"header","format":"cafecli-terminal-replay","version":1,"terminalType":"xterm","initialColumns":24,"initialRows":2,"encoding":"UTF-8","outputFlags":[],"lineFeedMode":"LF"}
                {"type":"resize","sequence":0,"elapsedNanos":1,"generation":7,"resolved":true}
                """, StandardCharsets.UTF_8);
        Path truth = temporaryDirectory.resolve("unused-contradictory-truth.txt");
        Files.writeString(truth, "", StandardCharsets.UTF_8);
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();

        int exitCode = TerminalReplayDriver.run(
                new String[]{trace.toString(), truth.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertTrue(standardError.toString(StandardCharsets.UTF_8)
                .contains("Resize field 'resolved' disagrees with rows/columns"));
    }
}
