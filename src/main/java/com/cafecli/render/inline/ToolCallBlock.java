package com.cafecli.render.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.BlockOverflow;
import com.cafecli.tool.ToolRegistry;
import com.cafecli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One [[工具块]]: a batch of tool calls and, once they return, their results (ADR 0017 D2).
 *
 * <p>It is deliberately <b>not</b> a {@link LiveFoldableBlock}. A batch is opened in the [[活区]] by
 * {@code appendToolCalls} carrying only its call header, and it is not a completed foldable until
 * {@code appendToolResults} fills the results in. That is the whole reason
 * {@link LiveRegionStrip#freezeSettled()} needs no change and gets none: its rule — commit
 * <em>completed</em> foldables — keeps holding exactly as written, because a running batch is not one.
 * Writing a "skip incomplete blocks" branch there would turn a rule that already holds into a special
 * case.
 *
 * <p><b>A running tool block is not the delayed freeze ADR 0012 rejected.</b> The rejected thing held
 * a <em>completed</em> block open so it could be expanded in place; this block has <b>no result
 * yet</b>. The discriminant is "has this block completed", and while it has not, it is transient
 * content sitting where transient content belongs — beside the spinner and the [[审批应答]] card.
 * Once complete it is committed and, like every other frozen row, never changes again.
 *
 * <p>Two caps in two units, and they are not one unit in disguise (D3). The call header is capped in
 * <b>source</b> units because it is an identity — {@code Shell(mvn -q test)} should be the same thing
 * at any width. The result preview is capped in <b>visual</b> rows, because what it spends is screen:
 * a 400-character grep hit is one source line and five rows at 80 columns. The overflow count
 * therefore moves with the width, which is intended and is not ADR 0010 amendment A11's trap — that
 * one is about a boundary which has to survive re-projection, and a retained entry is simply
 * re-rendered from source at the new width.
 */
final class ToolCallBlock implements StripEntry {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The call header is an identity, so it is bounded in source units (D3). */
    private static final int MAX_HEADER_LINES = 2;
    private static final int MAX_HEADER_CHARS = 160;

    /**
     * <b>Picked, not measured.</b> Three is Claude Code's number, and Claude Code has no [[状态坞]];
     * on 80×24 the [[活区尾]] has roughly 17 rows left after the [[输入框]], the dock and the rules,
     * and one batch of four parallel calls at 2 header rows + 3 result rows is already 20. The block
     * is committed rather than resident, so it scrolls past instead of bursting the region — but the
     * number itself is a guess. Measuring it is issue 04's job; do not cite it as measured.
     */
    private static final int MAX_RESULT_VISUAL_ROWS = 3;

    /**
     * The running tail of a command that has not returned yet (ADR 0017 D5).
     *
     * <p><b>Picked, not measured</b>, exactly like {@link #MAX_RESULT_VISUAL_ROWS} above it. The real
     * criterion would be "how many rows does the [[活区尾]] have left to be useful", squeezed by the
     * [[状态坞]], the [[输入框]] and the [[bounded replay window]] — the same measurement ADR 0016's
     * 20 / 12 never got either. Issue 04 owns it; do not cite this as a measured value.
     *
     * <p>Concurrent commands <b>share</b> this budget rather than each getting it, because what is
     * spent is one screen: one command gets all five rows, four (the default parallelism) get one each.
     */
    private static final int MAX_RUNNING_TAIL_ROWS = 5;

    private static final AttributedStyle RESULT = AttributedStyle.DEFAULT.faint();

    /**
     * The tools whose result view <b>is</b> a diff (ADR 0017 D4).
     *
     * <p>These two are exactly the tools that route through {@code FileTools.persistFileContent},
     * which is what fires the write observer and therefore what puts a diff into this block. Their
     * text result — {@code "文件已编辑: …，共 N 条编辑，替换 M 处匹配"} — says the same thing as the diff
     * underneath it, and showing both is noise.
     *
     * <p>{@code create_project} is deliberately absent and is not a hole to be filled: it writes
     * scaffolding with a bare {@code Files.writeString}, never reaches {@code persistFileContent}, and
     * is a scaffold generator rather than a file-<em>change</em> tool — diffs of four {@code pom.xml}
     * templates are noise. It takes an ordinary text result.
     */
    private static final Set<String> RESULT_VIEW_IS_A_DIFF = Set.of("write_file", "edit_file");

    private final Terminal terminal;
    private final Set<String> callIds;
    private final List<AttributedString> collapsedHeader;
    private final List<AttributedString> expandedCalls;
    /** This batch's results, each in the view its own type asks for; empty until they arrive. */
    private final List<ResultView> resultViews = new ArrayList<>();
    /** The paths a diff has actually been delivered for, keyed as the model wrote them. */
    private final Set<String> pathsShownAsDiff = new LinkedHashSet<>();
    /**
     * Per-command running output, newest last, capped at {@link #MAX_RUNNING_TAIL_ROWS} lines each.
     * Insertion-ordered so the commands keep their positions on screen while they run instead of
     * reordering under the reader every time one of them prints.
     *
     * <p>Emptied when the results arrive: the tail is what the batch shows <em>instead of</em> its
     * results, never alongside them (D5).
     */
    private final Map<String, List<String>> runningTails = new LinkedHashMap<>();
    private boolean complete;
    private boolean expanded;
    private boolean frozen;

    ToolCallBlock(Terminal terminal, List<LlmClient.ToolCall> toolCalls) {
        this.terminal = terminal;
        this.callIds = ids(toolCalls);
        Map<String, List<LlmClient.ToolCall>> grouped = group(toolCalls);
        this.collapsedHeader = capped(collapsedHeader(grouped)).stream()
                .map(BlockHeader::identity)
                .toList();
        this.expandedCalls = expanded(grouped);
    }

    /**
     * Whether {@code results} belong to this batch, matched by {@code tool_call_id} (D2). Matching on
     * the id rather than on "the newest open block" is what keeps the pair together when more than one
     * batch is somehow in flight.
     */
    boolean owns(List<ToolRegistry.ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        return results.stream().anyMatch(result -> result != null && callIds.contains(result.id()));
    }

    boolean isComplete() {
        return complete;
    }

    /**
     * A file this batch changed, arriving mid-execution through the write observer (ADR 0017 D4).
     *
     * <p>It lands in the block that is <em>running</em>, so the call header and the change it produced
     * are one block rather than two — a background completion notice or a [[转向消息]]'s [[提交块]]
     * committed in between would otherwise tear them apart (D2). Until the batch completes the diff is
     * simply part of what the live region draws for it.
     */
    void attachDiff(DiffBlock diff) {
        if (diff == null) {
            return;
        }
        resultViews.add(new DiffResult(diff));
        String path = diff.sourcePath();
        if (path != null && !path.isBlank()) {
            pathsShownAsDiff.add(path);
        }
    }

    /**
     * Fill in the results and complete the batch. This is a <b>producer event</b>, which is why the
     * mutation is legal under ADR 0016 D1: the entry never changes on {@code render()}, so laying it
     * out for the [[转写浏览层]] on the input thread while the [[活区]] lays it out on the render
     * thread mutates nothing on either side.
     */
    void completeWith(List<ToolRegistry.ToolExecutionResult> results) {
        List<String> text = new ArrayList<>();
        if (results != null) {
            for (ToolRegistry.ToolExecutionResult result : results) {
                if (result == null || alreadyShownAsItsOwnDiff(result)) {
                    continue;
                }
                String value = result.result();
                if (value == null || value.isBlank()) {
                    continue;
                }
                text.addAll(value.replace("\r\n", "\n").replace('\r', '\n').strip().lines().toList());
            }
        }
        if (!text.isEmpty()) {
            resultViews.add(new TextResult(terminal, List.copyOf(text)));
        }
        this.complete = true;
        // The tail is replaced, not appended to: while it ran, the newest lines were what said the
        // command was alive; now that it is done, the first rows of its result are what say what
        // happened. Dropping the tail here is also what keeps a completed block from ever containing
        // two views of the same command.
        this.runningTails.clear();
    }

    /**
     * One line a still-running command just printed (ADR 0017 D5). Only {@code execute_command} feeds
     * this: it is the one tool whose duration is unbounded and where the user genuinely cannot tell
     * whether anything is happening — {@code read_file} and {@code grep_code} return in milliseconds.
     *
     * <p>Like {@link #completeWith}, this is a <b>producer event</b> and never a side effect of
     * rendering, so ADR 0016 D1 holds: the entry is immutable source as far as any view is concerned.
     * It reaches here on the render thread, posted from the {@code cafecli-command-output} thread —
     * the render thread stays the live region's only writer.
     *
     * <p>Blank lines are dropped rather than shown: five rows of a command's own progress spacing say
     * nothing about whether it is alive.
     */
    void appendRunningOutput(String command, String line) {
        if (complete || line == null || line.isBlank()) {
            return;
        }
        List<String> tail = runningTails.computeIfAbsent(
                command == null ? "" : command, ignored -> new ArrayList<>());
        tail.add(line.stripTrailing());
        // Never more than the whole budget: a command can never show more rows than the region has.
        while (tail.size() > MAX_RUNNING_TAIL_ROWS) {
            tail.removeFirst();
        }
    }

    /**
     * Whether this result has already been shown — as the diff its own write produced (D4).
     *
     * <p>Both conditions carry weight. The tool has to be one whose result view is a diff at all, and
     * the diff has to have actually arrived <em>for this path</em>: a write that failed fires no
     * observer, so dropping its {@code "写入文件失败: …"} would leave a call header with nothing under
     * it. Matching on the {@code path} argument is exact rather than approximate — the observer is
     * handed the very string the model sent.
     */
    private boolean alreadyShownAsItsOwnDiff(ToolRegistry.ToolExecutionResult result) {
        if (!RESULT_VIEW_IS_A_DIFF.contains(result.name())) {
            return false;
        }
        String path = pathArgument(result.argumentsJson());
        return path != null && pathsShownAsDiff.contains(path);
    }

    private static String pathArgument(String json) {
        try {
            return JSON.readTree(json).path("path").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public List<AttributedString> render(int width) {
        if (frozen) {
            return List.of();
        }
        return render(width, foldState());
    }

    @Override
    public List<AttributedString> render(int width, FoldState foldState) {
        int columns = Math.max(1, width);
        // No memoization: the rows belong to whichever view asked for them (ADR 0016 D1).
        List<AttributedString> rows = foldState == FoldState.COLLAPSED
                ? collapsedRows(columns) : expandedRows(columns);
        List<AttributedString> tail = runningRows(columns);
        if (tail.isEmpty()) {
            return rows;
        }
        List<AttributedString> withTail = new ArrayList<>(rows);
        withTail.addAll(tail);
        return List.copyOf(withTail);
    }

    /**
     * The output tail of the commands in this batch that are still running.
     *
     * <p><b>This is not the delayed freeze ADR 0012 rejected</b>, though it looks exactly like it. The
     * rejected thing held a block open <em>after it had completed</em> so that it could be expanded in
     * place; this block <b>has no result yet</b>. The discriminant is "has this block completed", and
     * until it has, these rows are transient content sitting where transient content belongs — beside
     * the spinner and the [[审批应答]] card, committed to nothing, in the [[转写浏览层]] not at all
     * (D6). The next reader of this file would otherwise file it as a bug.
     *
     * <p>The rows are <b>clipped</b>, not wrapped, so the five-row budget is exact: a command that
     * prints a 400-character line must not silently take the whole tail. The completed result wraps
     * instead, because there the cap is what the user is <em>spending</em> screen on, and it is worth
     * spending on one long line.
     */
    private List<AttributedString> runningRows(int columns) {
        if (complete || runningTails.isEmpty()) {
            return List.of();
        }
        int perCommand = Math.max(1, MAX_RUNNING_TAIL_ROWS / runningTails.size());
        List<AttributedString> rows = new ArrayList<>();
        for (List<String> tail : runningTails.values()) {
            int from = Math.max(0, tail.size() - perCommand);
            for (String line : tail.subList(from, tail.size())) {
                if (rows.size() >= MAX_RUNNING_TAIL_ROWS) {
                    // More concurrent commands than the budget has rows: the ones that started
                    // printing first keep their row and the rest wait. A row per command would burst
                    // the budget the whole point of which is to bound the live tail.
                    return List.copyOf(rows);
                }
                // Same row styling a finished text result uses: while it runs and once it is done,
                // these are the same tool's output, and the tail should not change appearance the
                // moment it is replaced by the result head.
                rows.add(BlockSource.clip(TextResult.row(line), columns, terminal));
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return render(width, foldState());
    }

    @Override
    public boolean isFoldable() {
        return true;
    }

    @Override
    public FoldState foldState() {
        return expanded ? FoldState.EXPANDED : FoldState.COLLAPSED;
    }

    @Override
    public void persistFoldState(FoldState foldState) {
        expanded = foldState == FoldState.EXPANDED;
    }

    /**
     * A batch that is still running has nothing to freeze. This is what keeps the live-tail overflow
     * path from committing a half-finished block — the same answer {@code ApprovalBlock} and
     * {@code ActivityBlock} give, for the same reason.
     */
    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        if (minimumRows <= 0 || frozen || !complete) {
            return List.of();
        }
        List<AttributedString> rows = render(Math.max(1, width), foldState());
        frozen = true;
        return rows;
    }

    private List<AttributedString> collapsedRows(int columns) {
        return rows(wrapAll(collapsedHeader, columns), columns, FoldState.COLLAPSED);
    }

    private List<AttributedString> expandedRows(int columns) {
        return rows(wrapAll(expandedCalls, columns), columns, FoldState.EXPANDED);
    }

    /**
     * The call header, and then each result in the view its own type asks for.
     *
     * <p>How far a result is allowed to run is answered by the view and by nobody else — there is no
     * "if this one is a diff, skip the cap" here, because that would make the diff an exception to the
     * text rule instead of the other half of one rule (D4).
     */
    private List<AttributedString> rows(List<AttributedString> header, int columns,
                                        FoldState foldState) {
        List<AttributedString> rows = new ArrayList<>(header);
        for (ResultView view : resultViews) {
            rows.addAll(view.rows(columns, foldState));
        }
        return List.copyOf(rows);
    }

    private List<AttributedString> wrapAll(List<AttributedString> sources, int columns) {
        List<AttributedString> rows = new ArrayList<>();
        for (AttributedString source : sources) {
            rows.addAll(wrap(source, columns, terminal));
        }
        return rows;
    }

    private static List<AttributedString> wrap(AttributedString source, int columns,
                                               Terminal terminal) {
        return ProseEntityHighlighter.wrap(source, columns, terminal);
    }

    /**
     * How one result is shown — and therefore how much of it fits (ADR 0017 D3/D4).
     *
     * <p>The cap belongs to the <b>shape of the result</b>, not to the block: bare text spends screen,
     * so it is bounded in <b>visual rows</b>; a diff clips its over-wide lines instead of wrapping
     * them, so its bound is in <b>source lines</b> and is the one {@link DiffBlock} already applies.
     * These are two halves of one rule rather than a rule plus an exception, which is why choosing
     * between them is dispatch and not a conditional.
     */
    private sealed interface ResultView permits TextResult, DiffResult {

        List<AttributedString> rows(int columns, FoldState foldState);
    }

    /**
     * A tool's own bare output head, with no per-tool formatting. Collapsed it is capped in visual
     * rows; in the [[转写浏览层]] it is whole, because a second cap there would create content that is
     * visible nowhere at all (D7) — and unboundedness is already handled upstream, where shell output
     * is truncated at the source and oversized MCP results are spilled to disk (ADR 0009).
     */
    private record TextResult(Terminal terminal, List<String> lines) implements ResultView {

        @Override
        public List<AttributedString> rows(int columns, FoldState foldState) {
            return foldState == FoldState.EXPANDED ? whole(columns) : preview(columns);
        }

        private List<AttributedString> whole(int columns) {
            List<AttributedString> rows = new ArrayList<>();
            for (String line : lines) {
                rows.addAll(wrap(row(line), columns, terminal));
            }
            return rows;
        }

        private List<AttributedString> preview(int columns) {
            List<AttributedString> preview = new ArrayList<>();
            int shownSourceLines = 0;
            for (String line : lines) {
                if (preview.size() >= MAX_RESULT_VISUAL_ROWS) {
                    break;
                }
                List<AttributedString> wrapped = wrap(row(line), columns, terminal);
                if (preview.size() + wrapped.size() <= MAX_RESULT_VISUAL_ROWS) {
                    preview.addAll(wrapped);
                    shownSourceLines++;
                } else {
                    // A source line whose rows do not all fit is shown as far as the budget reaches
                    // and still counted as omitted: the user is told there is more of it to read.
                    preview.addAll(wrapped.subList(0, MAX_RESULT_VISUAL_ROWS - preview.size()));
                    break;
                }
            }
            int omitted = lines.size() - shownSourceLines;
            if (omitted > 0) {
                preview.add(BlockHeader.footnote(BlockOverflow.line(omitted)));
            }
            return preview;
        }

        private static AttributedString row(String line) {
            return new AttributedString("  " + line, RESULT);
        }
    }

    /**
     * The change a file-writing tool made, which <b>is</b> its result (D4). It brings its own cap with
     * it — the first 100 source lines and the same overflow line — so this view neither restates it nor
     * exempts it.
     */
    private record DiffResult(DiffBlock diff) implements ResultView {

        @Override
        public List<AttributedString> rows(int columns, FoldState foldState) {
            return diff.render(columns, foldState);
        }
    }

    private static Set<String> ids(List<LlmClient.ToolCall> calls) {
        Set<String> ids = new LinkedHashSet<>();
        if (calls != null) {
            for (LlmClient.ToolCall call : calls) {
                if (call != null && call.id() != null) {
                    ids.add(call.id());
                }
            }
        }
        return Set.copyOf(ids);
    }

    private static List<AttributedString> expanded(Map<String, List<LlmClient.ToolCall>> grouped) {
        List<AttributedString> lines = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            lines.add(AttributedString.fromAnsi(AnsiStyle.subtle(
                    "  " + toolLabel(group.getKey(), group.getValue().size()))));
            for (LlmClient.ToolCall call : group.getValue()) {
                String detail = oneLine(extractKeyParam(group.getKey(), call.function().arguments()));
                if (!detail.isEmpty()) {
                    lines.add(AttributedString.fromAnsi(AnsiStyle.subtle("    └ " + detail)));
                }
            }
        }
        return List.copyOf(lines);
    }

    private static Map<String, List<LlmClient.ToolCall>> group(List<LlmClient.ToolCall> calls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall call : calls) {
            grouped.computeIfAbsent(call.function().name(), ignored -> new ArrayList<>()).add(call);
        }
        return grouped;
    }

    /**
     * Bound the call header at two [[逻辑行]] or {@value #MAX_HEADER_CHARS} characters, whichever
     * arrives first, appending an ellipsis when anything was dropped. Source units, because the header
     * is the thing the user recognises the call by, and it should read the same at every width.
     */
    private static List<String> capped(String header) {
        String[] lines = header.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>();
        int budget = MAX_HEADER_CHARS;
        for (String line : lines) {
            if (out.size() == MAX_HEADER_LINES) {
                out.set(out.size() - 1, out.get(out.size() - 1) + "…");
                return List.copyOf(out);
            }
            if (line.length() > budget) {
                out.add(line.substring(0, Math.max(0, budget - 1)) + "…");
                return List.copyOf(out);
            }
            out.add(line);
            budget -= line.length();
        }
        return List.copyOf(out);
    }

    private static String collapsedHeader(Map<String, List<LlmClient.ToolCall>> grouped) {
        if (grouped.size() == 1) {
            var entry = grouped.entrySet().iterator().next();
            return collapsedLabel(entry.getKey(), entry.getValue());
        }
        int total = grouped.values().stream().mapToInt(List::size).sum();
        return grouped.size() + " 组工具调用 / " + total + " 次";
    }

    private static String collapsedLabel(String name, List<LlmClient.ToolCall> calls) {
        int count = calls.size();
        String label = toolLabel(name, count);
        if (count != 1) {
            return label;
        }
        String detail = extractKeyParam(name, calls.get(0).function().arguments());
        if (detail.isBlank()) {
            return label;
        }
        return switch (name) {
            case "web_search" -> "WebSearch(\"" + detail + "\")";
            case "web_fetch" -> "WebFetch(" + compactUrl(detail) + ")";
            case "search_code" -> "SearchCode(\"" + detail + "\")";
            case "read_file" -> "ReadFile(" + detail + ")";
            case "list_dir" -> "ListDir(" + detail + ")";
            case "execute_command" -> "Shell(" + detail + ")";
            default -> label + " · " + detail;
        };
    }

    private static String toolLabel(String name, int count) {
        return switch (name) {
            case "read_file" -> "读取 " + count + " 个文件";
            case "write_file" -> "写入 " + count + " 个文件";
            case "edit_file" -> "编辑 " + count + " 个文件";
            case "list_dir" -> "列出 " + count + " 个目录";
            case "execute_command" -> "执行 " + count + " 条命令";
            case "create_project" -> "创建 " + count + " 个项目";
            case "search_code" -> "搜索代码 " + count + " 次";
            case "web_search" -> "联网搜索 " + count + " 次";
            case "web_fetch" -> "抓取 " + count + " 个网页";
            case "save_memory" -> "保存长期记忆 " + count + " 条";
            default -> name != null && name.startsWith("mcp__")
                    ? mcpLabel(name, count) : name + " × " + count;
        };
    }

    private static String mcpLabel(String name, int count) {
        String[] parts = name.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : name;
        return count == 1 ? "调用 MCP 工具 " + display
                : "调用 MCP 工具 " + display + " × " + count;
    }

    /**
     * The raw key parameter, uncapped. Bounding it here would pre-empt {@link #capped(String)}, which
     * is where the header's one budget lives; the expanded per-call detail lines flatten it with
     * {@link #oneLine(String)} instead, because a row of the live region may not contain a newline.
     */
    private static String extractKeyParam(String name, String json) {
        try {
            JsonNode node = JSON.readTree(json);
            String key = switch (name) {
                case "read_file", "write_file", "edit_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            return key == null ? (json == null ? "" : json) : node.path(key).asText("");
        } catch (Exception ignored) {
            return json == null ? "" : json;
        }
    }

    private static String oneLine(String value) {
        String flattened = value == null ? "" : value.replaceAll("\\s*\\R\\s*", " ").strip();
        return flattened.length() > MAX_HEADER_CHARS
                ? flattened.substring(0, MAX_HEADER_CHARS - 1) + "…" : flattened;
    }

    private static String compactUrl(String url) {
        return url == null ? "" : url.trim()
                .replaceFirst("^https?://", "").replaceFirst("/+$", "");
    }
}
