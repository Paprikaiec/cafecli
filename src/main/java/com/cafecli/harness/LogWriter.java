package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import java.util.List;

/** Appends custom entries at a defined moment; the source log is an immutable snapshot. */
@FunctionalInterface
public interface LogWriter {
    List<CustomEntry> write(List<LogEntry> log, CancellationToken cancellation);
}
