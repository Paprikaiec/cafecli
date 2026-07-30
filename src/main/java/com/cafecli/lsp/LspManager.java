package com.cafecli.lsp;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LspManager {
    private static final Logger log = LoggerFactory.getLogger(LspManager.class);

    private final JavaParser javaParser;
    private final Map<Path, List<LspDiagnostic>> pendingByFile = new LinkedHashMap<>();
    private Path projectRoot;

    public LspManager(String projectPath) {
        this.javaParser = new JavaParser();
        setProjectPath(projectPath);
    }

    private LspManager(JavaParser sharedParser, Path projectRoot) {
        this.javaParser = sharedParser;
        this.projectRoot = projectRoot;
    }

    /**
     * 「进程共享、pending 缓冲私有」（ADR 0007）：派生一个共享底层解析进程（同一个
     * {@link JavaParser}）、但持有全新独立 pending 诊断缓冲的 LspManager，给每个子任务用。
     * 这样任务 A 的 post-edit 诊断不会被任务 B 的回合 flush 走。
     */
    public LspManager forkSharingProcess() {
        return new LspManager(this.javaParser, this.projectRoot);
    }

    /**
     * 底层解析进程的同一性标识：同一进程派生出的所有 fork 返回同一对象，
     * 用于断言「LSP 进程在子任务间共享而非 per-task 重建」。
     */
    public Object sharedProcessKey() {
        return javaParser;
    }

    public synchronized void setProjectPath(String projectPath) {
        String root = projectPath == null || projectPath.isBlank()
                ? System.getProperty("user.dir")
                : projectPath;
        this.projectRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public void runPostEditLspHook(String displayPath, Path editedFile) {
        if (!enabled()) {
            return;
        }
        if (editedFile == null) {
            return;
        }
        Path file = editedFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            clearDiagnostics(file);
            return;
        }
        if (!isJavaFile(file)) {
            clearDiagnostics(file);
            return;
        }
        try {
            List<LspDiagnostic> diagnostics = diagnoseJava(displayPath, file);
            replaceDiagnostics(file, diagnostics);
            log.debug("LSP post-edit diagnostics for {}: {}", file, diagnostics.size());
        } catch (Exception e) {
            log.trace("LSP post-edit hook failed for {}", file, e);
        }
    }

    public synchronized LspDiagnosticReport flushPendingDiagnostics() {
        if (pendingByFile.isEmpty()) {
            return LspDiagnosticReport.EMPTY;
        }
        List<LspDiagnostic> diagnostics = pendingByFile.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator
                        .comparing((LspDiagnostic d) -> severityRank(d.severity()))
                        .thenComparing(LspDiagnostic::filePath)
                        .thenComparingInt(LspDiagnostic::line)
                        .thenComparingInt(LspDiagnostic::column))
                .toList();
        pendingByFile.clear();
        return LspDiagnosticFormatter.format(diagnostics);
    }

    synchronized List<LspDiagnostic> pendingDiagnosticsSnapshot() {
        return pendingByFile.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * 测试用：直接往 pending 缓冲塞一条诊断，绕过真实解析，
     * 用于确定性地验证「pending 缓冲每任务私有、不跨任务串台」。
     */
    public synchronized void injectPendingForTest(String displayPath, String message) {
        Path key = Path.of(displayPath).toAbsolutePath().normalize();
        pendingByFile.put(key, List.of(new LspDiagnostic(
                LspSeverity.ERROR, displayPath, 1, 1, message, "test")));
    }

    private List<LspDiagnostic> diagnoseJava(String displayPath, Path file) throws IOException {
        ParseResult<CompilationUnit> result = javaParser.parse(file);
        List<LspDiagnostic> diagnostics = new ArrayList<>();
        for (Problem problem : result.getProblems()) {
            diagnostics.add(toDiagnostic(displayPath, problem));
        }
        return diagnostics;
    }

    private LspDiagnostic toDiagnostic(String displayPath, Problem problem) {
        Range range = problem.getLocation()
                .flatMap(tokenRange -> tokenRange.getBegin().getRange())
                .orElse(null);
        int line = range == null ? 1 : range.begin.line;
        int column = range == null ? 1 : range.begin.column;
        return new LspDiagnostic(
                LspSeverity.ERROR,
                normalizeDisplayPath(displayPath),
                line,
                column,
                cleanMessage(problem.getMessage()),
                "javaparser"
        );
    }

    private String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Java parse error";
        }
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n').trim();
        int newline = normalized.indexOf('\n');
        if (newline >= 0) {
            normalized = normalized.substring(0, newline).trim();
        }
        return normalized.isBlank() ? "Java parse error" : normalized;
    }

    private synchronized void replaceDiagnostics(Path file, List<LspDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            pendingByFile.remove(file);
        } else {
            pendingByFile.put(file, List.copyOf(diagnostics));
        }
    }

    private synchronized void clearDiagnostics(Path file) {
        if (file != null) {
            pendingByFile.remove(file.toAbsolutePath().normalize());
        }
    }

    private boolean isJavaFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.endsWith(".java");
    }

    private boolean enabled() {
        String raw = System.getProperty("paicli.lsp.enabled");
        if (raw == null || raw.isBlank()) {
            raw = com.cafecli.config.ConfigHome.envLegacy("PAICLI_LSP_ENABLED");
        }
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw.trim());
    }

    private String normalizeDisplayPath(String displayPath) {
        if (displayPath != null && !displayPath.isBlank()) {
            return displayPath.replace('\\', '/');
        }
        return projectRoot.toString().replace('\\', '/');
    }

    private static int severityRank(LspSeverity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }
}
