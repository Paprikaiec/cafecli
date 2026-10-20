package com.cafecli.tool;

import com.cafecli.config.ConfigHome;
import com.cafecli.mcp.McpResultPersister;
import com.cafecli.policy.PathGuard;
import com.cafecli.policy.PolicyException;
import com.cafecli.rag.CodeRetriever;
import com.cafecli.rag.SearchResultFormatter;
import com.cafecli.rag.VectorStore;
import com.cafecli.tool.ToolOutput.Status;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.cafecli.tool.ToolParam.createParameters;

/**
 * 只读代码定位三件套：{@code glob_files} / {@code grep_code}（实时扫描，受 {@link PathGuard} 围栏）+
 * {@code search_code}（RAG 语义检索，读项目根下的索引）。
 *
 * <p>路径围栏与项目根都经 {@link ToolEnv} 在<b>每次调用时</b>现取——子任务 registry 与 {@code setProjectPath}
 * 会在注册之后换掉它们。越界访问以 {@link PolicyException} 抛出，由 {@code ToolRegistry.doExecuteTool} 统一处理。
 */
final class SearchTools {
    private static final int MAX_GLOB_RESULTS = 200;
    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final int DEFAULT_GREP_MAX_CHARS = 24_000;
    private static final int MAX_GREP_MAX_CHARS = 60_000;
    private static final int DEFAULT_GREP_HEAD_LIMIT = 20;
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", ".cafecli", ".paicli", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );

    private final ToolEnv env;

    SearchTools(ToolEnv env) {
        this.env = env;
    }

    List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool(
                "glob_files",
                "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java",
                createParameters(
                        new ToolParam("pattern", "string", "glob 模式，例如 **/*.java、**/*Controller*、README.md", true),
                        new ToolParam("path", "string", "搜索起始目录，默认 .", false),
                        new ToolParam("max_results", "integer", "最多返回结果数，默认 50，上限 200", false)
                ),
                args -> globFiles(ToolArgs.of(args))
        ));

        tools.add(new Tool(
                "grep_code",
                "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文",
                createParameters(
                        new ToolParam("pattern", "string", "要搜索的关键字或正则", true),
                        new ToolParam("path", "string", "搜索起始目录，默认 .", false),
                        new ToolParam("glob", "string", "可选文件 glob 过滤，例如 **/*.java", false),
                        new ToolParam("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索", false),
                        new ToolParam("case_sensitive", "boolean", "是否大小写敏感，默认 true", false),
                        new ToolParam("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5", false),
                        new ToolParam("max_results", "integer", "最多返回命中数，默认 50，上限 200", false),
                        new ToolParam("head_limit", "integer", "单个文件最多返回多少条命中，默认 20，上限 50", false),
                        new ToolParam("max_chars", "integer", "单次工具结果字符预算，默认 24000，上限 60000", false)
                ),
                args -> grepCode(ToolArgs.of(args))
        ));

        tools.add(new Tool(
                "search_code",
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块；精确符号/字符串定位请优先用 grep_code/glob_files/read_file；默认 top_k=5，可显式指定（上限 30）",
                createParameters(
                        new ToolParam("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new ToolParam("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
                ),
                args -> searchCode(ToolArgs.of(args))
        ));
        return tools;
    }

    private ToolOutput globFiles(ToolArgs args) {
        PathGuard pathGuard = env.pathGuard().get();
        String pattern = args.str("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolOutput.failure(Status.ERROR, "文件匹配失败: pattern 不能为空");
        }
        Path root = pathGuard.resolveSafe(args.str("path", "."));
        int maxResults = args.intIn("max_results", 50, 1, MAX_GLOB_RESULTS);
        Path projectRoot = pathGuard.getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                if (matches.size() >= maxResults) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    matches.add(relative.toString());
                }
            }));
        } catch (Exception e) {
            return ToolOutput.failure(Status.ERROR, "文件匹配失败: " + e.getMessage());
        }

        if (matches.isEmpty()) {
            return ToolOutput.text("未找到匹配文件: " + pattern);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return ToolOutput.text(sb.toString().trim());
    }

    private ToolOutput grepCode(ToolArgs args) {
        PathGuard pathGuard = env.pathGuard().get();
        String query = args.str("pattern");
        if (query == null || query.isBlank()) {
            return ToolOutput.failure(Status.ERROR, "代码搜索失败: pattern 不能为空");
        }
        Path root = pathGuard.resolveSafe(args.str("path", "."));
        Path projectRoot = pathGuard.getRootPath();
        int maxResults = args.intIn("max_results", 50, 1, MAX_GREP_RESULTS);
        int contextLines = args.intIn("context_lines", 0, 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = args.bool("regex", false);
        boolean caseSensitive = args.bool("case_sensitive", true);
        int headLimit = args.intIn("head_limit", DEFAULT_GREP_HEAD_LIMIT, 1, 50);
        int maxChars = args.intIn("max_chars", DEFAULT_GREP_MAX_CHARS, 1_000, MAX_GREP_MAX_CHARS);
        CodeSearchRequest request = new CodeSearchRequest(
                query,
                root,
                projectRoot,
                args.str("glob"),
                regex,
                caseSensitive,
                contextLines,
                maxResults,
                headLimit
        );
        CodeSearchResult result = new RipgrepCodeSearchEngine(searchExcludedDirsFor(root, projectRoot)).search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return ToolOutput.failure(Status.ERROR, "代码搜索失败: " + result.partialReason());
        }
        if (result.matches().isEmpty()) {
            return ToolOutput.text("未找到匹配内容: " + query);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return ToolOutput.text(sb.toString().trim());
    }

    private void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }

    private static Set<String> searchExcludedDirsFor(Path root, Path projectRoot) {
        Path relative;
        try {
            relative = projectRoot.relativize(root).normalize();
        } catch (IllegalArgumentException e) {
            return SEARCH_EXCLUDED_DIRS;
        }
        if (relative.getNameCount() >= 2
                && McpResultPersister.RESULTS_DIR_NAME.equals(relative.getName(1).toString())) {
            String top = relative.getName(0).toString();
            if (ConfigHome.dirName().equals(top)
                    || ConfigHome.legacyDirName().equals(top)) {
                Set<String> excluded = new LinkedHashSet<>(SEARCH_EXCLUDED_DIRS);
                excluded.remove(ConfigHome.dirName());
                excluded.remove(ConfigHome.legacyDirName());
                return excluded;
            }
        }
        return SEARCH_EXCLUDED_DIRS;
    }

    private ToolOutput searchCode(ToolArgs args) {
        String query = args.str("query");
        int topK = args.intIn("top_k", 5, 1, 30);

        try (CodeRetriever retriever = new CodeRetriever(env.projectPath().get())) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                return ToolOutput.failure(Status.ERROR, "代码库尚未索引，请先使用 /index 命令索引当前项目。");
            }

            List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
            if (results.isEmpty()) {
                return ToolOutput.text("未找到与查询相关的代码。");
            }

            return ToolOutput.text(SearchResultFormatter.formatForTool(query, results));
        } catch (Exception e) {
            return ToolOutput.failure(Status.ERROR, "代码检索失败: " + e.getMessage());
        }
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static final class SearchFileVisitor extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final java.util.function.Consumer<Path> fileConsumer;

        private SearchFileVisitor(Path projectRoot, java.util.function.Consumer<Path> fileConsumer) {
            this.projectRoot = projectRoot;
            this.fileConsumer = fileConsumer;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(projectRoot) && SEARCH_EXCLUDED_DIRS.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileConsumer.accept(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }
}
