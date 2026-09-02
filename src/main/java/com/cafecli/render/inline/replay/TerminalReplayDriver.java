package com.cafecli.render.inline.replay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Offline command-line oracle for a recorded trace and its paired Terminal.app history. */
public final class TerminalReplayDriver {

    private static final String USAGE = "Usage: TerminalReplayDriver [--anchor TEXT] <trace.jsonl> <truth.txt>";

    private TerminalReplayDriver() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Runs the offline comparison. Returns 0 for an exact match, 1 for a mismatch, and 2 for invalid
     * input, an incomplete trace, or an I/O failure.
     */
    public static int run(String[] args, PrintStream standardOutput, PrintStream standardError) {
        Arguments parsed;
        try {
            parsed = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            standardError.println(e.getMessage());
            standardError.println(USAGE);
            return 2;
        }

        try {
            ReplayTrace trace = ReplayTrace.read(parsed.trace());
            trace.requireComplete();
            TerminalReplay.Result replayed = TerminalReplay.replay(trace);
            String truth = Files.readString(parsed.truth(), StandardCharsets.UTF_8);
            TerminalReplay.Comparison comparison = TerminalReplay.compareExact(
                    truth, replayed.text(), parsed.anchor());
            if (comparison.matches()) {
                standardOutput.println("MATCH: " + comparison.diagnostic());
                return 0;
            }
            standardError.println("MISMATCH: " + comparison.diagnostic());
            return 1;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            standardError.println("REPLAY ERROR: " + e.getMessage());
            return 2;
        }
    }

    private record Arguments(Path trace, Path truth, String anchor) {
        private static Arguments parse(String[] args) {
            if (args == null) {
                throw new IllegalArgumentException("arguments are required");
            }
            List<String> positional = new ArrayList<>();
            String anchor = null;
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if ("--anchor".equals(argument)) {
                    if (++i >= args.length || args[i].isEmpty()) {
                        throw new IllegalArgumentException("--anchor requires a non-empty value");
                    }
                    anchor = args[i];
                } else if (argument.startsWith("--anchor=")) {
                    anchor = argument.substring("--anchor=".length());
                    if (anchor.isEmpty()) {
                        throw new IllegalArgumentException("--anchor requires a non-empty value");
                    }
                } else if (argument.startsWith("-")) {
                    throw new IllegalArgumentException("unknown option: " + argument);
                } else {
                    positional.add(argument);
                }
            }
            if (positional.size() != 2) {
                throw new IllegalArgumentException("trace and truth paths are required");
            }
            return new Arguments(Path.of(positional.get(0)), Path.of(positional.get(1)), anchor);
        }
    }
}
