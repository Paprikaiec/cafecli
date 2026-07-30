package com.cafecli.tool;

import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookDispatcher;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookProperties;
import com.cafecli.hook.HookTenant;
import com.cafecli.hook.HooksConfigLoader;
import com.cafecli.hook.ReadLedgerTenant;
import com.cafecli.policy.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PreToolUse / PostToolUse 在 ToolRegistry 执行路径上的接线 seam（seam 5）。
 * 用真实临时文件驱动，断言外部行为：Pre BLOCK 不执行工具、Post additionalContext 进入下一轮注入通道、
 * 并行批次也触发 Post、无分发器时零成本、以及读前写台账端到端经 registry 阻断盲写。
 */
class ToolRegistryHookTest {

    /** 可配置的桩租户：对指定事件返回给定决定。 */
    private static final class StubTenant implements HookTenant {
        private final HookEvent event;
        private final HookDecision decision;

        StubTenant(HookEvent event, HookDecision decision) {
            this.event = event;
            this.decision = decision;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public HookDecision evaluate(HookPayload payload) {
            return payload.event() == event ? decision : HookDecision.cont();
        }
    }

    private static ToolRegistry registryWith(Path root, HookEvent event, HookTenant tenant) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.register(event, tenant);
        registry.setHookDispatcher(dispatcher);
        return registry;
    }

