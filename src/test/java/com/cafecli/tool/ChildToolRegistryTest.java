package com.cafecli.tool;

import com.cafecli.lsp.LspManager;
import com.cafecli.skill.SkillContextBuffer;
import com.cafecli.snapshot.SnapshotService;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void childSharesResultSessionButCanUseTaskScope() {
        ToolRegistry parent = new ToolRegistry();

        ToolRegistry child = parent.createChildRegistry();
        child.setResultScope("task_1");

        assertEquals(parent.getSessionId(), child.getSessionId(),
                "子任务结果文件应与父任务共享同一个 session 目录");
        assertEquals("main", parent.getResultScope(),
                "父任务默认保持 main scope");
        assertEquals("task_1", child.getResultScope(),
                "子任务可切到自己的 task_N scope");
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

    /**
     * 继承是 fork 那一刻的快照，不是活的共享：父在 fork 之后再调 setter，已派生的子任务看不到。
     */
    @Test
    void parentSettersAfterForkDoNotReachAnExistingChild() {
        ToolRegistry parent = new ToolRegistry();
        List<String> forkTimeSaver = new CopyOnWriteArrayList<>();
        parent.setScopedMemorySaver((fact, scope) -> forkTimeSaver.add(fact));

        ToolRegistry child = parent.createChildRegistry();
        List<String> laterSaver = new CopyOnWriteArrayList<>();
        parent.setScopedMemorySaver((fact, scope) -> laterSaver.add(fact));
        parent.setResultScope("set_after_fork");

        child.executeTool("save_memory", "{\"fact\":\"from child\"}");

        assertEquals(List.of("from child"), forkTimeSaver, "子任务应仍用 fork 那一刻父持有的 memorySaver");
        assertEquals(List.of(), laterSaver, "父 fork 之后换上的 memorySaver 不应传到已派生的子任务");
        assertEquals("main", child.getResultScope(), "父 fork 之后改的 resultScope 不应传到已派生的子任务");
    }

    /**
     * 父 fork 时还没懒建的 web 服务（null）原样传下去：子任务各自懒建自己那份，fork 不替父建、也不跟父共用。
     */
    @Test
    void webServicesTheParentNeverBuiltAreBuiltByEachChildForItself() throws Exception {
        ToolRegistry parent = new ToolRegistry();
        ToolRegistry child = parent.createChildRegistry();

        for (String getter : WEB_SERVICE_GETTERS) {
            Object childOwn = lazyWebService(child, getter);
            assertNotNull(childOwn, getter);
            assertSame(childOwn, lazyWebService(child, getter), getter + "：子任务懒建一次后应复用");
            assertNotSame(childOwn, lazyWebService(parent, getter),
                    getter + "：父 fork 时还没建，子任务应自己懒建，不跟父共用");
        }
    }

    /** 对照组：父 fork 前已经建好的 web 服务按引用共享。 */
    @Test
    void webServicesTheParentAlreadyBuiltAreSharedByReference() throws Exception {
        ToolRegistry parent = new ToolRegistry();
        List<Object> parentOwn = new ArrayList<>();
        for (String getter : WEB_SERVICE_GETTERS) {
            parentOwn.add(lazyWebService(parent, getter));
        }

        ToolRegistry child = parent.createChildRegistry();

        for (int i = 0; i < WEB_SERVICE_GETTERS.size(); i++) {
            assertSame(parentOwn.get(i), lazyWebService(child, WEB_SERVICE_GETTERS.get(i)),
                    WEB_SERVICE_GETTERS.get(i) + "：父已建好的应按引用共享");
        }
    }

    private static final List<String> WEB_SERVICE_GETTERS =
            List.of("searchProvider", "webFetcher", "htmlExtractor", "networkPolicy");

    /**
     * 调 registry 的私有懒建 getter（{@code searchProvider()} 等）：只构造对象、不发网络请求。
     * 没有公开入口能在不联网的前提下看到 web 服务的身份，所以走反射。
     */
    private static Object lazyWebService(ToolRegistry registry, String getter) throws Exception {
        Method method = ToolRegistry.class.getDeclaredMethod(getter);
        method.setAccessible(true);
        return method.invoke(registry);
    }
}
