package com.cafecli.render.inline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jline.terminal.impl.ExternalTerminal;

/**
 * An ExternalTerminal whose {@code writer()} is wrapped in a
 * {@link TracingWriter}, so that every character JLine emits is recorded with
 * the exact JLine call site that emitted it.
 *
 * <p>Why subclass the concrete terminal rather than wrap {@code Terminal}:
 * {@code Status.getStatus(terminal, create)} requires {@code instanceof AbstractTerminal},
 * so a plain delegating proxy would silently disable the status bar and invalidate
 * the experiment. Subclassing keeps the real type identity.
 *
 * <p>{@code AbstractTerminal.puts()} routes through {@code Curses.tputs(writer(), ...)}
 * and {@code AbstractTerminal.flush()} routes through {@code writer().flush()},
 * so overriding {@code writer()} intercepts capability output too.
 */
public class TracingTerminal extends ExternalTerminal {

    private final TracingWriter tracing;
    private final PrintWriter tracingPrintWriter;

    public TracingTerminal(String name, String type, InputStream in, OutputStream out) throws IOException {
        this(name, type, in, out, StandardCharsets.UTF_8);
    }

    private TracingTerminal(String name, String type, InputStream in, OutputStream out, Charset cs)
            throws IOException {
        super(name, type, in, out, cs);
        this.tracing = new TracingWriter(super.writer());
        this.tracingPrintWriter = new PrintWriter(tracing);
    }

    public TracingWriter tracer() {
        return tracing;
    }

    @Override
    public PrintWriter writer() {
        // super.writer() performs checkClosed(); keep that behaviour.
        PrintWriter real = super.writer();
        // The super constructor may touch writer() before our fields are assigned.
        return tracingPrintWriter != null ? tracingPrintWriter : real;
    }
}
