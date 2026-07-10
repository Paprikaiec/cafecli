package com.paicli.tool;

import com.paicli.hook.HookDecision;
import com.paicli.hook.HookDispatcher;
import com.paicli.hook.HookEvent;
import com.paicli.hook.HookPayload;
import com.paicli.hook.HookProperties;
import com.paicli.hook.HookTenant;
import com.paicli.hook.ReadLedgerTenant;
import com.paicli.policy.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }
}
