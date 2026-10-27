package com.cafecli.tool;

import com.cafecli.tool.ToolOutput.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.cafecli.tool.ToolParam.createParameters;

/**
 * 任务派生三件套：{@code spawn_agent} / {@code send_message} / {@code task_output}。
 * 主 Agent 借 {@code spawn_agent} 前台（或后台）派生一个自治子 Agent（任务），前台子任务在本轮内同步跑完，
 * 只把最终结果文本（result-only）作为工具结果返回。
 *
 * <p>遵循 ADR 0003：入参走 {@code JsonNode} 通道，直接从结构化参数读取；工具体只依赖注入的 {@link TaskSpawner}，
 * 未注入则返回明确的不可用说明。{@link TaskSpawner} 由能力装配传入，
 * 所以经 supplier 在<b>每次调用时</b>现取（规则见 {@link ToolEnv}）。
 */
public final class TaskTools {
    private final Supplier<? extends TaskSpawner> agentSpawner;

    public TaskTools(Supplier<? extends TaskSpawner> agentSpawner) {
        this.agentSpawner = agentSpawner;
    }

    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool(
                "spawn_agent",
                "派生一个自治子 Agent（任务）来独立完成一段子工作（如调研、定位、收集事实），"
                        + "它有自己独立的对话历史与预算，在本轮内同步跑完，只把最终结论返回给你。"
                        + "适合把可隔离的子任务交给它，避免中间过程污染你当前的上下文。",
                createParameters(
                        new ToolParam("description", "string", "任务简述，用于登记与状态展示（如\"调研 Loom 现状\"）", true),
                        new ToolParam("prompt", "string", "交给子 Agent 的完整初始指令", true),
                        new ToolParam("background", "boolean",
                                "true=后台派生：立即返回 task_id、本轮继续干别的，任务完成后结果会自动推送回来；"
                                        + "省略/false=前台：本轮阻塞等它的结论（需要结果才能继续时用前台）", false)
                ),
                args -> {
                    String description = args.path("description").asText("");
                    String prompt = args.path("prompt").asText("");
                    boolean background = args.path("background").asBoolean(false);
                    if (prompt.isBlank()) {
                        return ToolOutput.failure(Status.ERROR, "spawn_agent 失败: prompt 不能为空");
                    }
                    TaskSpawner spawner = agentSpawner.get();
                    if (spawner == null) {
                        return ToolOutput.failure(Status.ERROR, "spawn_agent 暂不可用：当前未注入 AgentSpawner（派生子 Agent 的能力未启用）。");
                    }
                    return ToolOutput.text(background
                            ? spawner.spawnBackground(description, prompt)
                            : spawner.spawn(description, prompt));
                }
        ));

        tools.add(new Tool(
                "send_message",
                "向一个已完成（空闲-可续派）的子任务续派后续指令：在它保留的上下文与热缓存上继续干，"
                        + "而不是重开一个从零开始的任务。to 传该任务的 task_id，message 传你综合后的新指令；"
                        + "task_id 已过期/不存在时会返回明确说明，让你改用 spawn_agent 重开。",
                createParameters(
                        new ToolParam("to", "string", "目标任务的 task_id（来自之前 spawn_agent 的返回）", true),
                        new ToolParam("message", "string", "续派给该任务的新指令", true)
                ),
                args -> {
                    String to = args.path("to").asText("");
                    String message = args.path("message").asText("");
                    if (to.isBlank()) {
                        return ToolOutput.failure(Status.ERROR, "send_message 失败: to(task_id) 不能为空");
                    }
                    if (message.isBlank()) {
                        return ToolOutput.failure(Status.ERROR, "send_message 失败: message 不能为空");
                    }
                    TaskSpawner spawner = agentSpawner.get();
                    if (spawner == null) {
                        return ToolOutput.failure(Status.ERROR, "send_message 暂不可用：当前未注入 AgentSpawner。");
                    }
                    return ToolOutput.text(spawner.continueTask(to, message));
                }
        ));

        tools.add(new Tool(
                "task_output",
                "按需拉取某个子任务的完整轨迹（含它的中间工具调用与推理）：默认任务只回灌最终结果，"
                        + "当结果不够、需要看它具体做了什么时才用本工具。task_id 已过期/不存在时返回明确说明。",
                createParameters(
                        new ToolParam("task_id", "string", "目标任务的 task_id", true)
                ),
                args -> {
                    String taskId = args.path("task_id").asText("");
                    if (taskId.isBlank()) {
                        return ToolOutput.failure(Status.ERROR, "task_output 失败: task_id 不能为空");
                    }
                    TaskSpawner spawner = agentSpawner.get();
                    if (spawner == null) {
                        return ToolOutput.failure(Status.ERROR, "task_output 暂不可用：当前未注入 AgentSpawner。");
                    }
                    return ToolOutput.text(spawner.taskOutput(taskId));
                }
        ));
        return tools;
    }
}
