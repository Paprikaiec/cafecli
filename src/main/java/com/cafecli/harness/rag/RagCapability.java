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
    private volatile Path currentRoot;
    private final Path storageDirectory;
    private final java.util.function.Consumer<String> progress;
    public RagCapability(Path root,RagConfig config) { this(root,config,null); }
    public RagCapability(Path root,RagConfig config,java.util.function.Consumer<String> progress) {
        this(root,new EmbeddingClient(config.provider(),config.model(),config.baseUrl(),config.apiKey()),root.resolve(config.directory()),progress);
    }
    public RagCapability(Path root,EmbeddingClient embeddings) { this(root,embeddings,root.resolve(".cafecli/rag")); }
    public RagCapability(Path root,EmbeddingClient embeddings,Path storageDirectory) { this(root,embeddings,storageDirectory,null); }
    public RagCapability(Path root,EmbeddingClient embeddings,Path storageDirectory,java.util.function.Consumer<String> progress) {
        this.storageDirectory=storageDirectory;
        this.progress=progress;
        this.root = root;
        this.currentRoot = root;
        this.embeddings = embeddings;
    }
    @Override public void attachSession(com.cafecli.harness.SessionRegistrar session) {
        session.onProjectChange(project -> currentRoot=project);
        for (String name : java.util.List.of("/index","/search","/graph"))
            session.command(new com.cafecli.harness.SessionCommand(name,"代码索引与检索",(context,args) -> {
                var bytes=new java.io.ByteArrayOutputStream();
                var out=new java.io.PrintStream(bytes,false,java.nio.charset.StandardCharsets.UTF_8);
                switch (name) {
                    case "/index" -> {
                        Path target=args.isEmpty() ? root : root.resolve(args).normalize();
                        if (progress==null) com.cafecli.rag.CodeCommands.index(target.toString(),embeddings,out,storageDirectory);
                        else { new com.cafecli.rag.CodeIndex(embeddings,progress::accept,storageDirectory).index(target.toString()); out.println(); }
                        context.changeProject(target);
                    }
                    case "/search" -> com.cafecli.rag.CodeCommands.search(args,root,embeddings,out,storageDirectory);
                    case "/graph" -> com.cafecli.rag.CodeCommands.graph(args,root,embeddings,out,storageDirectory);
                    default -> throw new IllegalStateException(name);
                }
                return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            }));
    }
    @Override public void attachAgent(AgentScope agent) {
        Path captured=currentRoot;
        java.util.function.Supplier<Path> project=com.cafecli.harness.AgentSession.MAIN_AGENT_ID.equals(agent.agentId()) ? () -> currentRoot : () -> captured;
        agent.tool(new LeafToolAdapter(SearchTools.semanticSearch(project,embeddings,storageDirectory)));
    }
}
