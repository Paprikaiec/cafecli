package com.cafecli.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Long-term operations without the retired short-term manager or a loop dependency. Opens on first use. */
public final class LongTermStore {
    private final Storage storage;
    private final String project;
    private static final class Storage {
        final Path directory;
        LongTermMemory memory;
        Storage(Path directory) { this.directory=directory; }
        LongTermMemory memory() {
            if (memory==null) memory=new LongTermMemory(directory.toFile());
            return memory;
        }
    }

    public LongTermStore(Path directory,String project) { this(new Storage(directory),project); }
    private LongTermStore(Storage storage,String project) { this.storage=storage; this.project=project; }
    public LongTermStore forProject(String project) { return new LongTermStore(storage,project); }
    public String project() { return project; }

    public void save(String fact, String scope) {
        boolean global = "global".equalsIgnoreCase(scope);
        synchronized (storage) { storage.memory().store(new MemoryEntry("fact-" + UUID.randomUUID().toString().substring(0, 8), fact,
                MemoryEntry.MemoryType.FACT, global ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", project), MemoryEntry.estimateTokens(fact))); }
    }
    public List<MemoryEntry> list() { synchronized (storage) { return storage.memory().getAll(); } }
    public List<MemoryEntry> search(String query) { synchronized (storage) { return storage.memory().search(query, 20, project); } }
    public boolean delete(String id) { synchronized (storage) { return storage.memory().delete(id); } }
    public void clear() { synchronized (storage) { storage.memory().clear(); } }
    public String status() { synchronized (storage) { return storage.memory().getStatusSummary(); } }

    public String recall(String query, int maxTokens) {
        // This leaf path is long-term-only; no short-term store is created or read.
        synchronized (storage) { return new MemoryRetriever(storage.memory()).buildContextForQuery(query, maxTokens, project); }
    }
}
