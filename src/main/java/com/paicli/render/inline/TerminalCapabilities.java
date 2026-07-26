package com.paicli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.util.Locale;

/**
 * 终端能力探测：决定 inline 渲染器的各项特性是否可启用。
 *
 * <p>探测逻辑保守——能开则开，老终端 / 非 TTY 环境优雅降级。
 */
public final class TerminalCapabilities {

    private TerminalCapabilities() {
    }

    /** 终端是否能渲染 ANSI 转义序列（颜色、光标控制、inline status 等）。 */
    public static boolean supportsAnsi(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        String type = terminal.getType();
        if (type != null && type.equalsIgnoreCase("dumb")) {
            return false;
        }
        if (System.getenv("NO_COLOR") != null) {
            // NO_COLOR 只影响样式，不影响光标控制——保留 true，颜色由 AnsiStyle 自己关
            return true;
        }
        String envTerm = System.getenv("TERM");
        return envTerm == null || !envTerm.equalsIgnoreCase("dumb");
    }

    /**
     * 终端是否适合启用 inline status 状态区。
     * 同时校验终端尺寸合理（rows ≥ 5）。
     */
    public static boolean supportsScrollRegion(Terminal terminal) {
        if (!supportsAnsi(terminal)) {
            return false;
        }
        if (Boolean.parseBoolean(com.paicli.config.ConfigHome.envLegacy("PAICLI_NO_STATUSBAR"))) {
            return false;
        }
        if (Boolean.parseBoolean(System.getProperty("paicli.no.statusbar"))) {
            return false;
        }
        Size size = safeSize(terminal);
        return size.getRows() >= 5 && size.getColumns() >= 20;
    }

    /** 终端是否支持 24-bit TrueColor（用于丰富的代码高亮等）。 */
    public static boolean supportsTrueColor() {
        String colorterm = System.getenv("COLORTERM");
        return "truecolor".equalsIgnoreCase(colorterm) || "24bit".equalsIgnoreCase(colorterm);
    }

    /** Whether the terminal explicitly reports a 256-color palette or larger. */
    public static boolean supports256Colors(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            Integer colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
            return colors != null && colors >= 256;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Whether completed prose may safely emit OSC 8 hyperlinks. */
    public static boolean supportsOsc8(Terminal terminal) {
        String override = System.getProperty("paicli.render.osc8");
        if (override == null || override.isBlank()) {
            override = com.paicli.config.ConfigHome.envLegacy("PAICLI_OSC8");
        }
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        if (!supportsAnsi(terminal)) {
            return false;
        }
        String type = terminal.getType() == null ? "" : terminal.getType().toLowerCase(Locale.ROOT);
        if (type.contains("kitty") || type.contains("wezterm") || type.contains("foot")
                || type.contains("ghostty") || type.contains("contour")) {
            return true;
        }
        String program = System.getenv("TERM_PROGRAM");
        if (program == null) {
            return false;
        }
        String normalized = program.toLowerCase(Locale.ROOT);
        return normalized.contains("iterm") || normalized.contains("wezterm")
                || normalized.contains("ghostty") || normalized.contains("kitty")
                || normalized.contains("vscode") || normalized.contains("hyper")
                || normalized.contains("warp") || normalized.contains("rio");
    }

    /** Whether frames may use DEC synchronized-output mode 2026. */
    public static boolean supportsSynchronizedOutput(Terminal terminal) {
        String override = System.getProperty("paicli.render.synchronized-output");
        if (override == null || override.isBlank()) {
            override = com.paicli.config.ConfigHome.envLegacy("PAICLI_SYNCHRONIZED_OUTPUT");
        }
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        if (!supportsAnsi(terminal)) {
            return false;
        }
        String type = terminal.getType() == null ? "" : terminal.getType().toLowerCase(Locale.ROOT);
        if (type.contains("kitty") || type.contains("wezterm") || type.contains("foot")
                || type.contains("alacritty") || type.contains("ghostty")) {
            return true;
        }
        String program = System.getenv("TERM_PROGRAM");
        if (program == null) {
            return false;
        }
        String normalized = program.toLowerCase(Locale.ROOT);
        return normalized.contains("iterm") || normalized.contains("wezterm")
                || normalized.contains("ghostty") || normalized.contains("kitty")
                || normalized.contains("foot") || normalized.contains("alacritty");
    }

    public static Size safeSize(Terminal terminal) {
        try {
            Size s = terminal.getSize();
            if (s == null || s.getRows() <= 0 || s.getColumns() <= 0) {
                return new Size(80, 24);
            }
            return s;
        } catch (Exception e) {
            return new Size(80, 24);
        }
    }
}
