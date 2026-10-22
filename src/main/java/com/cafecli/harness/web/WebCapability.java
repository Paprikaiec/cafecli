package com.cafecli.harness.web;

import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.WebTools;
import com.cafecli.web.*;
import java.util.function.Consumer;

/** Web leaf adapters and run-start freshness context. */
public final class WebCapability implements Capability {
    private final SearchProvider search;
    private final WebFetcher fetcher;
    private final HtmlExtractor extractor;
    private final NetworkPolicy policy;
    private final Consumer<String> status;
    public WebCapability(SearchProvider search, WebFetcher fetcher, HtmlExtractor extractor,
                         NetworkPolicy policy, Consumer<String> status) {
        this.search = search; this.fetcher = fetcher; this.extractor = extractor;
        this.policy = policy; this.status = status;
    }
    @Override public void attachAgent(AgentScope agent) {
        leaves(agent.model(), null, null).forEach(leaf -> agent.tool(new com.cafecli.core.AgentTool() {
            public String name() { return leaf.name(); }
            public String description() { return leaf.description(); }
            public com.fasterxml.jackson.databind.JsonNode parameters() { return leaf.parameters().deepCopy(); }
            public com.cafecli.core.ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input,
                    com.cafecli.ai.CancellationToken token, Consumer<com.cafecli.core.ToolResult> progress) {
                throw new IllegalStateException("tool invocation context required");
            }
            @Override public com.cafecli.core.ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input,
                    com.cafecli.ai.CancellationToken token, Consumer<com.cafecli.core.ToolResult> progress,
                    com.cafecli.core.ToolInvoker invoker) {
                var bound = leaves(agent.model(), invoker, token).stream().filter(tool -> tool.name().equals(name())).findFirst().orElseThrow();
                return new LeafToolAdapter(bound).execute(id, input, token, progress);
            }
        }));
        agent.messageKind(Freshness.KIND);
        agent.runStartWriter((input, token, invoker) -> {
            String text = input.content().stream().filter(com.cafecli.ai.message.TextBlock.class::isInstance)
                    .map(com.cafecli.ai.message.TextBlock.class::cast).map(com.cafecli.ai.message.TextBlock::text)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!FreshnessSearch.shouldRun(text)) return java.util.List.of();
            String query = FreshnessSearch.query(text);
            if (query.isBlank()) return java.util.List.of();
            status.accept("  → 检测到时效性问题，已先联网搜索 \"" + query + "\"");
            try {
                var args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("query", query).put("top_k", 5);
                var result = invoker.invoke("web_search", args);
                return java.util.List.of(new Freshness(FreshnessSearch.success(query, text(result))));
            } catch (Exception e) {
                return java.util.List.of(new Freshness(FreshnessSearch.failure(e.getMessage())));
            }
        });
    }
    private java.util.List<com.cafecli.tool.Tool> leaves(com.cafecli.ai.Model model, com.cafecli.core.ToolInvoker invoker,
                                                       com.cafecli.ai.CancellationToken token) {
        return new WebTools(() -> search, () -> fetcher, () -> extractor, () -> policy,
                () -> WebTools.prefersStepSearch(model.provider().toLowerCase(java.util.Locale.ROOT),
                        model.id().toLowerCase(java.util.Locale.ROOT)),
                name -> invoker != null && invoker.find(name).isPresent(),
                name -> invoker.find(name).orElseThrow().parameters().get("properties"),
                (name, arguments) -> {
                    try {
                        var result = invoker.invoke(name, new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments));
                        // Abandoned rendezvous return a typed cancellation without product text.
                        // Preserve the terminal decision before the leaf checks whether content is usable.
                        if (token.isCancelled() || result.status() == com.cafecli.core.ToolResult.Status.CANCELLED) {
                            String output = text(result);
                            return com.cafecli.tool.ToolOutput.text(output.isBlank()
                                    ? "工具执行失败: 用户取消了此次工具调用" : output)
                                    .withStatus(com.cafecli.tool.ToolOutput.Status.CANCELLED);
                        }
                        return com.cafecli.tool.ToolOutput.text(text(result))
                                .withStatus(com.cafecli.tool.ToolOutput.Status.valueOf(result.status().name()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
                        throw new IllegalArgumentException(impossible);
                    }
                }).tools();
    }
    private static String text(com.cafecli.core.ToolResult result) {
        return result.content().stream().filter(com.cafecli.ai.message.TextBlock.class::isInstance)
                .map(com.cafecli.ai.message.TextBlock.class::cast).map(com.cafecli.ai.message.TextBlock::text)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

}
