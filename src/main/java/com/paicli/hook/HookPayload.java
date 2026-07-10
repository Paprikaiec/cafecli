package com.paicli.hook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次生命周期点分发时交给租户的只读上下文。
 *
 * 设计约束（ADR 0012）：**JSON-serializable**——shell 租户（后续 issue）会把它序列化到进程 stdin，
 * 所以这里的每个字段都要能干净地序列化。内建租户直接读字段。
 *
 * 字段随事件不同而部分为空：
 * <ul>
 *   <li>{@code toolName} / {@code toolInput} —— PreToolUse / PostToolUse 才有。</li>
 *   <li>{@code toolOutput} —— PostToolUse 才有。</li>
 *   <li>{@code turnToolSummary} —— Stop：本回合按顺序执行过的工具（名 + 目标 + 是否成功）。</li>
 *   <li>{@code userPrompt} —— UserPromptSubmit 才有。</li>
 *   <li>{@code blockCount} —— Stop：当前回合已被 Hook 阻断的次数（收尾门的反循环旗标）。</li>
 * </ul>
 * {@code taskId} 为 null 表示主 Agent；子任务（后续 issue）填自己的 task id。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookPayload(
        HookEvent event,
        String sessionId,
        String taskId,
        String projectRoot,
        String toolName,
        JsonNode toolInput,
        String toolOutput,
        List<TurnTool> turnToolSummary,
        String userPrompt,
        int blockCount) {

    /**
     * 本回合一次工具执行的摘要：工具名 + 目标（写类工具=路径、execute_command=命令）+ 是否成功。
     * 收尾门据此判断“有成功写入”与“写后是否有验证命令成功执行”。
     */
    public record TurnTool(String name, String target, boolean success) {}

    /** 构造 Stop 事件 payload。 */
    public static HookPayload forStop(String sessionId, String taskId, String projectRoot,
                                      List<TurnTool> turnToolSummary, int blockCount) {
        return new HookPayload(HookEvent.STOP, sessionId, taskId, projectRoot,
                null, null, null,
                turnToolSummary == null ? List.of() : List.copyOf(turnToolSummary),
                null, blockCount);
    }

    /** 构造 PreToolUse 事件 payload（工具执行前、审批之前）。 */
    public static HookPayload forPreToolUse(String sessionId, String taskId, String projectRoot,
                                            String toolName, JsonNode toolInput) {
        return new HookPayload(HookEvent.PRE_TOOL_USE, sessionId, taskId, projectRoot,
                toolName, toolInput, null, null, null, 0);
    }

    /** 构造 PostToolUse 事件 payload（工具成功执行后）。 */
    public static HookPayload forPostToolUse(String sessionId, String taskId, String projectRoot,
                                             String toolName, JsonNode toolInput, String toolOutput) {
        return new HookPayload(HookEvent.POST_TOOL_USE, sessionId, taskId, projectRoot,
                toolName, toolInput, toolOutput, null, null, 0);
    }
}