    @Test
    void preToolUseBlockShortCircuitsWithoutExecuting(@TempDir Path root) {
        ToolRegistry registry = registryWith(root, HookEvent.PRE_TOOL_USE,
                new StubTenant(HookEvent.PRE_TOOL_USE, HookDecision.block("nope")));
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            String out = registry.executeTool("write_file", "{\"path\":\"Foo.java\",\"content\":\"x\"}");

            assertTrue(out.contains("nope"), out);
            assertFalse(Files.exists(root.resolve("Foo.java")), "BLOCK 后工具不应执行");
            List<AuditLog.AuditEntry> audit = registry.getAuditLog().readRecent(1);
            assertEquals(AuditLog.OUTCOME_HOOK_BLOCK, audit.get(0).outcome());
        } finally {
            restore(HookProperties.PRE_TOOL_USE_ENABLED, prev);
        }
    }

    @Test
    void postToolUseAdditionalContextReachesInjectionChannel(@TempDir Path root) {
        ToolRegistry registry = registryWith(root, HookEvent.POST_TOOL_USE,
                new StubTenant(HookEvent.POST_TOOL_USE, HookDecision.cont().withAdditionalContext("lint clean")));
        String prev = System.setProperty(HookProperties.POST_TOOL_USE_ENABLED, "true");
        try {
            registry.executeTool("list_dir", "{\"path\":\".\"}");

            assertEquals("lint clean", registry.flushPendingHookContext());
            assertNull(registry.flushPendingHookContext(), "flush 后清空");
        } finally {
            restore(HookProperties.POST_TOOL_USE_ENABLED, prev);
        }
    }

    @Test
    void postToolUseFiresOnParallelBatch(@TempDir Path root) {
        ToolRegistry registry = registryWith(root, HookEvent.POST_TOOL_USE,
                new StubTenant(HookEvent.POST_TOOL_USE, HookDecision.cont().withAdditionalContext("ctx")));
        String prev = System.setProperty(HookProperties.POST_TOOL_USE_ENABLED, "true");
        try {
            registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("1", "list_dir", "{\"path\":\".\"}"),
                    new ToolRegistry.ToolInvocation("2", "list_dir", "{\"path\":\".\"}")));

            // 两次调用各聚合一条 context —— 证明 Post 在并行批次每个调用都触发。
            assertEquals("ctx\n\nctx", registry.flushPendingHookContext());
        } finally {
            restore(HookProperties.POST_TOOL_USE_ENABLED, prev);
        }
    }

    @Test
    void zeroCostWhenNoDispatcherRegistered(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());

        String out = registry.executeTool("list_dir", "{\"path\":\".\"}");

        assertNotNull(out);
        assertNull(registry.flushPendingHookContext());
    }

    @Test
    void readLedgerBlocksBlindWriteThenPassesAfterRead(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}");
        ReadLedgerTenant ledger = new ReadLedgerTenant();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.register(HookEvent.PRE_TOOL_USE, ledger);
        dispatcher.register(HookEvent.POST_TOOL_USE, ledger);
        registry.setHookDispatcher(dispatcher);

        String pre = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        String post = System.setProperty(HookProperties.POST_TOOL_USE_ENABLED, "true");
        try {
            // 盲写：没读过 → 阻断，文件保持原样
            String blind = registry.executeTool("write_file", "{\"path\":\"Foo.java\",\"content\":\"blind\"}");
            assertTrue(blind.contains("还没读过") || blind.contains("先"), blind);
            assertEquals("class Foo {}", Files.readString(root.resolve("Foo.java")));

            // 先读，台账记录观测；再写 → 放行
            registry.executeTool("read_file", "{\"path\":\"Foo.java\"}");
            String written = registry.executeTool("write_file", "{\"path\":\"Foo.java\",\"content\":\"read then write\"}");
            assertFalse(written.contains("还没读过"), written);
            assertEquals("read then write", Files.readString(root.resolve("Foo.java")));
        } finally {
            restore(HookProperties.PRE_TOOL_USE_ENABLED, pre);
            restore(HookProperties.POST_TOOL_USE_ENABLED, post);
        }
    }

    @Test
    void userLevelExitTwoShellHookBlocksMatchedToolWithStderrReason(@TempDir Path root) throws Exception {
        // 用户级 hooks.json：PreToolUse 上一个 matcher=write_file 的 exit-2 脚本，stderr 作为模型可见原因。
        Path script = root.resolve("guard.sh");
        Files.writeString(script, "#!/bin/sh\necho 'blocked: no writes allowed' 1>&2\nexit 2\n");
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path userCfg = root.resolve("hooks.json");
        Files.writeString(userCfg, """
                { "PreToolUse": [ { "matcher": "write_file", "hooks": [ { "type": "command", "command": "%s" } ] } ] }
                """.formatted(script));

        HookDispatcher dispatcher = new HookDispatcher();
        for (HooksConfigLoader.Registration reg :
                new HooksConfigLoader(userCfg, root.resolve("noproj.json"), root, msg -> { }).load()) {
            dispatcher.register(reg.event(), reg.hook());
        }
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        registry.setHookDispatcher(dispatcher);

        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            String out = registry.executeTool("write_file", "{\"path\":\"Foo.java\",\"content\":\"x\"}");

            assertTrue(out.contains("blocked: no writes allowed"), out);
            assertFalse(Files.exists(root.resolve("Foo.java")), "被 shell hook 阻断后不应写文件");
            // 非匹配工具（list_dir）不受影响
            assertNotNull(registry.executeTool("list_dir", "{\"path\":\".\"}"));
        } finally {
            restore(HookProperties.PRE_TOOL_USE_ENABLED, prev);
        }
    }

    @Test
    void subagentPreToolUseAttributedToTaskId(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}");
        String[] capturedTaskId = {"UNSET"};
        HookTenant recorder = new HookTenant() {
            @Override
            public String name() {
                return "recorder";
            }

            @Override
            public HookDecision evaluate(HookPayload payload) {
                capturedTaskId[0] = payload.taskId();
                return HookDecision.block("blocked by recorder");
            }
        };
        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(root.toString());
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.register(HookEvent.PRE_TOOL_USE, recorder);
        parent.setHookDispatcher(dispatcher);

        ToolRegistry child = parent.createChildRegistry();
        child.setResultScope("task_1");

        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            // 子任务派发同一 hook（共享分发器），payload.taskId = 其 scope。
            child.executeTool("write_file", "{\"path\":\"Foo.java\",\"content\":\"x\"}");
            assertEquals("task_1", capturedTaskId[0], "子任务 hook 决定应带 task 归属");
            assertTrue(child.getAuditLog().readRecent(20).stream()
                            .anyMatch(e -> AuditLog.OUTCOME_HOOK_BLOCK.equals(e.outcome())
                                    && e.tool() != null && e.tool().contains("task_1")),
                    "hook_block 审计应带 task_1 归属");

            // 主 Agent（scope=main）→ taskId 为 null
            parent.executeTool("write_file", "{\"path\":\"Foo.java\",\"content\":\"y\"}");
            assertNull(capturedTaskId[0], "主 Agent hook 决定 taskId 应为 null");
        } finally {
            restore(HookProperties.PRE_TOOL_USE_ENABLED, prev);
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }
}
