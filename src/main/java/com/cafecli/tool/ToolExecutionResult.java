package com.cafecli.tool;

import java.util.List;

public record ToolExecutionResult(String id, String name, String argumentsJson,
                                  ToolOutput output, long elapsedMillis, boolean timedOut) {
    static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output, long elapsedMillis) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                output == null ? ToolOutput.text("") : output,
                elapsedMillis,
                false);
    }

    /** 批次层面的失败（取消 / 中断 / 执行异常）：文本统一加「工具执行失败: 」前缀，状态由调用方给出。 */
    static ToolExecutionResult failed(ToolInvocation invocation, ToolOutput.Status status, String message) {
        return completed(invocation, ToolOutput.failure(status, "工具执行失败: " + message), 0);
    }

    static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                ToolOutput.failure(ToolOutput.Status.TIMEOUT, "工具执行超时（" + timeoutSeconds + "秒），已取消"),
                timeoutSeconds * 1000,
                true
        );
    }

    public String result() {
        return output == null ? "" : output.renderTextForLlm();
    }

    public List<com.cafecli.llm.LlmClient.ContentPart> imageParts() {
        return output == null ? List.of() : output.imageParts();
    }

    public boolean hasImageParts() {
        return output != null && output.hasImageParts();
    }
}
