package com.cafecli.harness;

import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.ToolFailure;
import com.cafecli.core.ToolResult;

import java.util.List;

/** v1's model-facing failure wording, kept outside the generic scheduler. */
final class ToolFailures {
    private ToolFailures() {}

    static ToolResult result(ToolCall call, ToolFailure failure) {
        String text = switch (failure.kind()) {
            case UNKNOWN_TOOL -> "未知工具: " + call.name();
            case EXECUTION -> "工具执行失败: " + failure.detail();
            case CANCELLED -> "工具执行失败: 用户取消了此次工具调用";
            case INTERRUPTED -> "工具执行失败: 工具执行被中断";
            case BATCH_INTERRUPTED -> "工具执行失败: 工具批次执行被中断";
            case TIMEOUT -> "工具执行超时（" + failure.timeoutSeconds() + "秒），已取消";
        };
        return new ToolResult(List.of(new TextBlock(text)), null, failure.status());
    }
}
