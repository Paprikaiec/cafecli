package com.paicli.render.inline;

import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.util.List;

/**
 * Dev-only visual reproduction harness for the inline TUI fixup wave. NOT wired into the product CLI.
 *
 * <p>Replays a canned ReAct turn (reasoning burst → answer with a heading + fenced tree + a file diff,
 * across two steps) through a REAL {@link InlineRenderer} with no Agent and no LLM, then drops to the
 * idle {@code *} prompt so the transcript overlay can be exercised. Because it runs on a real terminal
 * with a live JLine status dock, it reproduces the scroll-region-dependent symptoms the unit harness
 * ({@code RenderFixupReproTest}) cannot: the #5B big blank gap and the #3/#4 overlay scrolling.
 *
 * <pre>{@code
 *   mvn -q -DskipTests package
 *   java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.render.inline.RenderReproDriver
 *   # control run — isolates the status scroll region as the big-gap cause:
 *   PAICLI_NO_STATUSBAR=true java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.render.inline.RenderReproDriver
 * }</pre>
 *
 * <p>What to watch: (a) the big blank gap between frozen scrollback and the streaming tail during the
 * answer; (b) whether the reasoning shows once (live) then collapses, or lingers as a duplicate; then
 * at the {@code *} prompt press <b>Ctrl+O</b> and try the mouse wheel (#3) and scroll-down (#4). Type
 * {@code /replay} to run the turn again, {@code /exit} or Ctrl+D to quit.
 */
public final class RenderReproDriver {

    private static final String MODEL = "glm-5.1";
    private static final long CONTEXT_WINDOW = 200_000L;

    private RenderReproDriver() {
    }

    public static void main(String[] args) throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();
            InlineRenderer renderer = new InlineRenderer(terminal);
            renderer.bindLineReader(lineReader);
            renderer.start();
            bindCtrlOToTranscript(lineReader, renderer);

            renderer.stream().println(
                    "[repro] canned render turns — watch for the blank gap / duplicated thinking, "
                            + "then press Ctrl+O (wheel + scroll-down), /replay, or /exit.");
            replayTurns(renderer, 3);

            idleLoop(lineReader, renderer);
            renderer.close();
        }
    }

    private static void idleLoop(LineReader lineReader, InlineRenderer renderer) {
        while (true) {
            try {
                renderer.beforeInput();
                String line;
                try {
                    line = lineReader.readLine(renderer.inputPrompt(), renderer.inputRightPrompt(),
                            (org.jline.reader.MaskingCallback) null, null);
                } finally {
                    renderer.afterInput();
                }
                String trimmed = line == null ? "" : line.strip();
                if (trimmed.equals("/exit") || trimmed.equals("/quit")) {
                    return;
                }
                if (trimmed.equals("/replay")) {
                    replayTurns(renderer, 3);
                }
            } catch (UserInterruptException ignored) {
                // Ctrl+C — stay in the loop.
            } catch (EndOfFileException eof) {
                return;
            }
        }
    }

    /** Mirrors the essential Ctrl+O → transcript overlay binding from {@code Main} for the idle prompt. */
    private static void bindCtrlOToTranscript(LineReader lineReader, InlineRenderer renderer) {
        lineReader.getWidgets().put("repro-transcript-overlay", () -> {
            renderer.openTranscript();
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("repro-transcript-overlay");
        String ctrlO = String.valueOf((char) 15);
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlO);
            }
        }
    }

    /** Replays several full turns so the retained window (and thus the Ctrl+O overlay) spans pages. */
    private static void replayTurns(Renderer renderer, int count) {
        for (int turn = 1; turn <= count; turn++) {
            renderer.updateStatus(StatusInfo.active(MODEL, CONTEXT_WINDOW, false, "react"));
            replayTurn(renderer, turn);
        }
        renderer.updateStatus(StatusInfo.idle(MODEL, CONTEXT_WINDOW, false));
    }

    private static void replayTurn(Renderer renderer, int turn) {
        renderer.beginTurn();
        renderer.appendUserPrompt("(#" + turn + ") 读一下 render/inline 下的类，讲清楚 TUI 渲染管线");

        // Step 1: reasoning burst → short answer preamble → a batch of tool calls.
        String reasoning1 = "Now let me read the remaining key inline render components: ActivityBlock, "
                + "LiveRegionStrip, BottomStatusBar, FramePump, FrameTicker, TerminalFrameWriter, "
                + "RetainedWindow, and the various blocks (AnswerSegment, ToolCallBlock, DiffBlock, "
                + "CodeBlock, ApprovalBlock, ReasoningBlock), and TerminalCapabilities.";
        thinkThenPublish(renderer, reasoning1);
        streamAnswer(renderer, "Let me now read the remaining inline rendering components — the blocks, "
                + "frame infrastructure, and other key pieces:\n");
        renderer.appendToolCalls(List.of(
                tc("glob_files", "{\"path\":\"src/main/java/com/paicli/render/inline\",\"pattern\":\"*.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/paicli/render/inline/LiveRegionStrip.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/paicli/render/inline/AnswerSegment.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/paicli/render/inline/ActivityBlock.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/paicli/render/inline/RenderLoop.java\"}")));
        sleep(120);

        // Step 2: reasoning → long answer with a heading + fenced tree (the big-gap trigger) → a diff.
        thinkThenPublish(renderer, "Now I have a comprehensive understanding of the TUI rendering "
                + "pipeline. Let me also quickly sketch the HITL pipeline before answering.");
        streamAnswer(renderer, ""
                + "Now I have a comprehensive understanding of the TUI rendering pipeline. "
                + "Here is how the HITL approval pipeline is wired:\n"
                + "\n"
                + "## 八、HITL 管线\n"
                + "\n"
                + "```\n"
                + "RendererHitlHandler (implements HitlHandler)\n"
                + "├── 维护: approvedAllByTool / approvedAllByServer (ConcurrentHashSet)\n"
                + "├── requestApproval(request):\n"
                + "│   ├── 检查是否已全部放行 → 自动通过\n"
                + "│   └── 否则委托 renderer.promptApproval(request)\n"
                + "└── PlainRenderer / InlineRenderer / LanternaRenderer 各自 promptApproval()\n"
                + "```\n"
                + "\n"
                + "就这样——三种 renderer 复用同一套 approvedAll 决策。\n");
        renderer.appendDiff("ROADMAP.md",
                "# PaiCLI ROADMAP\n\n## 里程碑\n- 已交付 21 期\n- TUI 渲染管线\n",
                "# PaiCLI 路线图（v16.1 测试）\n\n## 里程碑\n- 已交付 22 期\n- TUI 渲染管线\n");
        sleep(80);
        renderer.endTurn();
    }

    private static void thinkThenPublish(Renderer renderer, String reasoning) {
        renderer.beginThinking("Thinking");
        for (String chunk : chunks(reasoning, 24)) {
            renderer.appendThinking(chunk);
            sleep(30);
        }
        renderer.endThinking();
        Renderer.BlockWriter block = renderer.beginReasoning();
        block.append(reasoning);
        block.finish();
    }

    private static void streamAnswer(Renderer renderer, String answer) {
        Renderer.BlockWriter block = renderer.beginAnswer();
        for (String chunk : chunks(answer, 12)) {
            block.append(chunk);
            sleep(25);
        }
        block.finish();
    }

    private static List<String> chunks(String text, int size) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
