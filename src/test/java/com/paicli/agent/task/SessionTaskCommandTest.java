package com.paicli.agent.task;

import com.paicli.runtime.CancellationRegistry;
import com.paicli.runtime.CancellationToken;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `/task` 重指向内存注册表（ADR 0005，#10）：列出会话内任务及状态、按 id 检视完整轨迹、取消运行中的任务。
 */
class SessionTaskCommandTest {

    @Test
    void listShowsSessionTasksWithStateAndSummary() {
        TaskRegistry registry = new TaskRegistry();
        String id1 = registry.register("调研 A");
        registry.markDone(id1, "结论 A");
        String id2 = registry.register("写 B");   // 仍 RUNNING

        String out = SessionTaskCommand.handle(registry, new CancellationRegistry(), null, "list");

        assertTrue(out.contains(id1) && out.contains("调研 A") && out.contains("结论 A"), out);
        assertTrue(out.contains(id2) && out.contains("运行中"), out);
        assertTrue(out.contains("空闲"), "DONE 任务应显示为空闲·可续派");
    }

    @Test
    void emptyListIsExplained() {
        String out = SessionTaskCommand.handle(new TaskRegistry(), new CancellationRegistry(), null, "");
        assertTrue(out.contains("没有任务"), out);
    }

    @Test
    void cancelCancelsTheTaskToken() {
        TaskRegistry registry = new TaskRegistry();
        CancellationRegistry cancellation = new CancellationRegistry();
        String id = registry.register("跑偏任务");
        CancellationToken token = cancellation.registerTask(id, false);

        String out = SessionTaskCommand.handle(registry, cancellation, null, "cancel " + id);

        assertTrue(out.contains("已请求取消"), out);
        assertTrue(token.isCancelled(), "/task cancel 应取消该任务的令牌");
    }

    @Test
    void cancelUnknownIdReportsNotFound() {
        String out = SessionTaskCommand.handle(new TaskRegistry(), new CancellationRegistry(), null, "cancel task_404");
        assertTrue(out.contains("未找到"), out);
    }

    @Test
    void inspectByIdDelegatesToTaskOutput() {
        TaskRegistry registry = new TaskRegistry();
        String id = registry.register("某任务");
        AgentSpawner spawner = new AgentSpawner() {
            @Override public String spawn(String d, String p) { return ""; }
            @Override public String spawnBackground(String d, String p) { return ""; }
            @Override public String continueTask(String t, String m) { return ""; }
            @Override public String taskOutput(String t) { return "TRAJECTORY of " + t; }
        };

        String out = SessionTaskCommand.handle(registry, new CancellationRegistry(), spawner, id);

        assertTrue(out.contains("TRAJECTORY of " + id), "/task <id> 应走 taskOutput 检视完整轨迹");
    }
}
