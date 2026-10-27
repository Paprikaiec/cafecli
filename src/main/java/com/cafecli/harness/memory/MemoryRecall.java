package com.cafecli.harness.memory;

import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.ai.message.UserMessage;
import java.util.Optional;

/** The recall selected once for this run, retained in the agent log. */
public record MemoryRecall(String text) implements CustomEntry {
    public static final MessageKind<MemoryRecall> KIND = new MessageKind<>("memory-recall", MemoryRecall.class,
            entry -> Optional.of(UserMessage.text(entry.text())));
}
