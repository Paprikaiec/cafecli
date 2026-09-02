package com.cafecli.render.inline.replay;

import com.cafecli.render.inline.TerminalCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Opt-in, low-perturbation recorder for the exact Java-side terminal write stream and totally ordered
 * observations of terminal geometry. The real-terminal factory runs a daemonized native
 * {@code TIOCGWINSZ} sampler so resize transitions coalesced out of SIGWINCH delivery still enter the
 * timeline. Native observations and writes share one sequencing lock; unchanged samples allocate no
 * event. This is recorder observation order, not the unobservable ordering of PTY consumption and
 * reflow inside the terminal emulator. Recording only appends immutable in-memory values; JSON/Base64
 * and filesystem work happen on close (or via the shutdown-hook fallback).
 */
public final class TerminalReplayRecorder implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TerminalReplayRecorder DISABLED = new TerminalReplayRecorder();
    /** One-millisecond native polling, with no {@code stty} process fork. */
    private static final long DEFAULT_GEOMETRY_SAMPLE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SAMPLER_GENERATION_BASE = 1L << 62;
    private static final int MAX_EARLY_RESIZE_OBSERVATIONS = 256;

    private final boolean enabled;
    private final Path output;
    private final ReplayTrace.Header header;
    private final long startedNanos;
    private final Object lock = new Object();
    private final List<RecordedEvent> events = new ArrayList<>();
    private final Map<Long, RecordedResize> unresolvedResizes = new HashMap<>();
    private final Map<Long, ObservedResize> earlyResizeObservations = new LinkedHashMap<>();
    private final Set<Long> recordedResizeGenerations = new HashSet<>();
    private final Thread shutdownHook;
    private final LongSupplier resizeSizeProbe;
    private final long geometrySampleIntervalNanos;
    private final Thread geometrySampler;

    /** Guarded by {@link #lock}; initialized from the trace header's startup geometry. */
    private long lastSampledGeometry;
    /** Guarded by {@link #lock}; kept far from the renderer's small, ascending generations. */
    private long nextSamplerGeneration = SAMPLER_GENERATION_BASE;

    private volatile boolean closed;
    private volatile boolean geometrySamplerStopRequested;
    private volatile IOException writeFailure;

    private TerminalReplayRecorder() {
        enabled = false;
        output = null;
        header = null;
        startedNanos = 0;
        shutdownHook = null;
        resizeSizeProbe = null;
        geometrySampleIntervalNanos = 0;
        geometrySampler = null;
    }

    private TerminalReplayRecorder(Path output,
                                   ReplayTrace.Header header,
                                   LongSupplier resizeSizeProbe,
                                   boolean sampleGeometry,
                                   long geometrySampleIntervalNanos) {
        this.enabled = true;
        this.output = output.toAbsolutePath().normalize();
        this.header = header;
        this.startedNanos = System.nanoTime();
        this.resizeSizeProbe = resizeSizeProbe;
        this.geometrySampleIntervalNanos = geometrySampleIntervalNanos;
        this.lastSampledGeometry = packSize(header.initialRows(), header.initialColumns());
        this.shutdownHook = new Thread(() -> persist(false), "cafecli-terminal-replay-recorder");
        this.geometrySampler = sampleGeometry
                ? Thread.ofPlatform()
                        .daemon(true)
                        .name("cafecli-terminal-geometry-sampler")
                        .unstarted(this::sampleGeometryLoop)
                : null;
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException ignored) {
            // Explicit close remains available when shutdown hooks cannot be registered.
        }
        if (geometrySampler != null) {
            geometrySampler.start();
        }
    }

    public static TerminalReplayRecorder disabled() {
        return DISABLED;
    }

    /** Capture startup metadata once and enable native geometry sampling for this opt-in trace. */
    public static TerminalReplayRecorder create(Path output, Terminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        Size size = TerminalCapabilities.safeSize(terminal);
        List<String> flags = terminalOutputFlags(terminal);
        return create(output, terminal.getType(), size.getColumns(), size.getRows(), flags,
                NativeTerminalSizeProbe.forSystemOutput(), true,
                DEFAULT_GEOMETRY_SAMPLE_INTERVAL_NANOS);
    }

    public static TerminalReplayRecorder create(Path output,
                                                 String terminalType,
                                                 int initialColumns,
                                                 int initialRows,
                                                 List<String> outputFlags) {
        return create(output, terminalType, initialColumns, initialRows, outputFlags, null,
                false, 0);
    }

    static TerminalReplayRecorder createForTest(Path output,
                                                String terminalType,
                                                int initialColumns,
                                                int initialRows,
                                                List<String> outputFlags,
                                                LongSupplier resizeSizeProbe) {
        return create(output, terminalType, initialColumns, initialRows, outputFlags,
                Objects.requireNonNull(resizeSizeProbe, "resizeSizeProbe"), false, 0);
    }

    static TerminalReplayRecorder createWithGeometrySamplerForTest(Path output,
                                                                    String terminalType,
                                                                    int initialColumns,
                                                                    int initialRows,
                                                                    List<String> outputFlags,
                                                                    LongSupplier resizeSizeProbe,
                                                                    long sampleIntervalNanos) {
        if (sampleIntervalNanos <= 0) {
            throw new IllegalArgumentException("Geometry sample interval must be positive");
        }
        return create(output, terminalType, initialColumns, initialRows, outputFlags,
                Objects.requireNonNull(resizeSizeProbe, "resizeSizeProbe"), true,
                sampleIntervalNanos);
    }

    private static TerminalReplayRecorder create(Path output,
                                                 String terminalType,
                                                 int initialColumns,
                                                 int initialRows,
                                                 List<String> outputFlags,
                                                 LongSupplier resizeSizeProbe,
                                                 boolean sampleGeometry,
                                                 long geometrySampleIntervalNanos) {
        Objects.requireNonNull(output, "output");
        if (sampleGeometry && resizeSizeProbe == null) {
            sampleGeometry = false;
        }
        List<String> flags = outputFlags == null ? List.of() : List.copyOf(outputFlags);
        ReplayTrace.LineFeedMode lineFeedMode = flags.contains(Attributes.OutputFlag.OPOST.name())
                && flags.contains(Attributes.OutputFlag.ONLCR.name())
                ? ReplayTrace.LineFeedMode.CRLF
                : ReplayTrace.LineFeedMode.LF;
        ReplayTrace.Header header = new ReplayTrace.Header(
                ReplayTrace.CURRENT_FORMAT_VERSION,
                terminalType,
                initialColumns,
                initialRows,
                flags,
                lineFeedMode);
        return new TerminalReplayRecorder(output, header, resizeSizeProbe, sampleGeometry,
                geometrySampleIntervalNanos);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Record the exact UTF-8 Java write boundary before terminal I/O can interleave a WINCH. */
    public void recordWrite(String utf8Text) {
        if (!enabled || closed || utf8Text == null || utf8Text.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            events.add(new RecordedWrite(events.size(), elapsedNanos(), utf8Text));
        }
    }

    /**
     * Append the timestamped WINCH marker at signal time. With the geometry sampler active, the
     * marker is resolved to the current sampled dimensions and is deliberately a replay no-op: sampled
     * transitions are authoritative, so an independently queried signal cannot reorder them. Without
     * the sampler, the immediate native probe and existing render-frame observation remain fallbacks.
     */
    public void recordResizeSignal(long generation) {
        if (!enabled || closed) {
            return;
        }
        if (generation < 0) {
            throw new IllegalArgumentException("Resize generation must be non-negative");
        }
        synchronized (lock) {
            if (closed || recordedResizeGenerations.contains(generation)) {
                return;
            }
            RecordedResize resize = new RecordedResize(events.size(), elapsedNanos(), generation);
            events.add(resize);
            unresolvedResizes.put(generation, resize);
            recordedResizeGenerations.add(generation);
            if (geometrySampler != null) {
                // The sampler is the sole authority for geometry transitions. A delivered WINCH is
                // retained as a diagnostic marker at the current sampled geometry, making it a
                // semantic no-op rather than allowing a later independent ioctl to roll replay back.
                resize.rows = (int) (lastSampledGeometry >>> 32);
                resize.columns = (int) lastSampledGeometry;
                unresolvedResizes.remove(generation);
                return;
            }
            ObservedResize observed = earlyResizeObservations.remove(generation);
            if (observed != null) {
                resize.rows = observed.rows();
                resize.columns = observed.columns();
                unresolvedResizes.remove(generation);
                return;
            }
        }
        if (resizeSizeProbe != null) {
            long packed = 0;
            try {
                packed = resizeSizeProbe.getAsLong();
            } catch (RuntimeException | LinkageError ignored) {
                // The later render-frame observation may still resolve this generation.
            }
            int rows = (int) (packed >>> 32);
            int columns = (int) packed;
            if (rows > 0 && columns > 0) {
                observeResize(generation, rows, columns);
            }
        }
    }

    static long packSize(int rows, int columns) {
        return NativeTerminalSizeProbe.packed(rows, columns);
    }

    /**
     * Pair one generation with the existing once-per-frame terminal-size observation. On the
     * sampler-free path, retain an observation that wins the lock before its signal marker does.
     */
    public void observeResize(long generation, int rows, int columns) {
        if (!enabled || closed) {
            return;
        }
        if (rows <= 0 || columns <= 0) {
            throw new IllegalArgumentException("Resize dimensions must be positive");
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            RecordedResize resize = unresolvedResizes.remove(generation);
            if (resize != null) {
                resize.rows = rows;
                resize.columns = columns;
            } else if (geometrySampler == null && !recordedResizeGenerations.contains(generation)) {
                cacheEarlyResizeObservation(generation, rows, columns);
            }
        }
    }

    /** Guarded by {@link #lock}; discard the oldest unseen generation under sustained races. */
    private void cacheEarlyResizeObservation(long generation, int rows, int columns) {
        if (!earlyResizeObservations.containsKey(generation)
                && earlyResizeObservations.size() >= MAX_EARLY_RESIZE_OBSERVATIONS) {
            var oldest = earlyResizeObservations.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        earlyResizeObservations.put(generation, new ObservedResize(rows, columns));
    }

    /** Convenience for deterministic producers that already own both the signal and its dimensions. */
    public void recordResize(long generation, int rows, int columns) {
        recordResizeSignal(generation);
        observeResize(generation, rows, columns);
    }

    public Optional<IOException> writeFailure() {
        return Optional.ofNullable(writeFailure);
    }

    private void sampleGeometryLoop() {
        while (!closed && !geometrySamplerStopRequested) {
            sampleGeometry();
            if (geometrySamplerStopRequested) {
                return;
            }
            LockSupport.parkNanos(geometrySampleIntervalNanos);
            if (Thread.interrupted() && (closed || geometrySamplerStopRequested)) {
                return;
            }
        }
    }

    private long probeGeometry() {
        try {
            return resizeSizeProbe == null ? 0 : resizeSizeProbe.getAsLong();
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    /** Linearize the native observation with writes and signal markers on the recorder event lock. */
    private void sampleGeometry() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            long packed = probeGeometry();
            int rows = (int) (packed >>> 32);
            int columns = (int) packed;
            if (rows <= 0 || columns <= 0 || packed == lastSampledGeometry) {
                return;
            }
            long generation = nextSamplerGeneration();
            events.add(new RecordedResize(events.size(), elapsedNanos(), generation, rows, columns));
            recordedResizeGenerations.add(generation);
            lastSampledGeometry = packed;
        }
    }

    private long nextSamplerGeneration() {
        while (recordedResizeGenerations.contains(nextSamplerGeneration)) {
            if (nextSamplerGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Terminal replay sampler generation space exhausted");
            }
            nextSamplerGeneration++;
        }
        return nextSamplerGeneration++;
    }

    @Override
    public void close() {
        persist(true);
    }

    private void persist(boolean removeHook) {
        if (!enabled) {
            return;
        }
        stopGeometrySampler();
        ReplayTrace snapshot;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = snapshot();
        }
        if (removeHook && shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException | SecurityException ignored) {
                // The JVM may already be shutting down; the closed guard makes the hook a no-op.
            }
        }
        try {
            writeAtomically(snapshot);
        } catch (IOException | RuntimeException e) {
            writeFailure = e instanceof IOException io
                    ? io
                    : new IOException("Failed to persist terminal replay trace", e);
        }
    }

    private void stopGeometrySampler() {
        if (geometrySampler == null || Thread.currentThread() == geometrySampler) {
            return;
        }
        geometrySamplerStopRequested = true;
        geometrySampler.interrupt();
        boolean interrupted = false;
        while (geometrySampler.isAlive()) {
            try {
                geometrySampler.join();
            } catch (InterruptedException e) {
                interrupted = true;
                geometrySampler.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ReplayTrace snapshot() {
        List<ReplayTrace.Event> snapshot = new ArrayList<>(events.size());
        for (RecordedEvent event : events) {
            if (event instanceof RecordedWrite write) {
                snapshot.add(new ReplayTrace.WriteEvent(write.sequence, write.elapsedNanos,
                        write.text.getBytes(StandardCharsets.UTF_8)));
            } else if (event instanceof RecordedResize resize) {
                snapshot.add(new ReplayTrace.ResizeEvent(resize.sequence, resize.elapsedNanos,
                        resize.generation, resize.rows, resize.columns));
            }
        }
        return new ReplayTrace(header, snapshot);
    }

    private void writeAtomically(ReplayTrace trace) throws IOException {
        Path parent = output.getParent();
        Files.createDirectories(parent);
        String fileName = output.getFileName().toString();
        Path temporary = Files.createTempFile(parent, "." + fileName + ".", ".tmp");
        boolean moved = false;
        try {
            writeJsonl(temporary, trace);
            try {
                Files.move(temporary, output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void writeJsonl(Path path, ReplayTrace trace) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeLine(writer, headerJson(trace.header()));
            for (ReplayTrace.Event event : trace.events()) {
                writeLine(writer, eventJson(event));
            }
        }
    }

    private static ObjectNode headerJson(ReplayTrace.Header header) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("type", "header");
        json.put("format", ReplayTrace.FORMAT_NAME);
        json.put("version", header.formatVersion());
        json.put("terminalType", header.terminalType());
        json.put("initialColumns", header.initialColumns());
        json.put("initialRows", header.initialRows());
        json.put("encoding", header.encoding());
        ArrayNode flags = json.putArray("outputFlags");
        header.outputFlags().forEach(flags::add);
        json.put("lineFeedMode", header.lineFeedMode().name());
        return json;
    }

    private static ObjectNode eventJson(ReplayTrace.Event event) {
        ObjectNode json = MAPPER.createObjectNode();
        if (event instanceof ReplayTrace.WriteEvent write) {
            json.put("type", "write");
            json.put("sequence", write.sequence());
            json.put("elapsedNanos", write.elapsedNanos());
            json.put("bytesBase64", Base64.getEncoder().encodeToString(write.bytes()));
        } else if (event instanceof ReplayTrace.ResizeEvent resize) {
            json.put("type", "resize");
            json.put("sequence", resize.sequence());
            json.put("elapsedNanos", resize.elapsedNanos());
            json.put("generation", resize.generation());
            json.put("resolved", resize.resolved());
            if (resize.resolved()) {
                json.put("rows", resize.rows());
                json.put("columns", resize.columns());
            }
        } else {
            throw new IllegalArgumentException("Unsupported replay event: " + event);
        }
        return json;
    }

    private static void writeLine(BufferedWriter writer, ObjectNode json) throws IOException {
        writer.write(MAPPER.writeValueAsString(json));
        writer.write('\n');
    }

    private long elapsedNanos() {
        return Math.max(0, System.nanoTime() - startedNanos);
    }

    private static List<String> terminalOutputFlags(Terminal terminal) {
        try {
            Attributes attributes = terminal.getAttributes();
            if (attributes == null) {
                return List.of();
            }
            return attributes.getOutputFlags().stream().map(Enum::name).sorted().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private sealed interface RecordedEvent permits RecordedWrite, RecordedResize {
    }

    private record RecordedWrite(long sequence, long elapsedNanos, String text) implements RecordedEvent {
    }

    private record ObservedResize(int rows, int columns) {
    }

    private static final class RecordedResize implements RecordedEvent {
        private final long sequence;
        private final long elapsedNanos;
        private final long generation;
        private Integer rows;
        private Integer columns;

        private RecordedResize(long sequence, long elapsedNanos, long generation) {
            this.sequence = sequence;
            this.elapsedNanos = elapsedNanos;
            this.generation = generation;
        }

        private RecordedResize(long sequence,
                               long elapsedNanos,
                               long generation,
                               int rows,
                               int columns) {
            this(sequence, elapsedNanos, generation);
            this.rows = rows;
            this.columns = columns;
        }
    }
}
