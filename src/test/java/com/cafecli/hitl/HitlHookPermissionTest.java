package com.cafecli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookDispatcher;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookProperties;
import com.cafecli.hook.HookTenant;
import com.cafecli.hook.ShellHook;
import com.cafecli.policy.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ALLOW/ASK 在 HITL 审批边界的路由（issue 05，seam 4）。守卫绝对：ALLOW 只免审批、不豁免 PathGuard。
 */
class HitlHookPermissionTest {

    private record Stub(HookEvent event, HookDecision decision) implements HookTenant {
        @Override
        public String name() {
            return "stub";
        }

        @Override
        public HookDecision evaluate(HookPayload payload) {
            return payload.event() == event ? decision : HookDecision.cont();
        }
    }

    private static HitlToolRegistry registryWith(Path root, StubHandler handler, HookTenant... preTenants) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        HookDispatcher dispatcher = new HookDispatcher();
        for (HookTenant t : preTenants) {
            dispatcher.register(HookEvent.PRE_TOOL_USE, t);
        }
        registry.setHookDispatcher(dispatcher);
        return registry;
    }

    private static String write(Path target, String content) {
        return "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"" + content + "\"}";
    }

    @Test
    void allowSkipsApprovalAndExecutes(@TempDir Path root) throws Exception {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("ALLOW 应免除审批，不该发起审批");
        });
        HitlToolRegistry registry = registryWith(root, stub, new Stub(HookEvent.PRE_TOOL_USE, HookDecision.allow()));
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            Path target = root.resolve("out.txt");
            String result = registry.executeTool("write_file", write(target, "hello"));

            assertFalse(result.startsWith("[HITL]"), result);
            assertEquals("hello", Files.readString(target));
            assertTrue(registry.getAuditLog().readRecent(20).stream()
                    .anyMatch(e -> AuditLog.APPROVER_HOOK.equals(e.approver()) && AuditLog.OUTCOME_ALLOW.equals(e.outcome())));
        } finally {
            restore(prev);
        }
    }

    @Test
    void allowCannotLiftPathGuardDenial(@TempDir Path root, @TempDir Path outside) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = registryWith(root, stub, new Stub(HookEvent.PRE_TOOL_USE, HookDecision.allow()));
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            Path escaped = outside.resolve("escape.txt");  // 项目根之外
            registry.executeTool("write_file", write(escaped, "x"));

            assertFalse(Files.exists(escaped), "ALLOW 不应抬起 PathGuard 的越界拒绝（守卫绝对）");
        } finally {
            restore(prev);
        }
    }

    @Test
    void askForcesApprovalOverApproveAll(@TempDir Path root) {
        // list_dir 通常无人值守；ASK 应强制审批，即便会话级全批。
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        stub.setEnabled(true);
        HitlToolRegistry registry = registryWith(root, stub, new Stub(HookEvent.PRE_TOOL_USE, HookDecision.ask()));
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            registry.executeTool("list_dir", "{\"path\":\".\"}");
            assertEquals(1, stub.requestCount(), "ASK 应强制一次审批");
        } finally {
            restore(prev);
        }
    }

    @Test
    void compositionAskBeatsAllow(@TempDir Path root) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = registryWith(root, stub,
                new Stub(HookEvent.PRE_TOOL_USE, HookDecision.allow()),
                new Stub(HookEvent.PRE_TOOL_USE, HookDecision.ask()));
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            registry.executeTool("list_dir", "{\"path\":\".\"}");
            assertEquals(1, stub.requestCount(), "ASK 压过 ALLOW → 仍审批");
        } finally {
            restore(prev);
        }
    }

    @Test
    void compositionBlockBeatsAllow(@TempDir Path root) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = registryWith(root, stub,
                new Stub(HookEvent.PRE_TOOL_USE, HookDecision.allow()),
                new Stub(HookEvent.PRE_TOOL_USE, HookDecision.block("nope")));
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            Path target = root.resolve("x.txt");
            String result = registry.executeTool("write_file", write(target, "y"));
            assertTrue(result.contains("nope"), result);
            assertFalse(Files.exists(target), "BLOCK 压过 ALLOW → 不执行");
        } finally {
            restore(prev);
        }
    }

    @Test
    void shellHookOutcomeAllowDrivesSkip(@TempDir Path root) throws Exception {
        Path script = root.resolve("allow.sh");
        Files.writeString(script, "#!/bin/sh\necho '{\"outcome\":\"allow\"}'\nexit 0\n");
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        ShellHook shellHook = new ShellHook("allower", null, script.toString(),
                Duration.ofSeconds(5), null, msg -> { }, new ObjectMapper());

        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("shell ALLOW 应免审批");
        });
        HitlToolRegistry registry = registryWith(root, stub, shellHook);
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            Path target = root.resolve("s.txt");
            registry.executeTool("write_file", write(target, "shell-allowed"));
            assertEquals("shell-allowed", Files.readString(target));
        } finally {
            restore(prev);
        }
    }

    private static void restore(String prev) {
        if (prev == null) {
            System.clearProperty(HookProperties.PRE_TOOL_USE_ENABLED);
        } else {
            System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, prev);
        }
    }

    // 复用 HitlToolRegistryTest 的 stub 契约（此处独立一份，避免跨测试类可见性耦合）。
    private static final class StubHandler implements HitlHandler {
        private final Function<ApprovalRequest, ApprovalResult> decision;
        private int count;
        private boolean enabled = true;

        StubHandler(Function<ApprovalRequest, ApprovalResult> decision) {
            this.decision = decision;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            count++;
            return decision.apply(request);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        int requestCount() {
            return count;
        }
    }
}
