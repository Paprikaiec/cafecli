package com.cafecli.harness.rag;

import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.config.RagConfig;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.rag.EmbeddingClient;
import com.cafecli.tool.SearchTools;
import java.nio.file.Path;

/** Semantic search contributes only its existing leaf; lexical search belongs to workspace. */
public final class RagCapability implements Capability {
    private final Path root;
    private final EmbeddingClient embeddings;
    public RagCapability(Path root, RagConfig config) {
        this(root, new EmbeddingClient(config.provider(), config.model(), config.baseUrl(), config.apiKey()));
    }
    public RagCapability(Path root, EmbeddingClient embeddings) {
        this.root = root;
        this.embeddings = embeddings;
    }
    @Override public void attachAgent(AgentScope agent) {
        agent.tool(new LeafToolAdapter(SearchTools.semanticSearch(root, embeddings)));
    }
}
