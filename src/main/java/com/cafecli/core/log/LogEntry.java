package com.cafecli.core.log;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One entry of an agent log (CONTEXT.md 「Agent 日志」): the append-only, typed record of an agent's conversation. An
 * entry's position in the log is its identity.
 *
 * <p>Core knows exactly three kinds — {@link UserEntry}, {@link AssistantEntry}, {@link ToolResultEntry}, each
 * wrapping the {@code ai} message it records — and switches over them exhaustively. Every other kind is a
 * {@link CustomEntry} defined by the harness, which core carries but never interprets.
 *
 * <p>The JSON kind ids ({@code "kind": "user" | "assistant" | "toolResult"}, plus each custom kind's registered id)
 * are part of the persisted log format: renaming one breaks every log written before the rename. See
 * {@link AgentLogJson}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserEntry.class, name = "user"),
        @JsonSubTypes.Type(value = AssistantEntry.class, name = "assistant"),
        @JsonSubTypes.Type(value = ToolResultEntry.class, name = "toolResult")
})
public sealed interface LogEntry permits UserEntry, AssistantEntry, ToolResultEntry, CustomEntry {
}
