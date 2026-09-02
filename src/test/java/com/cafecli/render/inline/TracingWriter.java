package com.cafecli.render.inline;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * A Writer that records every write together with the JLine call site
 * that produced it, then forwards to the delegate.
 */
public class TracingWriter extends Writer {

    public static final class WriteRecord {
        public final String phase;
        public final String text;
        public final String callSite;
        public final String fullStack;

        WriteRecord(String phase, String text, String callSite, String fullStack) {
            this.phase = phase;
            this.text = text;
            this.callSite = callSite;
            this.fullStack = fullStack;
        }

        public String visible() {
            StringBuilder sb = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c == 27) sb.append("\\e");
                else if (c == '\n') sb.append("\\n");
                else if (c == '\r') sb.append("\\r");
                else if (c < 32) sb.append("\\x").append(Integer.toHexString(c));
                else sb.append(c);
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "[" + phase + "] " + callSite + "  ->  \"" + visible() + "\"";
        }
    }

    private final Writer delegate;
    private final List<WriteRecord> records = new ArrayList<>();
    private volatile String phase = "init";
    private volatile boolean recording = true;
    private long bytes = 0;

    public TracingWriter(Writer delegate) {
        this.delegate = delegate;
    }

    public void phase(String phase) {
        this.phase = phase;
    }

    public void recording(boolean on) {
        this.recording = on;
    }

    public synchronized List<WriteRecord> records() {
        return new ArrayList<>(records);
    }

    public synchronized long charsWritten() {
        return bytes;
    }

    public synchronized void reset() {
        records.clear();
        bytes = 0;
    }

    private synchronized void record(String text) {
        bytes += text.length();
        if (!recording) return;
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        String site = "<unknown>";
        StringBuilder full = new StringBuilder();
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("java.lang.Thread")
                    || cn.startsWith("com.cafecli.render.inline.TracingWriter")
                    || cn.startsWith("java.io.Writer")
                    || cn.startsWith("java.io.PrintWriter")) {
                continue;
            }
            if ("<unknown>".equals(site)) {
                site = cn + "." + e.getMethodName() + ":" + e.getLineNumber();
            }
            if (full.length() < 1200) {
                full.append("    at ").append(cn).append('.').append(e.getMethodName())
                        .append(':').append(e.getLineNumber()).append('\n');
            }
        }
        records.add(new WriteRecord(phase, text, site, full.toString()));
    }

    @Override
    public void write(int c) throws IOException {
        record(String.valueOf((char) c));
        delegate.write(c);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        record(new String(cbuf, off, len));
        delegate.write(cbuf, off, len);
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        record(str.substring(off, off + len));
        delegate.write(str, off, len);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
