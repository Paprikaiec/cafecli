package com.cafecli.render;

import com.cafecli.render.inline.InlineRenderer;
import com.cafecli.render.inline.TerminalCapabilities;
import org.jline.terminal.Terminal;

/**
 * 启动时根据环境变量选择渲染器形态。
 *
 * <p>选型规则：
 * <ul>
 *   <li>{@code -Dcafecli.renderer} > {@code CAFECLI_RENDERER} 环境变量 > 默认 inline</li>
 *   <li>{@code plain} → {@link PlainRenderer}，显式降级逃生口</li>
 *   <li>{@code inline}（默认）→ Inline 流式（{@code InlineRenderer}）</li>
 *   <li>已退役：{@code lanterna} / {@code tui}（Lanterna 全屏 TUI，ADR 0015）→ 提示后回退 inline</li>
 * </ul>
 *
 * <p>当 inline 目标渲染器初始化失败（如终端不支持 ANSI），自动 fallback 到
 * {@link PlainRenderer}，并在 stderr 打日志。
 */
public final class RendererFactory {

    public enum Mode {
        INLINE, PLAIN
    }

    private RendererFactory() {
    }

    public static Mode resolveMode() {
        String prop = System.getProperty("paicli.renderer");
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        String env = com.cafecli.config.ConfigHome.envLegacy("PAICLI_RENDERER");
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        return Mode.INLINE;
    }

    private static Mode parse(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "plain" -> Mode.PLAIN;
            case "inline" -> Mode.INLINE;
            case "lanterna", "tui" -> {
                System.err.println("⚠️ Lanterna 全屏 TUI 已移除（ADR 0015），CAFECLI_RENDERER='"
                        + raw.trim() + "' 回退到 inline；如需纯文本降级请用 CAFECLI_RENDERER=plain");
                yield Mode.INLINE;
            }
            default -> {
                System.err.println("⚠️ 未识别的 CAFECLI_RENDERER='" + raw + "'，回退到 inline");
                yield Mode.INLINE;
            }
        };
    }

    /**
     * 创建 CLI 循环内使用的渲染器。inline 模式如果终端不支持 ANSI（如 dumb 终端），回退到 plain。
     *
     * @param terminal JLine terminal，用于 inline 模式探测能力。可为 null。
     */
    public static Renderer create(Mode mode, Terminal terminal) {
        return switch (mode) {
            case PLAIN -> new PlainRenderer();
            case INLINE -> {
                if (TerminalCapabilities.supportsAnsi(terminal)) {
                    yield new InlineRenderer(terminal);
                }
                System.err.println("⚠️ 终端不支持 ANSI，inline 模式回退到 plain");
                yield new PlainRenderer();
            }
        };
    }
}
