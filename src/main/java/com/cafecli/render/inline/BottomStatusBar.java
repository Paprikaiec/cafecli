package com.cafecli.render.inline;

import com.cafecli.render.StatusInfo;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 底部状态坞：只持有当前状态并把它格式化成两行，**自己从不写终端**。
 *
 * <p>它不再向 JLine 索取 {@code Status}（ADR 0014 决策 4）。理由有二：{@code LineReader} 重绘会在
 * <em>输入线程</em>上调 {@code Status.redraw()}，与渲染线程争终端；且 {@code Status} 持有自己的
 * {@code Display} 直接写终端，是 {@code LineReader} 截获管不到的第二个画者。取「根本不创建」而非
 * 「创建后挂起」——挂起只让 {@code redraw()} 成 no-op，{@code size()} 仍占行预算。JLine 自身只调用
 * 非创建版 {@code Status.getStatus(terminal, false)}，因此它恒为 null。
 *
 * <p>状态坞由渲染线程作为[[活区]]的尾部行画出：{@link LiveRegionStrip} 每帧向 {@link #rows(int)}
 * 取行，与活区尾合成同一帧。因此这里没有滚动区、没有保留行数、没有挂起 / 恢复。
 */
public final class BottomStatusBar implements AutoCloseable {

    private static final int CONTEXT_BAR_WIDTH = 8;
    private static final Pattern SUMMARY_RATIO = Pattern.compile("(?i)^(?:MCP|Skill)\\s+(\\d+)/(\\d+)$");

    private volatile StatusInfo current;
    private volatile boolean started;
    private volatile boolean closed;

    public BottomStatusBar() {
    }

    /** 启用状态坞。重复调用无副作用；不写终端。 */
    public synchronized void start() {
        if (closed) {
            return;
        }
        started = true;
    }

    public void update(StatusInfo info) {
        this.current = mergeEnvironment(info, current);
    }

    /** 当前 StatusInfo 快照，供 thinking 面板等组件复用同一份格式化结果。 */
    public StatusInfo currentStatus() {
        return current;
    }

    /**
     * The dock's rows at the given width, for the render thread to compose as the trailing rows of
     * the live region. Empty before {@code start()}, after {@code close()}, and until the first
     * status update arrives.
     */
    public List<AttributedString> rows(int width) {
        StatusInfo info = current;
        if (info == null || !started || closed) {
            return List.of();
        }
        return formatStatusLines(info, Math.max(1, width));
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    static String formatStatusLine(StatusInfo info, int cols) {
        String mode = info.hitlEnabled() ? "HITL Ctrl+Y for YOLO" : "YOLO Ctrl+Y to enable HITL";
        String right = environmentSummary(info);
        if (right.isBlank()) {
            return fitToColumns(" " + mode, cols);
        }
        int gap = Math.max(1, cols - visibleLength(mode) - visibleLength(right) - 2);
        return fitToColumns(" " + mode + " ".repeat(gap) + right + " ", cols);
    }

    static String formatFooterLine(StatusInfo info, int cols) {
        String model = info.model() == null || info.model().isBlank() ? "Auto Model" : info.model().trim();
        String phase = info.phase() == null || info.phase().isBlank() ? "idle" : info.phase().trim();
        StringBuilder sb = new StringBuilder(" Auto Model · ");
        sb.append(model);
        appendField(sb, phase);
        appendField(sb, contextSegment(info));
        if (info.inputTokens() > 0 || info.outputTokens() > 0 || info.cachedInputTokens() > 0) {
            appendField(sb, "in " + formatTokens(info.inputTokens()) + " out " + formatTokens(info.outputTokens()));
            if (info.cachedInputTokens() > 0) {
                sb.append(" cache ").append(formatTokens(info.cachedInputTokens()));
            }
            if (info.estimatedCost() != null && !info.estimatedCost().isBlank()) {
                sb.append(" · ").append(info.estimatedCost().trim());
            }
        }
        if (info.elapsedMillis() > 0) {
            appendField(sb, formatElapsed(info.elapsedMillis()));
        }
        appendField(sb, compactCwd());
        return fitToColumns(sb.toString(), cols);
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  ").append(value.trim());
    }

    static StatusInfo mergeEnvironment(StatusInfo next, StatusInfo previous) {
        if (next == null || previous == null) {
            return next;
        }
        String mcp = next.mcpSummary() == null || next.mcpSummary().isBlank()
                ? previous.mcpSummary()
                : next.mcpSummary();
        String skill = next.skillSummary() == null || next.skillSummary().isBlank()
                ? previous.skillSummary()
                : next.skillSummary();
        if (mcp == next.mcpSummary() && skill == next.skillSummary()) {
            return next;
        }
        return next.withEnvironment(mcp, skill);
    }

    static List<AttributedString> formatStatusLines(StatusInfo info, int cols) {
        return List.of(
                new AttributedString(formatStatusLine(info, cols), AttributedStyle.DEFAULT),
                new AttributedString(formatFooterLine(info, cols), AttributedStyle.DEFAULT.faint())
        );
    }

    private static String fitToColumns(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        String safe = text == null ? "" : text;
        if (safe.length() > cols) {
            return safe.substring(0, cols);
        }
        return safe + " ".repeat(cols - safe.length());
    }

    private static String environmentSummary(StatusInfo info) {
        String mcp = formatEnvironment(info.mcpSummary(), "MCP server", "MCP servers");
        String skill = formatEnvironment(info.skillSummary(), "skill", "skills");
        if (mcp.isBlank()) {
            return skill;
        }
        if (skill.isBlank()) {
            return mcp;
        }
        return mcp + " · " + skill;
    }

    private static String formatEnvironment(String raw, String singular, String plural) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        Matcher matcher = SUMMARY_RATIO.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        int active = Integer.parseInt(matcher.group(1));
        int total = Integer.parseInt(matcher.group(2));
        if (active == total) {
            return total + " " + (total == 1 ? singular : plural);
        }
        return active + "/" + total + " " + plural;
    }

    private static String contextSegment(StatusInfo info) {
        long total = Math.max(0L, info.totalTokens());
        long window = Math.max(0L, info.contextWindow());
        int percent = window <= 0L ? 0 : (int) Math.min(100L, Math.round(total * 100.0 / window));
        int filled = window <= 0L ? 0 : (int) Math.min(CONTEXT_BAR_WIDTH,
                Math.round(total * CONTEXT_BAR_WIDTH * 1.0 / window));
        String bar = "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, CONTEXT_BAR_WIDTH - filled));
        return "ctx " + bar + " " + percent + "% (" + formatTokens(total) + "/" + formatTokens(window) + ")";
    }

    private static String compactCwd() {
        String cwd = System.getProperty("user.dir");
        if (cwd == null || cwd.isBlank()) {
            return "";
        }
        String normalized = Path.of(cwd).toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && normalized.startsWith(home)) {
            normalized = "~" + normalized.substring(home.length());
        }
        return normalized;
    }

    private static int visibleLength(String text) {
        return text == null ? 0 : text.length();
    }

    private static String formatTokens(long t) {
        if (t >= 1_000_000) {
            return String.format("%.1fM", t / 1_000_000.0);
        }
        if (t >= 1_000) {
            return String.format("%.1fk", t / 1_000.0);
        }
        return String.valueOf(t);
    }

    private static String formatElapsed(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }
}
