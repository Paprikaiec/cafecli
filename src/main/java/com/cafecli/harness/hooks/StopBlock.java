package com.cafecli.harness.hooks;

import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;

/** A stop refusal kept once, after the assistant's completed iteration. */
public record StopBlock(String reason) implements CustomEntry {
    public static final MessageKind<StopBlock> KIND = MessageKind.tagged("stop-block", StopBlock.class,
            "[收尾门 / Finish Gate]", entry -> entry.reason() == null ? "" : entry.reason());
}
