package com.cafecli.hook;

/**
 * 生命周期 Hook 的分发点（对齐 Claude Code 公开 hook 事件模型）。
 *
 * Wave 1 只接线 {@link #STOP}（收尾门），其余三个事件的分发点在后续 issue 增量接入；
 * 枚举现在就补齐，是为了让后续 issue 只“增”不“改”（契约贵、租户便宜）。
 */
public enum HookEvent {
    PRE_TOOL_USE,
    POST_TOOL_USE,
    STOP,
    USER_PROMPT_SUBMIT
}
