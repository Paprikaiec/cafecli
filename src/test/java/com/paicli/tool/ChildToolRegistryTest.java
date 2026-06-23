package com.paicli.tool;

import com.paicli.lsp.LspManager;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.snapshot.SnapshotService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0007「默认隔离，共享靠显式注入」的单元测试：
 * {@link ToolRegistry#createChildRegistry()} 让每个子任务拿到自己的 ToolRegistry，
 * 昂贵的进程/连接级协作者按引用共享（不重建），会话相邻的可变状态每任务私有（不串台）。
 */
class ChildToolRegistryTest {

    /**
     * 共享靠注入、不重建：子 registry 复用父的昂贵协作者（SnapshotService、审计日志），
     * 而非各自 new 一份 → 没有 per-task 的快照重建 / 重连。
     */
    @Test
    void childSharesExpensiveServicesByReference() {
        ToolRegistry parent = new ToolRegistry();
        SnapshotService sharedSnapshot = parent.getSnapshotService();

        ToolRegistry child = parent.createChildRegistry();

        assertSame(sharedSnapshot, child.getSnapshotService(),
                "子任务应共享父的 SnapshotService（单一工作区时间线）");
        assertSame(parent.getAuditLog(), child.getAuditLog(),
                "子任务应共享父的审计日志");
        assertEquals(parent.getProjectPath(), child.getProjectPath(),
                "子任务应继承父的项目路径");
    }

    /**
     * 会话相邻状态每任务私有：Skill 缓冲与模型选择互不串台。
     * 任务 A 往自己的 Skill buffer push 内容、选一个模型，任务 B 看到的仍是空 buffer / 默认模型。
     */
    @Test
    void siblingTasksDoNotShareSessionState() {
        ToolRegistry parent = new ToolRegistry();

        ToolRegistry taskA = parent.createChildRegistry();
        ToolRegistry taskB = parent.createChildRegistry();

        // 两个子任务各持独立的 Skill buffer
        assertNotSame(taskA.getSkillContextBuffer(), taskB.getSkillContextBuffer(),
                "兄弟任务的 SkillContextBuffer 必须是各自独立实例");

        // 任务 A 把一个 skill body 注入自己的缓冲
        taskA.getSkillContextBuffer().push("web-access", "TASK_A_SKILL_BODY");

        // 任务 B 的缓冲仍为空 —— A 的 skill 状态没有泄漏到 B
        assertTrue(taskB.getSkillContextBuffer().isEmpty(),
                "任务 A 的 Skill 状态不应出现在任务 B");

        // 模型选择也每任务私有：A 切到一个特定 provider/model，B 仍是默认空
        taskA.setCurrentModel("step", "step-3.7-flash-X");
        assertTrue(taskB.shouldPreferStepSearchForTest() == false,
                "任务 A 的模型选择不应改变任务 B 的 provider 行为");
    }

    /**
     * LSP「进程共享、pending 缓冲私有」：子任务复用同一份解析进程（共享底层 parser），
     * 但各自维护独立的 pending 诊断缓冲 —— 任务 A 写进自己缓冲的诊断，不会被任务 B flush 走。
     */
    @Test
    void lspPendingBufferIsPerTaskEvenWhenProcessShared() {
        ToolRegistry parent = new ToolRegistry();

        ToolRegistry taskA = parent.createChildRegistry();
        ToolRegistry taskB = parent.createChildRegistry();

        LspManager lspA = taskA.getLspManager();
        LspManager lspB = taskB.getLspManager();

        // pending 缓冲私有 → 两个子任务持有不同的 LspManager 实例（各自一份 pendingByFile）
        assertNotSame(lspA, lspB,
                "LSP pending 缓冲应每任务私有，子任务不能共用同一个 pending buffer");

        // 但底层解析「进程」共享（不为每任务重建 parser）
        assertSame(lspA.sharedProcessKey(), lspB.sharedProcessKey(),
                "LSP 底层解析进程应在子任务间共享，不做 per-task 重开");

        // 确定性 no-bleed 探针：往 A 的缓冲塞一条诊断，B flush 时看不到
        lspA.injectPendingForTest("A.java", "TASK_A_DIAGNOSTIC");
        assertTrue(taskB.flushPendingLspDiagnostics().isEmpty(),
                "任务 A 的 pending 诊断不应被任务 B flush 走（缓冲私有）");
        assertFalse(taskA.flushPendingLspDiagnostics().isEmpty(),
                "任务 A 自己 flush 应能拿到它注入的诊断");
    }
}
