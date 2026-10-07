package com.cafecli.tool;

import com.cafecli.config.ConfigHome;
import com.cafecli.lsp.LspManager;
import com.cafecli.policy.PathGuard;
import com.cafecli.policy.PolicyException;
import com.cafecli.runtime.CancellationContext;
import com.cafecli.tool.ToolOutput.Status;
import com.cafecli.tool.ToolRegistry.Param;
import com.cafecli.tool.ToolRegistry.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.cafecli.tool.ToolRegistry.createParameters;

/**
 * 文件工具组：{@code read_file} / {@code write_file} / {@code edit_file} / {@code list_dir} / {@code create_project}，
 * 全部受 {@link PathGuard} 围栏。{@code edit_file} 的纯计算内核在 {@link EditFileTool}，这里只留 I/O 外壳
 * （[[文件互斥锁]]、取消检查、5MB 上限、落盘、写入观察者、post-edit LSP）。
 *
 * <p>路径围栏、写入观察者与 LSP 管理器都经 {@link ToolEnv} 在<b>每次调用时</b>现取——子任务 registry、
 * {@code setProjectPath}、{@code setWriteFileObserver}、{@code setLspManager} 都会在注册之后换掉它们。
 * 越界访问与超限写入以 {@link PolicyException} 抛出，由 {@code ToolRegistry.doExecuteTool} 统一审计与格式化。
 */
final class FileTools {
    private static final int MAX_READ_FILE_LINES = 2_000;
    private static final long DEFAULT_MAX_UNRANGED_READ_FILE_BYTES = 256L * 1024L;
    private static final String MAX_UNRANGED_READ_FILE_BYTES_PROPERTY = "paicli.read_file.max_unranged_bytes";
    private static final String MAX_UNRANGED_READ_FILE_BYTES_ENV = "PAICLI_READ_FILE_MAX_UNRANGED_BYTES";
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    private static final String DISABLE_EDIT_FILE_PROPERTY = "paicli.edit_file.disabled";
    private static final Set<String> SUPPORTED_PROJECT_TYPES = Set.of("java", "python", "node");

    private final ToolEnv env;

    FileTools(ToolEnv env) {
        this.env = env;
    }

