package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具参数的类型化读取器。语义与已删除的老适配器 {@code fromMap}<b>逐字节一致</b>——老路径把顶层 JSON 对象的每个字段
 * 按 {@code asText()} 拍平成 {@code Map<String,String>}（非对象 / null 节点拍成空表），工具再对其中的字符串做
 * {@code parseInt} / {@code clamp} / {@code parseBoolean}：
 * <ul>
 *   <li>字段取值一律走 {@code node.asText()}：JSON null 读成 {@code "null"}，对象 / 数组读成 {@code ""}；</li>
 *   <li>数字先 trim 再解析，null / 空白 / 解析失败都回退默认值；</li>
 *   <li>布尔 null / 空白回退默认值，否则 trim 后 {@code true} / {@code 1} / {@code yes}（忽略大小写）为真。</li>
 * </ul>
 * 解析逻辑只有这一份，{@code ToolArgsTest} 用一份冻结的老拍平循环钉住这份一致性。
 */
final class ToolArgs {
    private final JsonNode node;

    private ToolArgs(JsonNode node) {
        this.node = node;
    }

    static ToolArgs of(JsonNode node) {
        return new ToolArgs(node);
    }

    /** 字段是否存在（值为 JSON null 也算存在）。 */
    boolean has(String name) {
        return node != null && node.isObject() && node.has(name);
    }

    /** 字段存在时返回 {@code asText()}，否则 {@code null}。 */
    String str(String name) {
        return has(name) ? node.get(name).asText() : null;
    }

    /** 同 {@link #str(String)}，但字段缺失时返回 {@code def}（存在但为 JSON null 时仍是 {@code "null"}）。 */
    String str(String name, String def) {
        return has(name) ? node.get(name).asText() : def;
    }

    int intOr(String name, int fallback) {
        return parseInt(str(name), fallback);
    }

    int intIn(String name, int fallback, int min, int max) {
        return clamp(intOr(name, fallback), min, max);
    }

    boolean bool(String name, boolean fallback) {
        return parseBoolean(str(name), fallback);
    }

    static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }
}
