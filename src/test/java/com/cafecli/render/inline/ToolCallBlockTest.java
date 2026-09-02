package com.cafecli.render.inline;

import com.cafecli.llm.LlmClient;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallBlockTest {

    /**
     * The call header carries the terminal's ordinary foreground, not the gray every other collapsed
     * header uses: it names the action the user asked for, and it is what they scan a block for.
     *
     * <p>This inverts what the first real-terminal pass over ADR 0017 found (issue 04). The header was
     * gray while the {@code … +N 行} footnote carried no style at all and so came out at the default
     * bright foreground — the footnote dominated the block and the command receded. Thinking keeps the
     * gray, which is why {@code ReasoningBlockTest} still asserts it.
     */
    @Test
    void theCallHeaderIsOrdinaryForegroundNotTheGrayOfAmbientHeaders() {
        Terminal terminal = Mockito.mock(Terminal.class);
        List<LlmClient.ToolCall> calls = List.of(
                call("web_search", "{\"query\":\"terminal rendering\"}"),
                call("web_fetch", "{\"url\":\"https://example.com/docs\"}"),
                call("search_code", "{\"query\":\"LiveRegionStrip\"}"),
                call("read_file", "{\"path\":\"src/Main.java\"}"),
                call("write_file", "{\"path\":\"notes.md\"}"),
                call("list_dir", "{\"path\":\"src\"}"),
                call("execute_command", "{\"command\":\"mvn test\"}"),
                call("create_project", "{\"name\":\"demo\"}"),
                call("save_memory", "{\"fact\":\"stable preference\"}"),
                call("mcp__github__search", "{}"),
                call("custom_tool", "{}"));
        StringBuilder rendered = new StringBuilder();

        for (LlmClient.ToolCall call : calls) {
            ToolCallBlock block = new ToolCallBlock(terminal, List.of(call));
            AttributedString collapsed = block.render(120, FoldState.COLLAPSED).getFirst();
            assertEquals(AttributedStyle.DEFAULT, collapsed.styleAt(0),
                    "the call header should read at ordinary brightness: " + collapsed);
            rendered.append(collapsed).append('\n');
            block.render(120, FoldState.EXPANDED).forEach(line -> rendered.append(line).append('\n'));
        }

        String labels = rendered.toString();
        assertFalse(containsEmoji(labels), labels);
        assertFalse(labels.contains("▎"), labels);
        assertTrue(labels.contains("ReadFile(src/Main.java)"), labels);
        assertFalse(labels.contains("ctrl+o to expand"), labels);
        assertFalse(labels.contains("collapse"), labels);
        assertTrue(labels.contains("读取 1 个文件"), labels);
        assertTrue(labels.contains("调用 MCP 工具 github.search"), labels);
        assertTrue(labels.contains("custom_tool × 1"), labels);
    }

    // --- issue 01: the caps, and the unit each of them is in (ADR 0017 D3) -----------------------

    /**
     * The result preview is capped in <b>visual</b> rows, so the same result shows a different number
     * of source lines at different widths while occupying the same three rows.
     *
     * <p>Source lines would be the wrong unit here and the difference is the whole point: a 400-char
     * grep hit is one source line and five rows of screen, and what the preview is spending is screen.
     * The call header above it is capped in source units instead, because it is an <b>identity</b> —
     * {@code Shell(mvn -q test)} should be the same thing at every width.
     */
    @Test
    void theResultPreviewIsCappedAtThreeVisualRowsAtEveryWidth() {
        Terminal terminal = Mockito.mock(Terminal.class);
        String result = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "result line %d ".formatted(i) + "x".repeat(40))
                .collect(java.util.stream.Collectors.joining("\n"));

        List<String> wide = previewRows(terminal, result, 120);
        List<String> narrow = previewRows(terminal, result, 40);

        assertEquals(3, wide.size(), wide.toString());
        assertEquals(3, narrow.size(), narrow.toString());
        assertTrue(wide.stream().allMatch(row -> row.length() <= 120), wide.toString());
        assertTrue(narrow.stream().allMatch(row -> row.length() <= 40), narrow.toString());
        // Fewer source lines fit into three rows once they wrap, so the overflow count moves with the
        // width. That is intended (issue 01 接受的后果) — a retained entry is simply re-rendered.
        assertEquals(3, omittedSourceLines(terminal, result, 120), "wide preview");
        assertEquals(5, omittedSourceLines(terminal, result, 40), "narrow preview");
    }

    /** The character bound arrives first: one very long command line. */
    @Test
    void aVeryLongSingleLineCallHeaderStopsAtOneHundredSixtyCharacters() {
        Terminal terminal = Mockito.mock(Terminal.class);
        ToolCallBlock block = new ToolCallBlock(terminal,
                List.of(call("execute_command", "{\"command\":\"" + "a".repeat(300) + "\"}")));

        List<AttributedString> rows = block.render(400, FoldState.COLLAPSED);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals(160, rows.getFirst().length(), rows.toString());
        assertTrue(rows.getFirst().toString().startsWith("Shell(aaa"), rows.toString());
        assertTrue(rows.getFirst().toString().endsWith("…"), rows.toString());
    }

    /** The logical-line bound arrives first: a short three-line heredoc. */
    @Test
    void aMultiLineCallHeaderStopsAtTwoLogicalLines() {
        Terminal terminal = Mockito.mock(Terminal.class);
        ToolCallBlock block = new ToolCallBlock(terminal, List.of(call("execute_command",
                "{\"command\":\"cat <<'EOF'\\nthe second line\\nEOF\"}")));

        List<AttributedString> rows = block.render(400, FoldState.COLLAPSED);

        assertEquals(2, rows.size(), rows.toString());
        assertEquals("Shell(cat <<'EOF'", rows.get(0).toString());
        assertEquals("the second line…", rows.get(1).toString());
    }

    /**
     * The call header outranks the overflow footnote in brightness — the ordering, not the two styles
     * separately.
     *
     * <p>Asserting the relationship is the point. Both halves were "correct" in isolation before the
     * first real-terminal pass (issue 04): the header was deliberately gray, and the footnote was
     * simply never styled. Nothing anywhere compared them, so the block rendered with its least
     * important row the brightest and no test could notice.
     */
    @Test
    void theCallHeaderReadsBrighterThanTheOverflowFootnote() {
        Terminal terminal = Mockito.mock(Terminal.class);
        ToolCallBlock block = new ToolCallBlock(terminal,
                List.of(call("read_file", "{\"path\":\"README.md\"}")));
        block.completeWith(List.of(execution("read_file",
                java.util.stream.IntStream.rangeClosed(1, 40)
                        .mapToObj("body line %d"::formatted)
                        .collect(java.util.stream.Collectors.joining("\n")))));

        List<AttributedString> rows = block.render(120, FoldState.COLLAPSED);
        AttributedString header = rows.getFirst();
        AttributedString footnote = rows.getLast();

        assertTrue(footnote.toString().contains("ctrl+o 查看全部"), rows.toString());
        assertEquals(AttributedStyle.DEFAULT, header.styleAt(0), "header: " + header);
        assertNotEquals(AttributedStyle.DEFAULT, footnote.styleAt(0), "footnote: " + footnote);
    }

    /** The [[转写浏览层]] expands the whole result; there is no second cap there (D7). */
    @Test
    void theOverlayExpandsTheWholeResult() {
        Terminal terminal = Mockito.mock(Terminal.class);
        ToolCallBlock block = new ToolCallBlock(terminal,
                List.of(call("read_file", "{\"path\":\"README.md\"}")));
        block.completeWith(List.of(execution("read_file",
                java.util.stream.IntStream.rangeClosed(1, 40)
                        .mapToObj("body line %d"::formatted)
                        .collect(java.util.stream.Collectors.joining("\n")))));

        String expanded = block.renderRetained(120, FoldState.EXPANDED).stream()
                .map(AttributedString::toString)
                .collect(java.util.stream.Collectors.joining("\n"));

        assertTrue(expanded.contains("body line 1"), expanded);
        assertTrue(expanded.contains("body line 40"), expanded);
        assertFalse(expanded.contains("ctrl+o 查看全部"), expanded);
    }

    // --- issue 02: for a file-writing tool the diff IS the result view (ADR 0017 D4) --------------

    /**
     * The cap follows the <b>result's type</b>, so a diff inside a tool block keeps the bound it always
     * had — 100 <b>source</b> lines and the same overflow line — instead of being squeezed into the
     * three visual rows a text result gets, and instead of being an exception written into the text
     * rule. Two halves of one rule, not a rule plus a special case.
     */
    @Test
    void aDiffResultKeepsItsHundredSourceLineCapInsteadOfTheThreeRowTextCap() {
        Terminal terminal = Mockito.mock(Terminal.class);
        String after = java.util.stream.IntStream.rangeClosed(1, 150)
                .mapToObj("line %d"::formatted)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
        ToolCallBlock block = new ToolCallBlock(terminal,
                List.of(call("write_file", "{\"path\":\"big.txt\"}")));
        block.attachDiff(new DiffBlock(terminal, "big.txt", null, after));
        block.completeWith(List.of(execution("write_file", "{\"path\":\"big.txt\"}",
                "文件已写入: big.txt")));

        List<String> rows = rendered(block, 120, FoldState.COLLAPSED);

        assertEquals(1, count(rows, "Update big.txt  +150 -0"), rows.toString());
        assertEquals(1, count(rows, "+ line 100"), rows.toString());
        assertEquals(0, count(rows, "+ line 101"),
                "the diff's own 100-source-line cap decides, not the text preview's three rows: "
                        + rows);
        assertEquals(1, count(rows, "… +50 行 (ctrl+o 查看全部)"), rows.toString());
    }

    /**
     * A write's text summary is dropped because its diff <b>arrived</b>, not because of what tool it
     * was. The control is the same call whose write failed: no observer fires for it, so there is no
     * diff, and dropping {@code "写入文件失败: …"} as well would leave a call header with nothing at
     * all under it.
     */
    @Test
    void aWritesTextSummaryIsDroppedOnlyWhenItsDiffActuallyArrived() {
        Terminal terminal = Mockito.mock(Terminal.class);
        String args = "{\"path\":\"notes.md\"}";

        ToolCallBlock wrote = new ToolCallBlock(terminal, List.of(call("write_file", args)));
        wrote.attachDiff(new DiffBlock(terminal, "notes.md", null, "alpha\n"));
        wrote.completeWith(List.of(execution("write_file", args, "文件已写入: notes.md")));

        ToolCallBlock failed = new ToolCallBlock(terminal, List.of(call("write_file", args)));
        failed.completeWith(List.of(execution("write_file", args, "写入文件失败: 磁盘只读")));

        List<String> succeeded = rendered(wrote, 120, FoldState.COLLAPSED);
        assertEquals(1, count(succeeded, "Update notes.md"), succeeded.toString());
        assertEquals(0, count(succeeded, "文件已写入"),
                "the diff underneath says this already: " + succeeded);

        List<String> unwritten = rendered(failed, 120, FoldState.COLLAPSED);
        assertEquals(0, count(unwritten, "Update notes.md"), unwritten.toString());
        assertEquals(1, count(unwritten, "写入文件失败: 磁盘只读"),
                "a write with no diff must keep the only thing it has to say: " + unwritten);
    }

    /** The [[转写浏览层]] expands the whole diff too, with no second cap (D7). */
    @Test
    void theOverlayExpandsTheWholeDiffAsWell() {
        Terminal terminal = Mockito.mock(Terminal.class);
        String after = java.util.stream.IntStream.rangeClosed(1, 150)
                .mapToObj("line %d"::formatted)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
        ToolCallBlock block = new ToolCallBlock(terminal,
                List.of(call("write_file", "{\"path\":\"big.txt\"}")));
        block.attachDiff(new DiffBlock(terminal, "big.txt", null, after));
        block.completeWith(List.of(execution("write_file", "{\"path\":\"big.txt\"}",
                "文件已写入: big.txt")));

        List<String> rows = rendered(block, 120, FoldState.EXPANDED);

        assertEquals(1, count(rows, "+ line 150"), rows.toString());
        assertEquals(0, count(rows, "ctrl+o 查看全部"), rows.toString());
    }

    private static List<String> rendered(ToolCallBlock block, int width, FoldState foldState) {
        return block.renderRetained(width, foldState).stream()
                .map(AttributedString::toString)
                .toList();
    }

    private static int count(List<String> rows, String needle) {
        return (int) rows.stream().filter(row -> row.contains(needle)).count();
    }

    // --- issue 03: the running command tail (ADR 0017 D5) ----------------------------------------

    /** One running command owns the whole budget, and it shows the <b>newest</b> five lines. */
    @Test
    void aRunningCommandTailKeepsOnlyItsLastFiveNonEmptyLines() {
        ToolCallBlock block = shellBlock();
        for (int i = 1; i <= 8; i++) {
            block.appendRunningOutput("mvn -q test", "step " + i);
            block.appendRunningOutput("mvn -q test", "   ");
        }

        List<String> rows = rowsOf(block, 120);

        assertEquals(List.of("Shell(mvn -q test)",
                        "  step 4", "  step 5", "  step 6", "  step 7", "  step 8"),
                rows, "the tail is the last five non-empty lines, blanks dropped");
    }

    /**
     * Tail rows are <b>clipped</b>, never wrapped: a command that prints one 400-character line must
     * spend one row of the budget, not five. (The completed result wraps instead — there the visual
     * row is the unit precisely because a long line is worth the screen.)
     */
    @Test
    void aRunningTailRowIsClippedSoOneOutputLineIsAlwaysOneRow() {
        ToolCallBlock block = shellBlock();
        block.appendRunningOutput("mvn -q test", "x".repeat(400));

        List<String> rows = rowsOf(block, 40);

        assertEquals(2, rows.size(), rows.toString());
        assertEquals(40, rows.get(1).length(), rows.toString());
        assertTrue(rows.get(1).endsWith("…"), rows.get(1));
    }

    /**
     * Concurrent commands share the five rows: four (the default parallelism) get one each. The
     * budget belongs to the [[活区尾]], not to each command, because what is spent is one screen.
     */
    @Test
    void concurrentCommandsShareTheBudgetOneRowEach() {
        ToolCallBlock block = new ToolCallBlock(Mockito.mock(Terminal.class), List.of(
                call("execute_command", "{\"command\":\"a\"}"),
                call("execute_command", "{\"command\":\"b\"}"),
                call("execute_command", "{\"command\":\"c\"}"),
                call("execute_command", "{\"command\":\"d\"}")));
        for (String command : List.of("a", "b", "c", "d")) {
            for (int i = 1; i <= 3; i++) {
                block.appendRunningOutput(command, command + i);
            }
        }

        List<String> tail = rowsOf(block, 120).stream().filter(row -> row.startsWith("  ")).toList();

        assertEquals(List.of("  a3", "  b3", "  c3", "  d3"), tail, tail.toString());
    }

    /**
     * More concurrent commands than the budget has rows — left unspecified by ADR 0017 D5, decided
     * here: the commands that started printing first keep their row and the rest wait, rather than
     * every command getting a row and the budget bursting. Reachable because a batch may hold more
     * calls than the parallelism runs at once.
     */
    @Test
    void moreCommandsThanRowsLeavesTheBudgetIntact() {
        ToolCallBlock block = shellBlock();
        for (int i = 1; i <= 7; i++) {
            block.appendRunningOutput("command-" + i, "printed by " + i);
        }

        List<String> tail = rowsOf(block, 120).stream().filter(row -> row.startsWith("  ")).toList();

        assertEquals(5, tail.size(), tail.toString());
        assertEquals("  printed by 1", tail.getFirst(), tail.toString());
        assertEquals("  printed by 5", tail.getLast(), tail.toString());
    }

    /**
     * The result <b>replaces</b> the tail rather than being appended to it, and a completed block
     * never grows a tail again — it is frozen scrollback from then on, and lines that arrive late
     * (a reader thread still draining a killed process) change nothing.
     */
    @Test
    void theRunningTailIsReplacedWhenTheResultsArrive() {
        ToolCallBlock block = shellBlock();
        block.appendRunningOutput("mvn -q test", "step 1");
        block.completeWith(List.of(execution("execute_command", "命令执行超时（120秒），已强制终止")));
        block.appendRunningOutput("mvn -q test", "printed after the kill");

        List<String> rows = rowsOf(block, 120);

        assertEquals(List.of("Shell(mvn -q test)", "  命令执行超时（120秒），已强制终止"), rows,
                rows.toString());
    }

    private static ToolCallBlock shellBlock() {
        return new ToolCallBlock(Mockito.mock(Terminal.class),
                List.of(call("execute_command", "{\"command\":\"mvn -q test\"}")));
    }

    private static List<String> rowsOf(ToolCallBlock block, int width) {
        return block.render(width, FoldState.COLLAPSED).stream()
                .map(AttributedString::toString)
                .toList();
    }

    /** Everything between the one-row call header and the overflow line: the preview itself. */
    private static List<String> previewRows(Terminal terminal, String result, int width) {
        List<String> rows = completed(terminal, result).renderRetained(width, FoldState.COLLAPSED)
                .stream()
                .map(AttributedString::toString)
                .toList();
        assertTrue(rows.getFirst().startsWith("ReadFile("), rows.toString());
        assertTrue(rows.getLast().contains("ctrl+o 查看全部"), rows.toString());
        return rows.subList(1, rows.size() - 1);
    }

    private static int omittedSourceLines(Terminal terminal, String result, int width) {
        String overflow = completed(terminal, result).renderRetained(width, FoldState.COLLAPSED)
                .stream()
                .map(AttributedString::toString)
                .filter(row -> row.contains("ctrl+o 查看全部"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no overflow line at width " + width));
        return Integer.parseInt(overflow.replaceAll("\\D+", " ").strip().split(" ")[0]);
    }

    private static ToolCallBlock completed(Terminal terminal, String result) {
        ToolCallBlock block = new ToolCallBlock(terminal,
                List.of(call("read_file", "{\"path\":\"README.md\"}")));
        block.completeWith(List.of(execution("read_file", result)));
        return block;
    }

    private static com.cafecli.tool.ToolRegistry.ToolExecutionResult execution(String name,
                                                                               String text) {
        return execution(name, "{}", text);
    }

    private static com.cafecli.tool.ToolRegistry.ToolExecutionResult execution(String name,
                                                                               String args,
                                                                               String text) {
        return new com.cafecli.tool.ToolRegistry.ToolExecutionResult(name, name, args,
                com.cafecli.tool.ToolOutput.text(text), 3, false);
    }

    private static LlmClient.ToolCall call(String name, String arguments) {
        return new LlmClient.ToolCall(name, new LlmClient.ToolCall.Function(name, arguments));
    }

    private static boolean containsEmoji(String text) {
        return text.codePoints().anyMatch(codePoint ->
                codePoint >= 0x1F300
                        || codePoint >= 0x2600 && codePoint <= 0x27BF
                        || codePoint == 0xFE0F);
    }
}
