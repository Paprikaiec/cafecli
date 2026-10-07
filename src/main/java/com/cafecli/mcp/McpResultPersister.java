package com.cafecli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class McpResultPersister {
    /** 配置目录下存放工具结果的子目录名：{@code <configDir>/tool-results/<sessionId>/<scope>/}。 */
    public static final String RESULTS_DIR_NAME = "tool-results";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JQ_AVAILABLE_PROPERTY = "paicli.mcp.result.jq.available";
    private static final String JQ_AVAILABLE_ENV = "PAICLI_MCP_RESULT_JQ_AVAILABLE";

    private final Path projectRoot;
    private final String sessionId;
    private final String scope;
    private final BooleanSupplier jqProbe;
    private final Path toolResultsRoot;
    private volatile Boolean jqAvailable;

    public McpResultPersister(Path projectRoot, String sessionId, String scope) {
        this(projectRoot, sessionId, scope, McpResultPersister::probeJqOnPath);
    }

    McpResultPersister(Path projectRoot, String sessionId, String scope, BooleanSupplier jqProbe) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.sessionId = requirePathComponent("sessionId", sessionId);
        this.scope = requirePathComponent("scope", scope);
        this.jqProbe = Objects.requireNonNull(jqProbe, "jqProbe");
        this.toolResultsRoot = Path.of(
                com.cafecli.config.ConfigHome.configDirUnder(this.projectRoot).getFileName().toString(), RESULTS_DIR_NAME);
    }

    /** <configDir>/tool-results/<sessionId>，不做 normalize —— 与 ToolRegistry 现有的路径构造逐字一致 */
    public static Path sessionDirectory(Path projectRoot, String sessionId) {
        return com.cafecli.config.ConfigHome.configDirUnder(projectRoot).resolve(RESULTS_DIR_NAME).resolve(sessionId);
    }

    public StoredResult persistStructuredJson(String toolCallId, JsonNode payload) throws IOException {
        String prettyJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + System.lineSeparator();
        return persist(toolCallId, ".json", prettyJson);
    }

    public StoredResult persistText(String toolCallId, String text) throws IOException {
        return persist(toolCallId, ".txt", text == null ? "" : text);
    }

    private StoredResult persist(String toolCallId, String extension, String content) throws IOException {
        String safeToolCallId = requirePathComponent("toolCallId", toolCallId);
        Path relativePath = toolResultsRoot.resolve(sessionId).resolve(scope).resolve(safeToolCallId + extension);
        Path absolutePath = projectRoot.resolve(relativePath);
        Files.createDirectories(absolutePath.getParent());
        try {
            Files.writeString(absolutePath, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // Replayed tool calls must resolve to the original persisted result without rewriting it.
        }
        return new StoredResult(absolutePath, relativePath);
    }

    public String readHint(String relativePath) {
        String path = relativePath == null ? "" : relativePath;
        String fallback = "Use grep_code to locate values in " + path
                + ", then read_file with offset/limit to inspect nearby lines.";
        if (jqAvailable()) {
            return "Use jq for structured queries, for example: jq '<filter>' " + path
                    + ". " + fallback;
        }
        return fallback;
    }

    private boolean jqAvailable() {
        Boolean cached = jqAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (jqAvailable == null) {
                jqAvailable = jqProbe.getAsBoolean();
            }
            return jqAvailable;
        }
    }

    private static boolean probeJqOnPath() {
        Boolean override = configuredJqAvailability();
        if (override != null) {
            return override;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("jq", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static Boolean configuredJqAvailability() {
        String configured = System.getProperty(JQ_AVAILABLE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = com.cafecli.config.ConfigHome.envLegacy(JQ_AVAILABLE_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String normalized = configured.trim().toLowerCase();
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no")) {
            return false;
        }
        return null;
    }

    private static String requirePathComponent(String label, String value) {
        if (value == null || value.isBlank()
                || ".".equals(value)
                || "..".equals(value)
                || value.contains("/")
                || value.contains("\\")) {
            throw new IllegalArgumentException(label + " must be a single path component");
        }
        return value;
    }

    public record StoredResult(Path absolutePath, Path relativePath) {
        public String relativePathForToolRegistry() {
            return relativePath.toString().replace('\\', '/');
        }
    }
}
