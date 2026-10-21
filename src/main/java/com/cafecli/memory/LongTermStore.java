package com.cafecli.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Long-term operations without the retired short-term manager or a loop dependency. Opens on first use. */
public final class LongTermStore {
    private final Path directory;
    private final String project;
    private LongTermMemory memory;

    public LongTermStore(Path directory, String project) {
        this.directory = directory;
        this.project = project;
    }

    private synchronized LongTermMemory memory() {
        if (memory == null) memory = new LongTermMemory(directory.toFile());
        return memory;
    }

    public synchronized void save(String fact, String scope) {
        boolean global = "global".equalsIgnoreCase(scope);
        memory().store(new MemoryEntry("fact-" + UUID.randomUUID().toString().substring(0, 8), fact,
                MemoryEntry.MemoryType.FACT, global ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", project), MemoryEntry.estimateTokens(fact)));
    }
    public synchronized List<MemoryEntry> list() { return memory().getAll(); }
    public synchronized List<MemoryEntry> search(String query) { return memory().search(query, 20, project); }
    public synchronized boolean delete(String id) { return memory().delete(id); }
    public synchronized void clear() { memory().clear(); }
    public synchronized String status() { return memory().getStatusSummary(); }

    public synchronized String recall(String query, int maxTokens) {
        // This leaf path is long-term-only; no short-term store is created or read.
        return new MemoryRetriever(null, memory()).buildContextForQuery(query, maxTokens, project);
    }
}
