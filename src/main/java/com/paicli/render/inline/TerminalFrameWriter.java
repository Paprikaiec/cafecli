package com.paicli.render.inline;

import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Terminal boundary used exclusively by the render-loop writer thread. */
final class TerminalFrameWriter implements RenderLoop.FrameWriter {

    private final Terminal terminal;
    private final PrintWriter writer;
    private final boolean terminalOwnsWriter;

    TerminalFrameWriter(Terminal terminal, PrintStream fallback) {
        this.terminal = terminal;
        PrintWriter terminalWriter = terminal == null ? null : terminal.writer();
        this.terminalOwnsWriter = terminalWriter != null;
        this.writer = terminalWriter != null
                ? terminalWriter
                : new PrintWriter(fallback, false, StandardCharsets.UTF_8);
    }

    @Override
    public void write(String frame) {
        writer.print(frame);
    }

    @Override
    public void flush() {
        if (terminalOwnsWriter) {
            terminal.flush();
        } else {
            writer.flush();
        }
    }
}
