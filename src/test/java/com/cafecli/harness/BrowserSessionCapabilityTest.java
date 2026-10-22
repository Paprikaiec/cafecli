package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.browser.BrowserConnector;
import com.cafecli.browser.BrowserGuard;
import com.cafecli.browser.BrowserSession;
import com.cafecli.browser.SensitivePagePolicy;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.browser.BrowserCapability;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionCapabilityTest {
    @TempDir Path root;
    private static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"navigate_page", "new_page"})
    void failedNavigationCannotRemoveTheSensitivePageApprovalRequirement(String navigation) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var browser = new BrowserSession(); browser.rememberNavigation("https://github.com/settings/profile");
        var notices = new java.util.ArrayList<String>();
        var permissions = new com.cafecli.harness.permission.PermissionCapability((request, token) -> {
            notices.add(request.sensitiveNotice());
            return com.cafecli.harness.permission.ApprovalResult.approveAllByServer();
        }, true);
        Capability chrome = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__chrome-devtools__" + navigation, "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> { throw new IllegalStateException("navigation failed"); })));
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__chrome-devtools__fill", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("filled"))));
            }
        };
        var capability = new BrowserCapability(browser, root.resolve("rules"), () -> null, permissions,
                new com.cafecli.browser.BrowserConnectivityCheck());
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("nav", "mcp__chrome-devtools__" + navigation,
                "{\"url\":\"https://example.test/\"}").build(), FakeResponse.builder()
                .toolCall("fill", "mcp__chrome-devtools__fill", "{}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(permissions, chrome, capability)).build()) {
            session.submit("navigate then fill");
            assertEquals("https://github.com/settings/profile", browser.lastNavigatedUrl());
            assertEquals(2, notices.size(), "failed navigation cannot turn the next sensitive write into a remembered grant");
            assertTrue(notices.get(1).contains("必须单步审批"));
        }
    }
    @Test void sharedBrowserTabsCommandUsesTheSamePermissionHooksAndAuditRoute() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.pre_tool_use.enabled", "true",
                "cafecli.hook.post_tool_use.enabled", "true"), Map.of(), root.resolve("home"), root));
        var browser = new BrowserSession(); browser.switchToShared("fixture");
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var pre = new java.util.concurrent.atomic.AtomicInteger();
        var post = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new com.cafecli.harness.permission.PermissionCapability((request, token) -> {
            approvals.incrementAndGet(); return com.cafecli.harness.permission.ApprovalResult.approve();
        }, true);
        var audit = new com.cafecli.harness.permission.AuditCapability(permissions, new com.cafecli.policy.AuditLog(root.resolve("audit")));
        var hooks = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), List.of(
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.PRE_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "pre"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                pre.incrementAndGet(); return com.cafecli.hook.HookDecision.cont();
                            }
                        }), new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.POST_TOOL_USE,
                        new com.cafecli.hook.HookTenant() {
                            public String name() { return "post"; }
                            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                                post.incrementAndGet(); return com.cafecli.hook.HookDecision.cont();
                            }
                        })), audit);
        Capability chrome = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__chrome-devtools__list_pages", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("fixture tabs"))));
            }
        };
        var capability = new BrowserCapability(browser, root.resolve("rules"), () -> null, permissions,
                new com.cafecli.browser.BrowserConnectivityCheck());
        try (var session = AgentSession.builder().provider(FakeProvider.scripted(List.of(FakeResponse.text("unused"))))
                .model(MODEL).config(config).capabilities(List.of(permissions, audit, hooks, chrome, capability)).build()) {
            assertTrue(session.dispatch("/browser tabs").orElseThrow().contains("fixture tabs"));
            assertEquals(1, approvals.get()); assertEquals(1, pre.get()); assertEquals(1, post.get());
            assertTrue(session.log().isEmpty());
            audit.flush();
            try (var files = java.nio.file.Files.list(root.resolve("audit"))) {
                var rows = files.filter(path -> path.toString().endsWith(".jsonl")).flatMap(path -> {
                    try { return java.nio.file.Files.readAllLines(path).stream(); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }).toList();
                assertEquals(1, rows.size());
                assertTrue(rows.getFirst().contains("mcp__chrome-devtools__list_pages"));
            }
        }
    }
    @Test void browserToolsExistOnlyWhenAttachedAndKeepConnectorText() {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("status", "browser_status", "{}").build(), FakeResponse.text("done"), FakeResponse.text("closed")));
        var connector = new BrowserConnector() {
            public String status() { return "local browser status"; }
            public String connectDefault() { throw new AssertionError("no real browser"); }
            public String disconnect() { return "disconnected"; }
        };
        var guard = new BrowserGuard(new BrowserSession(), new SensitivePagePolicy(root.resolve("rules")));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new BrowserCapability(connector, guard))).build()) {
            session.submit("inspect browser");
            assertEquals(List.of("browser_connect", "browser_disconnect", "browser_status"),
                    provider.requests().getFirst().context().tools().stream().map(com.cafecli.ai.ToolDefinition::name).toList());
            assertEquals(List.of(new TextBlock("local browser status")), ((ToolResultEntry) session.log().get(2)).message().content());
        }
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config).capabilities(List.of()).build()) {
            session.submit("closed");
            assertTrue(provider.requests().getLast().context().tools().isEmpty());
        }
    }
    @Test void hookAllowCannotCloseSomeoneElsesSharedBrowserTab() {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var browser = new BrowserSession(); browser.switchToShared("fixture");
        var guard = new BrowserGuard(browser, new SensitivePagePolicy(root.resolve("rules")));
        var connector = new BrowserConnector() {
            public String status() { return "fixture"; }
            public String connectDefault() { return "fixture"; }
            public String disconnect() { return "fixture"; }
        };
        Capability chrome = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__chrome-devtools__close_page", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("closed"))));
                agent.permissionRoute((call, token) -> com.cafecli.harness.permission.PermissionRoute.ALLOW);
            }
        };
        var permissions = new com.cafecli.harness.permission.PermissionCapability((request, token) -> {
            throw new AssertionError("ALLOW skips approval");
        }, true);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("close", "mcp__chrome-devtools__close_page",
                "{\"pageId\":\"user-tab\"}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(chrome, permissions, new BrowserCapability(connector, guard))).build()) {
            session.submit("close tab");
            var result = (ToolResultEntry) session.log().get(2);
            assertEquals(com.cafecli.core.ToolResult.Status.DENIED, result.status());
            assertEquals(List.of(new TextBlock("🛡️ 策略拒绝: shared 浏览器模式下拒绝关闭非 Cafecli 创建的标签页，请手动关闭该 Chrome 标签页")), result.message().content());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"continue", "allow", "disabled"})
    void sensitiveBrowserWritesAskPerCallOnlyInTheOrdinaryEnabledApprovalRoute(String route) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var browser = new BrowserSession(); browser.rememberNavigation("https://github.com/settings/profile");
        var guard = new BrowserGuard(browser, new SensitivePagePolicy(root.resolve("rules")));
        var connector = new BrowserConnector() {
            public String status() { return "fixture"; }
            public String connectDefault() { return "fixture"; }
            public String disconnect() { return "fixture"; }
        };
        Capability chrome = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool(
                        "mcp__chrome-devtools__fill", "fixture", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        args -> com.cafecli.tool.ToolOutput.text("filled"))));
                if (route.equals("allow")) agent.permissionRoute((call, token) -> com.cafecli.harness.permission.PermissionRoute.ALLOW);
            }
        };
        var notices = new java.util.ArrayList<String>();
        var permissions = new com.cafecli.harness.permission.PermissionCapability((request, token) -> {
            notices.add(request.sensitiveNotice());
            return new com.cafecli.harness.permission.ApprovalResult(com.cafecli.harness.permission.ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, null, null);
        }, !route.equals("disabled"));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("a", "mcp__chrome-devtools__fill", "{}").build(),
                FakeResponse.builder().toolCall("b", "mcp__chrome-devtools__fill", "{}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(chrome, permissions, new BrowserCapability(connector, guard))).build()) {
            session.submit("fill twice");
            if (route.equals("continue")) {
                assertEquals(List.of("敏感页面命中规则 *://github.com/settings/*，本次浏览器改写操作必须单步审批，不能复用全部放行。",
                        "敏感页面命中规则 *://github.com/settings/*，本次浏览器改写操作必须单步审批，不能复用全部放行。"), notices);
            } else assertTrue(notices.isEmpty());
        }
    }

    @Test void browserCommandsAndToolsShareTheExtractedUnconfiguredSessionBehavior() {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var permissions = com.cafecli.harness.permission.PermissionCapability.headless();
        var audit = new com.cafecli.harness.permission.AuditCapability(permissions, new com.cafecli.policy.AuditLog(root.resolve("audit")));
        var mcp = new com.cafecli.harness.mcp.McpCapability(config.mcp(), audit);
        var connectivity = new com.cafecli.browser.BrowserConnectivityCheck() {
            public ProbeResult probe(int port) { return new ProbeResult(false, null, "fixture offline"); }
        };
        var browser = new BrowserCapability(root.resolve("rules"), mcp::serverManager, permissions, connectivity);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("connect", "browser_connect", "{}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(permissions, audit, mcp, browser)).build()) {
            assertEquals("""
                    🌐 浏览器会话
                      当前模式: isolated（临时 user-data-dir，无登录态）
                      chrome-devtools server: 未配置
                      旧式 /json/version 探活: ⚠️ fixture offline
                      自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
                    """.trim(), session.dispatch("/browser status").orElseThrow());
            assertEquals("❌ 未配置 chrome-devtools MCP server，已清理本地浏览器会话状态", session.dispatch("/browser disconnect").orElseThrow());
            session.submit("connect browser");
            assertEquals(List.of(new TextBlock("❌ 未配置 chrome-devtools MCP server，请先检查 ~/.cafecli/mcp.json")),
                    ((ToolResultEntry) session.log().get(2)).message().content());
        }
    }

}
