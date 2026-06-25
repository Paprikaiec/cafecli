package com.paicli.agent.task;

import com.paicli.runtime.CancellationRegistry;

import java.util.List;

/**
 * `/task` 命令（ADR 0005）：重指向<b>内存级</b>会话内任务注册表（取代已退役的 SQLite `DurableTaskManager`）。
 * 列出当前会话的任务及状态、检视单个任务的完整轨迹、取消单个任务。会话绑定、不跨重启。
 *
 * <p>纯函数式：给定注册表/取消注册表/派生器与 payload，返回要打印的文本；可单测。
 */
public final class SessionTaskCommand {

    private SessionTaskCommand() {
    }

    public static String handle(TaskRegistry registry, CancellationRegistry cancellation,
                                AgentSpawner spawner, String payload) {
        String p = payload == null ? "" : payload.trim();
        if (p.isEmpty() || p.equalsIgnoreCase("list")) {
            return list(registry);
        }
        if (p.equalsIgnoreCase("cancel")) {
            return "用法：/task cancel <task_id>";
        }
        if (p.regionMatches(true, 0, "cancel ", 0, 7)) {
            return cancel(registry, cancellation, p.substring(7).trim());
        }
        // 其余当作 task_id：检视其完整轨迹（含中间工具调用）。
        if (spawner == null) {
            return "任务检视不可用。";
        }
        return spawner.taskOutput(p);
    }

    private static String list(TaskRegistry registry) {
        List<Task> tasks = registry.tasks();
        if (tasks.isEmpty()) {
            return "当前会话没有任务。主 Agent 用 spawn_agent 派生子任务后会出现在这里。";
        }
        StringBuilder sb = new StringBuilder("会话内任务（" + tasks.size() + "）：\n");
        for (Task t : tasks) {
            sb.append("  ").append(t.id()).append("  [").append(stateLabel(t.state())).append("]  ")
                    .append(t.description() == null ? "" : t.description()).append('\n');
            if (t.result() != null && !t.result().isBlank()) {
                sb.append("      ").append(oneLine(t.result())).append('\n');
            }
        }
        sb.append("（/task <id> 看完整轨迹，/task cancel <id> 取消运行中的任务）");
        return sb.toString();
    }

    private static String cancel(TaskRegistry registry, CancellationRegistry cancellation, String id) {
        if (id == null || id.isBlank()) {
            return "用法：/task cancel <task_id>";
        }
        if (registry.get(id) == null) {
            return "未找到任务 " + id + "（可能已完成或被回收）。用 /task 查看当前任务。";
        }
        boolean cancelled = cancellation != null && cancellation.cancelTask(id);
        return cancelled
                ? "已请求取消任务 " + id + "（在回合边界停止）。"
                : "任务 " + id + " 当前没有可取消的运行令牌（可能已完成）。";
    }

    private static String stateLabel(Task.State state) {
        return switch (state) {
            case RUNNING -> "运行中";
            case DONE -> "空闲·可续派";
            case FAILED -> "失败";
        };
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > 100 ? t.substring(0, 100) + "…" : t;
    }
}
