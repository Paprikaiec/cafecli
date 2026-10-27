package com.cafecli.rag;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/** Existing CLI RAG command algorithms over captured roots and embeddings. */
public final class CodeCommands {
    private CodeCommands() {}
    public static void index(String indexPath,EmbeddingClient embeddings,PrintStream ui) {
        index(indexPath,embeddings,ui,null);
    }
    public static void index(String indexPath,EmbeddingClient embeddings,PrintStream ui,Path storageDirectory) {
        CodeIndex indexer = new CodeIndex(embeddings,ui::println,storageDirectory);
        indexer.index(indexPath);
        ui.println();

    }
    public static void search(String query,Path root,EmbeddingClient embeddings,PrintStream ui) {
        search(query,root,embeddings,ui,null);
    }
    public static void search(String query,Path root,EmbeddingClient embeddings,PrintStream ui,Path storageDirectory) {

        if (query == null || query.isEmpty()) {
            ui.println("❌ 请提供检索关键词，例如 /search 用户登录实现\n");
            return;
        }
        ui.println("🔍 检索: " + query);
        try (CodeRetriever retriever = new CodeRetriever(root.toString(),embeddings,storageDirectory)) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                return;
            }
            List<com.cafecli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);
            if (results.isEmpty()) {
                ui.println("📭 未找到相关代码\n");
            } else {
                ui.println(SearchResultFormatter.formatForCli(query, results) + "\n");
            }
        } catch (Exception e) {
            ui.println("❌ 检索失败: " + e.getMessage() + "\n");
        }
        return;
    }
    public static void graph(String className,Path root,EmbeddingClient embeddings,PrintStream ui) {
        graph(className,root,embeddings,ui,null);
    }
    public static void graph(String className,Path root,EmbeddingClient embeddings,PrintStream ui,Path storageDirectory) {

        if (className == null || className.isEmpty()) {
            ui.println("❌ 请提供类名，例如 /graph Main\n");
            return;
        }
        ui.println("🕸️ 查询类关系图谱: " + className);
        try (CodeRetriever retriever = new CodeRetriever(root.toString(),embeddings,storageDirectory)) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                return;
            }
            List<CodeRelation> relations = retriever.getRelationGraph(className);
            if (relations.isEmpty()) {
                ui.println("📭 未找到相关关系\n");
            } else {
                ui.println("📋 找到 " + relations.size() + " 条关系:\n");
                for (CodeRelation rel : relations) {
                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                            : rel.relationType().equals("extends") ? "└── extends -->"
                            : rel.relationType().equals("implements") ? "└── implements -->"
                            : rel.relationType().equals("calls") ? "├── calls -->"
                            : "├── " + rel.relationType() + " -->";
                    ui.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                            rel.toName() != null ? rel.toName() : "unknown");
                }
                ui.println();
            }
        } catch (Exception e) {
            ui.println("❌ 查询失败: " + e.getMessage() + "\n");
        }
        return;
    }
}
