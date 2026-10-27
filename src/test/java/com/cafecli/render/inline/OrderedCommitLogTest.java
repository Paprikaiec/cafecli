package com.cafecli.render.inline;

import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.ToolResultView;
import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Size;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordered commit log as a model (ADR 0010 amendment A8/A17).
 *
 * <p>Everything that reaches [[冻结回卷]] is registered here in write order, because native scrollback
 * is no longer durable: a [[destructive resize reset]] erases it, and whatever is not in the log cannot
 * be repainted. Coverage is therefore the product's memory, and the case that decides it is a
 * background task's completion notice printed while the user is looking elsewhere.
 *
 * <p>These assertions read the log directly — no terminal bytes, no screen model. That is deliberate:
 * "did this output enter the model, in this position" is a question about a list, and the four failed
 * rounds of resize work all passed byte assertions while the real terminal stayed broken.
 *
 * <p>Live-region content is absent by definition rather than by policy: the spinner, the approval card,
 * the [[输入框]] and the dock are never committed, so no exclusion list exists to be kept up to date.
 */
class OrderedCommitLogTest {

    private static final int WIDTH = 80;

    private static final class Rig implements AutoCloseable {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final PipedOutputStream keys;
        final TracingTerminal terminal;
        final ManualFramePump pump = new ManualFramePump();
        final InlineRenderer renderer;

        Rig() throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("commit-log", "xterm-256color", in, sink);
            terminal.setSize(new Size(WIDTH, 24));
            renderer = new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8),
                    pump, new ManualFrameTicker(), new ManualClock());
            renderer.start();
        }

        /** The committed log, in write order, as the rows a reset would repaint. */
        List<String> log() {
            return renderer.liveRegion().replayLogRows(WIDTH).stream()
                    .map(String::strip)
                    .filter(row -> !row.isEmpty())
                    .toList();
        }

        @Override
        public void close() throws Exception {
            renderer.close();
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            terminal.close();
        }
    }

    /**
     * The line editor that yields the brush, which is how the startup banner reaches the log: its
     * {@code printAbove} is intercepted and routed into the strip's commit queue (ADR 0014 决策 2).
     */
    private static CafeCliLineReader reader(TracingTerminal terminal) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(LineReader.INPUT_RC_FILE_NAME, "/nonexistent-cafecli-inputrc");
        return CafeCliLineReader.create(terminal, "commit-log", variables,
                new DefaultHistory(), null, null);
    }

    private static ToolCallView toolCall(String name, String arguments) {
        return new ToolCallView(name + "-id", name, arguments);
    }

    /**
     * Every source A8 names, in one ordered assertion.
     *
     * <p>They reach the log through exactly two seams and no third: the startup banner through the
     * intercepted {@code printAbove} that {@code installStartupScreen} hooks onto JLine's
     * {@code CALLBACK_INIT}, and everything else — MCP and Skill summaries, slash-command results,
     * background-task completion notices — through {@code Renderer.stream()}, which is what those call
     * sites in {@code Main} / {@code SlashCommandExecutor} / the approval pump's pending notifier all
     * write to. Typed blocks are interleaved between them so the ordering assertion covers the
     * interleaving rather than two separate runs of same-kind items.
     */
    @Test
    void everyNamedSourceOfCommittedOutputEntersTheLogInWriteOrder() throws Exception {
        try (Rig rig = new Rig()) {
            InlineRenderer r = rig.renderer;
            CafeCliLineReader lineReader = reader(rig.terminal);
            r.bindLineReader(lineReader);
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));

            // 1. the startup banner, through the real CALLBACK_INIT hook JLine fires on first read
            r.installStartupScreen(List.of("Cafecli v16.1.0", "  model: glm-5.1"));
            lineReader.getWidgets().get(LineReader.CALLBACK_INIT).apply();
            rig.pump.pump();

            // 2. the MCP summary and 3. the Skill summary, as startup writes them
            r.stream().println("MCP: 2/2 servers ready, 14 tools");
            r.stream().println("Skill: 3 enabled of 7");
            rig.pump.pump();

            // 4. a typed block, so the interleaving is asserted and not just the streamed run
            var answer = r.beginAnswer();
            answer.append("Here is the plan.\n");
            answer.finish();
            rig.pump.pump();

            // 5. a slash-command result
            r.stream().println("/task  task_1 idle · task_2 running");
            rig.pump.pump();

            // 6. another typed block
            // A [[工具块]] enters the log when it completes, not when it is announced (ADR 0017 D2):
            // while it runs it is live-region content, so it is deliberately not in the log yet.
            r.appendToolCalls(List.of(toolCall("read_file", "{\"path\":\"README.md\"}")));
            r.appendToolResults(List.of(new ToolResultView(
                    "read_file-id", "read_file", "{}",
                    "")));
            rig.pump.pump();

            // 7. the case that decides the whole slice: a background task's completion notice
            r.stream().println("✅ task_2 完成：3 files updated");
            rig.pump.pump();

            List<String> log = rig.log();
            assertEquals(List.of(
                            "Cafecli v16.1.0",
                            "model: glm-5.1",
                            "MCP: 2/2 servers ready, 14 tools",
                            "Skill: 3 enabled of 7",
                            "Here is the plan.",
                            "/task  task_1 idle · task_2 running",
                            "ReadFile(README.md)",
                            "✅ task_2 完成：3 files updated"),
                    log,
                    "the ordered commit log must cover every committed source, in write order: " + log);
        }
    }

    /**
     * Live-region content is excluded by definition, not by policy. The spinner, the approval card, the
     * [[输入框]] and the dock are never committed to [[冻结回卷]] in the first place, so they cannot
     * reach the log — there is no filter here to be bypassed by a new call site.
     */
    @Test
    void liveRegionContentNeverEntersTheLog() throws Exception {
        try (Rig rig = new Rig()) {
            InlineRenderer r = rig.renderer;
            r.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> a half written draft")),
                    2, WIDTH, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.beginThinking("Thinking");
            rig.pump.pump();
            r.liveRegion().showApproval(new ApprovalBlock(rig.terminal,
                    com.cafecli.render.ApprovalRequest.of(
                            "write_file", "{\"path\":\"a.txt\"}", "test", "task_2")));
            var answer = r.beginAnswer();
            answer.append("committed prose\n");
            answer.finish();
            rig.pump.pump();
            rig.pump.pump();

            List<String> log = rig.log();
            assertTrue(log.contains("committed prose"), "committed prose must be in the log: " + log);
            for (String live : List.of("esc to cancel", "需要审批", "half written draft",
                    "Ctrl+Y", "Auto Model")) {
                assertTrue(log.stream().noneMatch(row -> row.contains(live)),
                        "live-region content must never reach the commit log ('" + live + "'): " + log);
            }
        }
    }
}
