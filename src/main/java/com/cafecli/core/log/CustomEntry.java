package com.cafecli.core.log;

/**
 * An entry of a custom message kind (CONTEXT.md 「自定义消息类型」): defined by the harness — memory recall, steering, a
 * task notification, a compaction summary — and opaque to core. Typically a record; to be serialized it is registered
 * with {@link AgentLogJson} under its kind id. Whether and how the model sees it is up to the harness's
 * {@code convertToLlm}; core's default drops it.
 */
public non-sealed interface CustomEntry extends LogEntry {
}
