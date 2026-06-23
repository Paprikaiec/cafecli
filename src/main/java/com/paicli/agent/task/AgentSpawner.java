package com.paicli.agent.task;

/**
 * 派生子 Agent（任务）的接缝。
 *
 * <p>{@code spawn_agent} 工具体只依赖这个窄接口；真正持有 {@code LlmClient}、构建子 Agent、
 * 登记 {@link TaskRegistry} 并同步跑完子任务的逻辑放在 agent 包的实现里
 * （见 {@code com.paicli.agent.ForegroundAgentSpawner}）。这样 {@code ToolRegistry} 不必反向
 * 依赖 Agent 的构造细节，也方便测试替身。
 *
 * <p>「result-only」契约：返回值只包含子 Agent 的最终结果文本（外加简短状态），
 * 子任务的中间工具调用 / 推理噪声不会出现在这里、也不进入主 Agent 的对话历史。
 */
public interface AgentSpawner {

    /**
     * 前台同步派生一个子任务，跑完后返回 result-only 文本。
     *
     * @param description 任务简述（给登记表与状态展示）
     * @param prompt      交给子 Agent 的初始用户输入
     * @return 子 Agent 的最终结果文本（result-only）
     */
    String spawn(String description, String prompt);
}
