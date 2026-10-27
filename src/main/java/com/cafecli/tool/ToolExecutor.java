package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 内置工具体：结构化参数 → 结果。业务级失败返回带非 OK 状态的 {@link ToolOutput}（文本照旧），
 * 策略拒绝仍以 {@link com.cafecli.policy.PolicyException} 抛出、由执行适配器 统一转成 DENIED。
 */
public interface ToolExecutor {
    ToolOutput execute(JsonNode args);
}
