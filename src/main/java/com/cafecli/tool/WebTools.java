package com.cafecli.tool;

import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.tool.ToolOutput.Status;
import com.cafecli.web.FetchResult;
import com.cafecli.web.HtmlExtractor;
import com.cafecli.web.NetworkPolicy;
import com.cafecli.web.SearchProvider;
import com.cafecli.web.SearchResult;
import com.cafecli.web.WebFetcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.cafecli.tool.ToolParam.createParameters;

/**
 * 联网工具：{@code web_search}（多 provider 抽象）+ {@code web_fetch}（HTTP + readability）。
 *
 * <p>当前模型是 StepFun {@code step-3.7-flash} 且 {@code step_search} MCP server 的同名工具已注册时，先经 registry 的
 * {@code executeToolOutput} 回调走 StepSearch（因此照常经过 PreToolUse Hook 与 HITL 覆写），结果不可用再回退到内置 provider。
 *
 * <p>四个 web 服务是 registry 的惰性 getter（子任务 registry 在注册之后才接过父的实例）、模型选择由 {@code setCurrentModel}
 * 随时改写、MCP 工具随时增删——所以一律经 supplier / 回调在<b>每次调用时</b>现取（规则见 {@link ToolEnv}）。
 */
public final class WebTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STEP_SEARCH_SERVER = "step_search";
    private static final String STEP_SEARCH_TOOL = McpToolDescriptor.namespaced(STEP_SEARCH_SERVER, "web_search");
    private static final String STEP_FETCH_TOOL = McpToolDescriptor.namespaced(STEP_SEARCH_SERVER, "web_fetch");
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;

    private final Supplier<SearchProvider> searchProvider;
    private final Supplier<WebFetcher> webFetcher;
    private final Supplier<HtmlExtractor> htmlExtractor;
    private final Supplier<NetworkPolicy> networkPolicy;
    private final BooleanSupplier preferStepSearch;
    private final Predicate<String> hasTool;
    private final Function<String, JsonNode> mcpInputProperties;
    private final BiFunction<String, String, ToolOutput> executeToolOutput;

    /**
     * @param mcpInputProperties 给定 MCP 工具名，返回其 {@code inputSchema} 的 {@code properties} 节点；工具未注册时返回 null。
     *                           解引用 {@code inputSchema} 留在 registry 侧，schema 为 null 时照旧抛 NPE（与拆分前一致）
     * @param executeToolOutput  registry 的 {@code executeToolOutput}（虚分派：HITL 覆写照常拦截 StepSearch 回调）
     */
    public WebTools(Supplier<SearchProvider> searchProvider, Supplier<WebFetcher> webFetcher,
             Supplier<HtmlExtractor> htmlExtractor, Supplier<NetworkPolicy> networkPolicy,
             BooleanSupplier preferStepSearch,
             Predicate<String> hasTool,
             Function<String, JsonNode> mcpInputProperties,
             BiFunction<String, String, ToolOutput> executeToolOutput) {
        this.searchProvider = searchProvider;
        this.webFetcher = webFetcher;
        this.htmlExtractor = htmlExtractor;
        this.networkPolicy = networkPolicy;
        this.preferStepSearch = preferStepSearch;
        this.hasTool = hasTool;
        this.mcpInputProperties = mcpInputProperties;
        this.executeToolOutput = executeToolOutput;
    }

    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。" +
                        "支持 SerpAPI（默认）和 SearXNG（自托管）两种 provider，由 SEARCH_PROVIDER 环境变量切换。",
                createParameters(
                        new ToolParam("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new ToolParam("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    return webSearch(a.str("query"), a.intOr("top_k", 5));
                }
        ));

        tools.add(new Tool(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。" +
                        "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。",
                createParameters(
                        new ToolParam("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new ToolParam("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
                ),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    return webFetch(a.str("url"), a.intOr("max_chars", DEFAULT_FETCH_MAX_CHARS));
                }
        ));
        return tools;
    }

    private ToolOutput webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return ToolOutput.failure(Status.ERROR, "搜索关键词不能为空");
        }
        if (preferStepSearch.getAsBoolean() && hasTool.test(STEP_SEARCH_TOOL)) {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("query", query.trim());
            putIfStepToolAccepts(STEP_SEARCH_TOOL, args, topK,
                    "top_k", "topK", "max_results", "num_results", "limit", "count");
            ToolOutput output = executeToolOutput.apply(STEP_SEARCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                // 换上 StepSearch 的标题、带上它的状态：可用结果也可能是 CANCELLED / BLOCKED（见 isUsableMcpOutput）
                return ToolOutput.text("🔍 [StepSearch] " + query.trim() + "\n\n" + output.text().trim())
                        .withStatus(output.status());
            }
        }
        SearchProvider provider = searchProvider.get();
        if (!provider.isReady()) {
            return ToolOutput.failure(Status.ERROR, "⚠️ " + provider.unavailableHint());
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return ToolOutput.text(formatSearchResults(provider.name(), query, results));
        } catch (Exception e) {
            return ToolOutput.failure(Status.ERROR, "搜索失败 (" + provider.name() + "): " + e.getMessage());
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private ToolOutput webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return ToolOutput.failure(Status.ERROR, "URL 不能为空");
        }
        NetworkPolicy policy = networkPolicy.get();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return ToolOutput.failure(Status.DENIED, "❌ 网络访问被拒绝: " + denyReason);
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return ToolOutput.failure(Status.DENIED, "❌ " + rateReason);
        }
        if (preferStepSearch.getAsBoolean() && hasTool.test(STEP_FETCH_TOOL)) {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("url", url.trim());
            putIfStepToolAccepts(STEP_FETCH_TOOL, args, maxChars,
                    "max_chars", "maxChars", "limit", "max_length", "maxLength");
            ToolOutput output = executeToolOutput.apply(STEP_FETCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return ToolOutput.text("🌐 [StepSearch] 抓取: " + url.trim() + "\n\n" + output.text().trim())
                        .withStatus(output.status());
            }
        }

        try {
            WebFetcher.RawResponse raw = webFetcher.get().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor.get().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return ToolOutput.text(formatFetchResult(result));
        } catch (Exception e) {
            return ToolOutput.failure(Status.ERROR, "抓取失败: " + e.getMessage());
        }
    }

    /** 当前模型是 StepFun step-3.7-flash 时优先走 StepSearch MCP。provider / model 由 registry 的 {@code setCurrentModel} 归一成小写。 */
    public static boolean prefersStepSearch(String provider, String model) {
        return "step".equals(provider) && model.startsWith("step-3.7-flash");
    }

    private void putIfStepToolAccepts(String toolName, ObjectNode args, int value, String... names) {
        if (value <= 0 || names == null || names.length == 0) {
            return;
        }
        JsonNode properties = mcpInputProperties.apply(toolName);
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (String name : names) {
            if (properties.has(name)) {
                args.put(name, value);
                return;
            }
        }
    }

    /**
     * StepSearch 结果可用 = 有正文且状态不是 ERROR / DENIED。CANCELLED / BLOCKED 仍算可用：原样回给模型，
     * 不在用户取消或 Hook 拦截之后再偷偷回退去走真实网络搜索。
     */
    static boolean isUsableMcpOutput(ToolOutput output) {
        if (output == null || output.text() == null || output.text().isBlank()) {
            return false;
        }
        return output.status() != ToolOutput.Status.ERROR
                && output.status() != ToolOutput.Status.DENIED;
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }
}