    List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        // read_file 工具
        tools.add(new Tool(
                "read_file",
                "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("offset", "integer", "起始行号，1 表示第一行；省略时读取全文", false),
                        new Param("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行", false)
                ),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    Path safe = env.pathGuard().get().resolveSafe(a.str("path"));
                    try {
                        return readFileForTool(safe, a);
                    } catch (Exception e) {
                        return ToolOutput.failure(Status.ERROR, "读取文件失败: " + e.getMessage());
                    }
                }
        ));

        // write_file 工具
        tools.add(new Tool(
                "write_file",
                "写入完整文件内容（仅限项目根目录之内，单文件 5MB 上限）；对已有文件的局部修改请优先使用 edit_file",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    String path = a.str("path");
                    String content = a.str("content") == null ? "" : a.str("content");
                    Path safe = env.pathGuard().get().resolveWritable(path);
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > MAX_WRITE_FILE_BYTES) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                    }
                    try {
                        MutationResult result = FileMutationLocks.withLock(safe, () -> {
                            if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                                return MutationResult.failed(Status.CANCELLED, "用户取消了此次工具调用");
                            }
                            String before = readExistingContentForDiff(safe);
                            return persistFileContent(
                                    path, safe, before, content, content, "文件已写入: " + path, "写入文件失败");
                        });
                        if (result.changed()) {
                            runPostEditLspHook(path, safe);
                        }
                        return result.toOutput();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ToolOutput.failure(Status.CANCELLED, "用户取消了此次工具调用");
                    } catch (Exception e) {
                        return ToolOutput.failure(Status.ERROR, "写入文件失败: " + e.getMessage());
                    }
                }
        ));

        if (!editFileDisabled()) {
            tools.add(new Tool(
                    "edit_file",
                    EditFileTool.DESCRIPTION,
                    EditFileTool.schema(),
                    this::editFile
            ));
        }

        // list_dir 工具
        tools.add(new Tool(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    Path safe = env.pathGuard().get().resolveSafe(a.str("path"));
                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            // 文本自称「为空或不存在」、不分辨是哪一种，也不声明失败：保持 OK
                            return ToolOutput.text("目录为空或不存在");
                        }
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        for (File f : files) {
                            sb.append(f.isDirectory() ? "[D] " : "[F] ")
                              .append(f.getName())
                              .append("\n");
                        }
                        return ToolOutput.text(sb.toString());
                    } catch (Exception e) {
                        return ToolOutput.failure(Status.ERROR, "列出目录失败: " + e.getMessage());
                    }
                }
        ));

        // create_project 工具
        tools.add(new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    String name = a.str("name");
                    String rawType = a.str("type") == null ? "" : a.str("type").trim();
                    String type = rawType.toLowerCase(Locale.ROOT);
                    if (!SUPPORTED_PROJECT_TYPES.contains(type)) {
                        // 先校验再落盘：不支持的类型不能留下一个空目录还报「已创建」
                        return ToolOutput.failure(Status.ERROR,
                                "创建项目失败: 不支持的项目类型 '" + rawType + "'（支持 java / python / node）");
                    }
                    Path projectRoot = env.pathGuard().get().resolveSafe(name);
                    try {
                        Files.createDirectories(projectRoot);

                        switch (type) {
                            case "java" -> {
                                Files.createDirectories(projectRoot.resolve("src/main/java"));
                                Files.createDirectories(projectRoot.resolve("src/main/resources"));
                                Files.writeString(projectRoot.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectRoot.resolve(name));
                                Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectRoot.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return ToolOutput.text("项目已创建: " + name + " (类型: " + type + ")");
                    } catch (Exception e) {
                        return ToolOutput.failure(Status.ERROR, "创建项目失败: " + e.getMessage());
                    }
                }
        ));
        return tools;
    }

    private ToolOutput readFileForTool(Path file, ToolArgs args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return ToolOutput.failure(Status.ERROR, "读取文件失败: 不是普通文件");
        }
        boolean ranged = args.has("offset") || args.has("limit");
        if (!ranged) {
            long size = Files.size(file);
            long maxBytes = maxUnrangedReadFileBytes();
            if (size > maxBytes) {
                throw new IOException("文件过大（" + size + " bytes，超过 " + maxBytes
                        + " bytes 裸读上限）；请改用 offset/limit 分页读取，或用 grep_code/jq 精确检索后再读取，勿整文件读取");
            }
            return ToolOutput.text("文件内容:\n" + Files.readString(file));
        }

        int offset = Math.max(1, args.intOr("offset", 1));
        int limit = Math.max(1, Math.min(args.intOr("limit", 200), MAX_READ_FILE_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            // offset 越界是读到了文件、只是没有可回的行：按 OK 处理（与「未找到匹配」同类）
            return ToolOutput.text("文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围");
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return ToolOutput.text(sb.toString().trim());
    }

    // ---- edit_file I/O 外壳（纯计算内核见 EditFileTool）----

    private ToolOutput editFile(JsonNode args) {
        EditFileTool.Parsed parsed = EditFileTool.parse(args);
        if (parsed.error() != null) {
            return ToolOutput.failure(Status.ERROR, parsed.error());
        }
        EditFileTool.Request request = parsed.request();
        String pathStr = request.path();

        // C. Path resolution（safe-edit 模式下拒写 src/test/**）
        Path safe = env.pathGuard().get().resolveWritable(pathStr);

        // D. Mutex lock
        try {
            MutationResult result = FileMutationLocks.withLock(safe, () -> {
                if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                    return MutationResult.failed(Status.CANCELLED, "用户取消了此次工具调用");
                }
                if (!Files.isRegularFile(safe)) {
                    return MutationResult.failed(Status.ERROR, "文件不存在，请用 write_file 新建");
                }

                String rawContent = Files.readString(safe, StandardCharsets.UTF_8);
                EditFileTool.Outcome outcome = EditFileTool.apply(rawContent, request);
                if (outcome.error() != null) {
                    return MutationResult.failed(Status.ERROR, outcome.error());
                }

                // H. Size check + write
                int afterBytes = outcome.diskContent().getBytes(StandardCharsets.UTF_8).length;
                if (afterBytes > MAX_WRITE_FILE_BYTES) {
                    throw new PolicyException("编辑后内容 " + afterBytes + " 字节超过 "
                            + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                }
                return persistFileContent(pathStr, safe, outcome.before(), outcome.after(), outcome.diskContent(),
                        outcome.summary(), "编辑文件失败");
            });
            if (result.changed()) {
                runPostEditLspHook(pathStr, safe);
            }
            return result.toOutput();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutput.failure(Status.CANCELLED, "用户取消了此次工具调用");
        } catch (PolicyException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutput.failure(Status.ERROR, "编辑文件失败: " + e.getMessage());
        }
    }

    private String readExistingContentForDiff(Path safe) {
        try {
            if (Files.exists(safe) && Files.isRegularFile(safe)) {
                return Files.readString(safe, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
        }
        return null;
    }

    /**
     * 落盘并通知写入观察者。{@code after} 是给 diff 观察者看的 LF 空间正文，{@code diskContent} 是真正写盘的字节
     * （edit_file 会还原 BOM 与原换行风格；write_file 两者相同）。
     */
    private MutationResult persistFileContent(String displayPath, Path safe,
                                               String before, String after, String diskContent,
                                               String successMessage, String failurePrefix) {
        try {
            Path parent = safe.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(safe, diskContent, StandardCharsets.UTF_8);
            try {
                env.writeFileObserver().get().accept(displayPath, new String[]{before, after});
            } catch (Exception ignored) {
                // observer 失败不能影响文件写入主路径
            }
            return MutationResult.changed(successMessage);
        } catch (Exception e) {
            return MutationResult.failed(Status.ERROR, failurePrefix + ": " + e.getMessage());
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long maxUnrangedReadFileBytes() {
        long configured = parseLong(System.getProperty(MAX_UNRANGED_READ_FILE_BYTES_PROPERTY),
                parseLong(ConfigHome.envLegacy(MAX_UNRANGED_READ_FILE_BYTES_ENV), DEFAULT_MAX_UNRANGED_READ_FILE_BYTES));
        return Math.max(1L, configured);
    }

    private static boolean editFileDisabled() {
        return ToolArgs.parseBoolean(System.getProperty(DISABLE_EDIT_FILE_PROPERTY), false);
    }

    private void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            LspManager lspManager = env.lspManager().get();
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
        }
    }

    /**
     * 锁内一次变更尝试的结果。只有真正落盘才是 OK（{@link #changed()}，随后跑 post-edit LSP）；
     * 取消 / 文件不存在 / 编辑内核报错 / 写盘失败都带非 OK 状态，文本照旧。
     */
    private record MutationResult(String message, Status status) {
        static MutationResult changed(String message) {
            return new MutationResult(message, Status.OK);
        }

        static MutationResult failed(Status status, String message) {
            return new MutationResult(message, status);
        }

        boolean changed() {
            return status == Status.OK;
        }

        ToolOutput toOutput() {
            return ToolOutput.text(message).withStatus(status);
        }
    }
}
