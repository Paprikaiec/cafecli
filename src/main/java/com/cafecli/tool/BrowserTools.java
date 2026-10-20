package com.cafecli.tool;

import com.cafecli.browser.BrowserConnector;
import com.cafecli.tool.ToolOutput.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.cafecli.tool.ToolParam.createParameters;

/**
 * 浏览器模式切换三件套：{@code browser_connect} / {@code browser_disconnect} / {@code browser_status}，
 * 全部委派给注入的 {@link BrowserConnector}。连接器由 {@code setBrowserConnector} 在注册之后才注入
 * （子任务 registry 也是注册后才接过父的），所以经 supplier 在<b>每次调用时</b>现取（规则见 {@link ToolEnv}）。
 */
final class BrowserTools {
    private final Supplier<BrowserConnector> browserConnector;

    BrowserTools(Supplier<BrowserConnector> browserConnector) {
        this.browserConnector = browserConnector;
    }

    List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                createParameters(),
                args -> {
                    BrowserConnector connector = browserConnector.get();
                    return connector == null
                            ? ToolOutput.failure(Status.ERROR, "浏览器连接器未初始化，无法自动切换 shared 模式")
                            : ToolOutput.text(connector.connectDefault());
                }
        ));
        tools.add(new Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                createParameters(),
                args -> {
                    BrowserConnector connector = browserConnector.get();
                    return connector == null
                            ? ToolOutput.failure(Status.ERROR, "浏览器连接器未初始化，无法切回 isolated 模式")
                            : ToolOutput.text(connector.disconnect());
                }
        ));
        tools.add(new Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                createParameters(),
                args -> {
                    BrowserConnector connector = browserConnector.get();
                    return connector == null
                            ? ToolOutput.failure(Status.ERROR, "浏览器连接器未初始化，无法查看浏览器状态")
                            : ToolOutput.text(connector.status());
                }
        ));
        return tools;
    }
}
