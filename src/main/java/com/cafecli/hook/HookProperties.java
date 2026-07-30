package com.cafecli.hook;

/**
 * Hook 子系统的系统属性读取（沿 safe-edit 先例：默认关，{@code -D} 开）。
 *
 * 属性清单：
 * <ul>
 *   <li>{@code cafecli.hook.stop.enabled} —— 是否在 Agent 收尾点分发 Stop hook（总开关，默认关）。</li>
 *   <li>{@code cafecli.hook.pre_tool_use.enabled} —— 是否在工具执行前分发 PreToolUse hook（总开关，默认关）。</li>
 *   <li>{@code cafecli.hook.post_tool_use.enabled} —— 是否在工具执行后分发 PostToolUse hook（总开关，默认关）。</li>
 *   <li>{@code cafecli.hook.finish_gate.enabled} —— 是否注册内建收尾门租户（默认关）。</li>
 *   <li>{@code cafecli.hook.finish_gate.verify_regex} —— 收尾门验证命令正则（可配置）。</li>
 *   <li>{@code cafecli.hook.finish_gate.block_cap} —— 收尾门单回合最多阻断次数（默认 2）。</li>
 *   <li>{@code cafecli.hook.read_ledger.enabled} —— 是否注册内建读前写台账租户（默认关）。</li>
 * </ul>
 */
public final class HookProperties {

    public static final String STOP_ENABLED = "cafecli.hook.stop.enabled";
    public static final String PRE_TOOL_USE_ENABLED = "cafecli.hook.pre_tool_use.enabled";
    public static final String POST_TOOL_USE_ENABLED = "cafecli.hook.post_tool_use.enabled";
    public static final String USER_PROMPT_SUBMIT_ENABLED = "cafecli.hook.user_prompt_submit.enabled";
    public static final String FINISH_GATE_ENABLED = "cafecli.hook.finish_gate.enabled";
    public static final String FINISH_GATE_VERIFY_REGEX = "cafecli.hook.finish_gate.verify_regex";
    public static final String FINISH_GATE_BLOCK_CAP = "cafecli.hook.finish_gate.block_cap";
    public static final String READ_LEDGER_ENABLED = "cafecli.hook.read_ledger.enabled";

    private HookProperties() {
    }

    /** 布尔开关解析，与 {@code PathGuard.safeEditEnabled()} 语义一致：true / 1 / yes 视为开。 */
    public static boolean flag(String key) {
        String value = read(key);
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public static String string(String key, String defaultValue) {
        String value = read(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public static int intValue(String key, int defaultValue) {
        String value = read(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String read(String key) {
        String value = System.getProperty(key);
        if (value == null && key != null && key.startsWith("cafecli.")) {
            value = System.getProperty("paicli." + key.substring("cafecli.".length()));
        }
        return value;
    }
}
