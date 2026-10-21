package com.cafecli.harness.memory;

import com.cafecli.core.log.LogEntry;
import java.util.List;

/**
 * Reserved extraction seam. No implementation is installed and no automatic extraction runs.
 * Future opt-in policy can implement these three triggers without changing the long-term store.
 */
public interface MemoryExtraction {
    enum Trigger { RUN_END, BEFORE_COMPACTION, SESSION_END }
    void extract(Trigger trigger, List<LogEntry> log);
}
