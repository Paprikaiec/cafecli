package com.cafecli.render.inline.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Immutable, versioned terminal-write and resize timeline used by offline replay. */
public final class ReplayTrace {

    public static final int CURRENT_FORMAT_VERSION = 1;
    static final String FORMAT_NAME = "cafecli-terminal-replay";
    static final String ENCODING = "UTF-8";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Header header;
    private final List<Event> events;

    public ReplayTrace(Header header, List<? extends Event> events) {
        this.header = Objects.requireNonNull(header, "header");
        this.events = List.copyOf(events == null ? List.of() : events);
        validateEventOrder(this.events);
    }

    public Header header() {
        return header;
    }

    public List<Event> events() {
        return events;
    }

    /** False means at least one delivered WINCH could not be paired with its own size observation. */
    public boolean isComplete() {
        return events.stream()
                .filter(ResizeEvent.class::isInstance)
                .map(ResizeEvent.class::cast)
                .allMatch(ResizeEvent::resolved);
    }

    /**
     * Reject a trace that would otherwise tempt replay to assign a later terminal size to an earlier
     * coalesced WINCH. The unresolved event remains readable for diagnostics; it is never guessed.
     */
    public ReplayTrace requireComplete() {
        List<Long> unresolved = events.stream()
                .filter(ResizeEvent.class::isInstance)
                .map(ResizeEvent.class::cast)
                .filter(event -> !event.resolved())
                .map(ResizeEvent::generation)
                .toList();
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Replay trace has unresolved resize generations: " + unresolved);
        }
        return this;
    }

    public static ReplayTrace read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null) {
                throw new IOException("Replay trace is empty: " + path);
            }
            Header header = parseHeader(MAPPER.readTree(first));
            List<Event> events = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    throw new IOException("Blank JSONL record at line " + lineNumber);
                }
                try {
                    events.add(parseEvent(MAPPER.readTree(line)));
                } catch (IOException | RuntimeException e) {
                    throw new IOException("Invalid replay record at line " + lineNumber + ": "
                            + e.getMessage(), e);
                }
            }
            try {
                return new ReplayTrace(header, events);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid replay event order: " + e.getMessage(), e);
            }
        }
    }

    private static Header parseHeader(JsonNode node) throws IOException {
        if (!"header".equals(requiredText(node, "type"))) {
            throw new IOException("First JSONL record must be a replay header");
        }
        String format = requiredText(node, "format");
        if (!FORMAT_NAME.equals(format)) {
            throw new IOException("Unsupported replay format: " + format);
        }
        int version = requiredInt(node, "version");
        if (version != CURRENT_FORMAT_VERSION) {
            throw new IOException("Unsupported replay format version: " + version);
        }
        String encoding = requiredText(node, "encoding");
        if (!ENCODING.equalsIgnoreCase(encoding)) {
            throw new IOException("Unsupported replay encoding: " + encoding);
        }
        JsonNode flagsNode = node.get("outputFlags");
        if (flagsNode == null || !flagsNode.isArray()) {
            throw new IOException("Header field 'outputFlags' must be an array");
        }
        List<String> outputFlags = new ArrayList<>();
        for (JsonNode flag : flagsNode) {
            if (!flag.isTextual()) {
                throw new IOException("Header output flags must be strings");
            }
            outputFlags.add(flag.textValue());
        }
        LineFeedMode lineFeedMode;
        try {
            lineFeedMode = LineFeedMode.valueOf(requiredText(node, "lineFeedMode"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unsupported line-feed mode", e);
        }
        return new Header(version,
                requiredText(node, "terminalType"),
                requiredInt(node, "initialColumns"),
                requiredInt(node, "initialRows"),
                outputFlags,
                lineFeedMode);
    }

    private static Event parseEvent(JsonNode node) throws IOException {
        String type = requiredText(node, "type");
        long sequence = requiredLong(node, "sequence");
        long elapsedNanos = requiredLong(node, "elapsedNanos");
        return switch (type) {
            case "write" -> {
                try {
                    yield new WriteEvent(sequence, elapsedNanos,
                            Base64.getDecoder().decode(requiredText(node, "bytesBase64")));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Write event contains invalid Base64", e);
                }
            }
            case "resize" -> {
                long generation = requiredLong(node, "generation");
                JsonNode resolvedNode = node.get("resolved");
                if (resolvedNode == null || !resolvedNode.isBoolean()) {
                    throw new IOException("Required boolean field is missing: resolved");
                }
                boolean resolved = resolvedNode.booleanValue();
                JsonNode rowsNode = node.get("rows");
                JsonNode columnsNode = node.get("columns");
                Integer rows = rowsNode == null || rowsNode.isNull() ? null : integerValue(rowsNode, "rows");
                Integer columns = columnsNode == null || columnsNode.isNull()
                        ? null : integerValue(columnsNode, "columns");
                if (resolved != (rows != null && columns != null)) {
                    throw new IOException("Resize field 'resolved' disagrees with rows/columns");
                }
                yield new ResizeEvent(sequence, elapsedNanos, generation, rows, columns);
            }
            default -> throw new IOException("Unknown replay event type: " + type);
        };
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException("Required string field is missing: " + field);
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        return integerValue(value, field);
    }

    private static int integerValue(JsonNode value, String field) throws IOException {
        if (value == null || !value.canConvertToInt()) {
            throw new IOException("Required integer field is missing: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IOException("Required long field is missing: " + field);
        }
        return value.longValue();
    }

    private static void validateEventOrder(List<Event> events) {
        long expected = 0;
        for (Event event : events) {
            Objects.requireNonNull(event, "event");
            if (event.sequence() != expected) {
                throw new IllegalArgumentException("expected sequence " + expected
                        + " but found " + event.sequence());
            }
            if (event.elapsedNanos() < 0) {
                throw new IllegalArgumentException("elapsedNanos must be non-negative");
            }
            expected++;
        }
    }

    public enum LineFeedMode {
        LF,
        CRLF
    }

    public record Header(int formatVersion,
                         String terminalType,
                         int initialColumns,
                         int initialRows,
                         List<String> outputFlags,
                         LineFeedMode lineFeedMode) {
        public Header {
            if (formatVersion <= 0) {
                throw new IllegalArgumentException("formatVersion must be positive");
            }
            terminalType = terminalType == null || terminalType.isBlank() ? "unknown" : terminalType;
            if (initialColumns <= 0 || initialRows <= 0) {
                throw new IllegalArgumentException("Initial terminal dimensions must be positive");
            }
            outputFlags = outputFlags == null
                    ? List.of()
                    : outputFlags.stream().filter(Objects::nonNull).distinct().sorted().toList();
            lineFeedMode = Objects.requireNonNull(lineFeedMode, "lineFeedMode");
        }

        public String encoding() {
            return ENCODING;
        }
    }

    public sealed interface Event permits WriteEvent, ResizeEvent {
        long sequence();

        long elapsedNanos();
    }

    public record WriteEvent(long sequence, long elapsedNanos, byte[] bytes) implements Event {
        public WriteEvent {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public String utf8Text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public record ResizeEvent(long sequence,
                              long elapsedNanos,
                              long generation,
                              Integer rows,
                              Integer columns) implements Event {
        public ResizeEvent {
            if (generation < 0) {
                throw new IllegalArgumentException("Resize generation must be non-negative");
            }
            if ((rows == null) != (columns == null)) {
                throw new IllegalArgumentException("Resize rows and columns must both be present or absent");
            }
            if (rows != null && (rows <= 0 || columns <= 0)) {
                throw new IllegalArgumentException("Resize dimensions must be positive");
            }
        }

        public boolean resolved() {
            return rows != null;
        }
    }
}
