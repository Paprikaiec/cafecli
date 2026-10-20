package com.cafecli.render.inline;

import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.inline.replay.TerminalReplayRecorder;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 *   java -cp target/cafecli-1.0.0.jar com.cafecli.render.inline.RenderReproDriver
 *   # control run — isolates the status scroll region as the big-gap cause:
 *   CAFECLI_NO_STATUSBAR=true java -cp target/cafecli-1.0.0.jar com.cafecli.render.inline.RenderReproDriver
 *   # opt-in deterministic resize recording (normally driven by scripts/resize-storm.sh):
 *   java -cp target/cafecli-1.0.0.jar com.cafecli.render.inline.RenderReproDriver \
 *       --storm=60 --trace=/tmp/cafecli-resize.jsonl
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
    /** Stable boundary shared with resize-storm.sh; replay/truth comparisons start at this text. */
    static final String REPLAY_START_MARKER = "[cafecli-replay-start:v1]";

    private RenderReproDriver() {
    }

    public static void main(String[] args) throws Exception {
        DriverOptions options = options(args);
        try (Terminal terminal = TerminalCapabilities.systemTerminalBuilder().build()) {
            if (options.stormSeconds() > 0) {
                if (options.tracePath() == null) {
                    runResizeStormTarget(terminal, options.stormSeconds(), null, options.captureId(),
                            null, null);
                } else {
                    TerminalReplayRecorder recorder = TerminalReplayRecorder.create(options.tracePath(), terminal);
                    try (recorder) {
                        runResizeStormTarget(terminal, options.stormSeconds(), recorder, options.captureId(),
                                options.captureRequest(), options.captureReady());
                    }
                    if (recorder.writeFailure().isPresent()) {
                        throw recorder.writeFailure().get();
                    }
                }
                return;
            }
            // 与 Main 同一条建构路径：LineReaderBuilder 不可用于子类，故手工构造 + 逐项 setter；
            // 能力不足的终端退回原生行编辑器（ADR 0014 决策 2 的降级路径）。
            // variables 只能走构造器（ADR 0014；AMBIGUOUS_BINDING 是第二例，见 ADR 0016 D2）：
            // 不设的话 ESC 要等 JLine 的 1000ms 默认才触发，手测时的取消手感与产品不一致。
            java.util.Map<String, Object> readerVariables = new java.util.HashMap<>();
            readerVariables.put(LineReader.AMBIGUOUS_BINDING, 100L);
            LineReader lineReader = CafeCliLineReader.supported(terminal)
                    ? CafeCliLineReader.create(terminal, "cafecli-repro", readerVariables,
                            null, null, null)
                    : LineReaderBuilder.builder().terminal(terminal)
                            .variables(readerVariables).build();
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

    private static DriverOptions options(String[] args) {
        long stormSeconds = 0;
        Path tracePath = null;
        Path captureRequest = null;
        Path captureReady = null;
        String captureId = null;
        for (String arg : args) {
            if (arg.equals("--storm")) {
                stormSeconds = 30;
            }
            if (arg.startsWith("--storm=")) {
                try {
                    stormSeconds = Long.parseLong(arg.substring("--storm=".length()));
                } catch (NumberFormatException e) {
                    stormSeconds = 30;
                }
            }
            if (arg.startsWith("--trace=")) {
                String value = arg.substring("--trace=".length());
                if (value.isBlank()) {
                    throw new IllegalArgumentException("--trace requires a non-empty output path");
                }
                tracePath = Path.of(value).toAbsolutePath().normalize();
            }
            if (arg.startsWith("--capture-id=")) {
                captureId = arg.substring("--capture-id=".length());
                if (captureId.isBlank() || captureId.indexOf('\n') >= 0 || captureId.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("--capture-id requires a non-empty single-line value");
                }
            }
            if (arg.startsWith("--capture-request=")) {
                captureRequest = requiredPathArgument(arg, "--capture-request=");
            }
            if (arg.startsWith("--capture-ready=")) {
                captureReady = requiredPathArgument(arg, "--capture-ready=");
            }
        }
        if (tracePath != null && stormSeconds <= 0) {
            throw new IllegalArgumentException("--trace is only supported with --storm");
        }
        if ((captureRequest == null) != (captureReady == null)) {
            throw new IllegalArgumentException("--capture-request and --capture-ready must be used together");
        }
        if (captureRequest != null && tracePath == null) {
            throw new IllegalArgumentException("capture endpoint control requires --trace");
        }
        return new DriverOptions(stormSeconds, tracePath, captureId, captureRequest, captureReady);
    }

    private static Path requiredPathArgument(String argument, String prefix) {
        String value = argument.substring(prefix.length());
        if (value.isBlank()) {
            throw new IllegalArgumentException(prefix.substring(0, prefix.length() - 1)
                    + " requires a non-empty path");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private record DriverOptions(long stormSeconds,
                                 Path tracePath,
                                 String captureId,
                                 Path captureRequest,
                                 Path captureReady) {
    }

    /**
     * 缩放风暴的<b>被测目标</b>：铺满一屏转写，然后把活区停在最容易出事的形态上——spinner + 多行
     * 流式思考预览 + 输入行 + 状态坞——并保持 N 秒，等 {@code scripts/resize-storm.sh} 从外部改窗口尺寸。
     *
     * <p>为什么要专门做一个模式：截图里的副本全都长成「spinner + 几行思考预览」，而这恰恰是活区**最高**
     * 的时刻（预览有好几行，其中不少是接近整行宽的 CJK）。只有 spinner + 状态坞的形态高度只有 3 行，
     * 缩放时活区几乎不长高，掩盖掉一整类擦除错误。这里用 CJK 文本也是刻意的：它按 wcwidth 占 2 列，
     * 行宽的取整边界与 ASCII 不同。
     *
     * <p>不接 LLM、不读输入、跑完自己退出，所以脚本可以无人值守地反复跑。
     */
    private static void runResizeStormTarget(Terminal terminal,
                                             long seconds,
                                             TerminalReplayRecorder recorder,
                                             String captureId,
                                             Path captureRequest,
                                             Path captureReady) throws Exception {
        LineReader lineReader = CafeCliLineReader.supported(terminal)
                ? CafeCliLineReader.create(terminal, "cafecli-storm", new java.util.HashMap<>(),
                        null, null, null)
                : LineReaderBuilder.builder().terminal(terminal).build();
        try (InlineRenderer renderer = recorder == null
                ? new InlineRenderer(terminal)
                : new InlineRenderer(terminal, recorder)) {
            renderer.bindLineReader(lineReader);
            renderer.start();
            renderer.updateStatus(StatusInfo.idle(MODEL, CONTEXT_WINDOW, false)
                    .withEnvironment("1", "1"));

            // Terminal.app history also contains shell launch noise. This first app-owned line is a
            // stable exact-comparison boundary and is deliberately present in the recorded byte stream.
            renderer.stream().println(REPLAY_START_MARKER
                    + (captureId == null ? "" : " " + captureId));

            // 先铺满一屏：真实会话里活区永远贴着屏幕底部，而空屏上的活区贴着顶部，缩放行为完全不同。
            for (int i = 0; i < 60; i++) {
                renderer.stream().println("transcript line " + i
                        + " —— 这一行用来把屏幕铺满，好让活区贴住底边。");
            }

            renderer.beginTurn();
            renderer.updateStatus(StatusInfo.active(MODEL, CONTEXT_WINDOW, false, "react")
                    .withEnvironment("1", "1"));
            renderer.beginThinking("Thinking");
            String[] thoughts = {
                "现在我已经有足够的内容来回答用户的问题了。让我也看看 ADR 文档，特别是否有关 resize 的讨论。",
                "关键机制总结：",
                "1. **`hardTerminated`** — 给每一行补 `\\n`，让 JLine 用真换行而不是自动折行接行。",
                "2. **[[destructive resize reset]]** — 尺寸一变就擦掉屏幕与终端保存的回卷，再按新尺寸重画。",
                "3. **状态坞按 width-1 排版** — 活区任何一行都不占终端最后一列。",
            };
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            int at = 0;
            if (captureRequest == null) {
                // Preserve the historical non-recording repro cadence exactly.
                while (System.currentTimeMillis() < deadline) {
                    renderer.appendThinking(thoughts[at % thoughts.length] + "\n");
                    at++;
                    sleep(1500);
                }
                renderer.endThinking();
                renderer.endTurn();
                return;
            }

            try (CaptureRequestWatcher capture = new CaptureRequestWatcher(captureRequest)) {
                while (System.currentTimeMillis() < deadline) {
                    renderer.appendThinking(thoughts[at % thoughts.length] + "\n");
                    at++;
                    long waitMillis = Math.min(1500L,
                            Math.max(1L, deadline - System.currentTimeMillis()));
                    if (capture.await(waitMillis)) {
                        completeReplayCapture(renderer, recorder, captureReady);
                        // The renderer and recorder are now inert, but the process deliberately remains
                        // attached so AppleScript can read this tab's unchanged history before terminating it.
                        new CountDownLatch(1).await();
                        return;
                    }
                }
            }
            throw new IllegalStateException("Replay capture request was not received before storm timeout: "
                    + captureRequest);
        }
    }

    /**
     * Stop all renderer writes, persist their exact recorder prefix, then publish the external history-
     * capture readiness signal. Publication is intentionally last: observing the ready file guarantees
     * that no later app byte can be submitted and that the trace already exists in its final form.
     */
    static void completeReplayCapture(InlineRenderer renderer,
                                      TerminalReplayRecorder recorder,
                                      Path readyFile) throws IOException {
        renderer.quiesceForReplayCapture();
        recorder.close();
        if (recorder.writeFailure().isPresent()) {
            throw recorder.writeFailure().get();
        }
        Path absoluteReady = readyFile.toAbsolutePath().normalize();
        Path parent = absoluteReady.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(absoluteReady, "ready\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Kernel-backed directory notification; no per-frame or millisecond filesystem polling. */
    static final class CaptureRequestWatcher implements AutoCloseable {
        private final Path request;
        private final WatchService watcher;
        private boolean requested;

        CaptureRequestWatcher(Path request) throws IOException {
            this.request = request.toAbsolutePath().normalize();
            Path parent = this.request.getParent();
            if (parent == null) {
                throw new IOException("Capture request has no parent directory: " + request);
            }
            watcher = FileSystems.getDefault().newWatchService();
            boolean registered = false;
            try {
                parent.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                registered = true;
                // Close the register/create race without recurring polling.
                requested = Files.exists(this.request);
            } finally {
                if (!registered) {
                    watcher.close();
                }
            }
        }

        boolean await(long timeoutMillis) throws IOException, InterruptedException {
            if (requested) {
                return true;
            }
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
            long deadline = System.nanoTime() + remainingNanos;
            while (remainingNanos > 0) {
                WatchKey key = watcher.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (key == null) {
                    return false;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW
                            || request.getFileName().equals(event.context())) {
                        requested = Files.exists(request);
                        if (requested) {
                            break;
                        }
                    }
                }
                if (!key.reset() && !requested) {
                    throw new IOException("Capture request directory is no longer watchable: "
                            + request.getParent());
                }
                if (requested) {
                    return true;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            watcher.close();
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
                tc("glob_files", "{\"path\":\"src/main/java/com/cafecli/render/inline\",\"pattern\":\"*.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/cafecli/render/inline/LiveRegionStrip.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/cafecli/render/inline/AnswerSegment.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/cafecli/render/inline/ActivityBlock.java\"}"),
                tc("read_file", "{\"path\":\"src/main/java/com/cafecli/render/inline/RenderLoop.java\"}")));
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
                + "└── PlainRenderer / InlineRenderer 各自 promptApproval()\n"
                + "```\n"
                + "\n"
                + "就这样——两种 renderer 复用同一套 approvedAll 决策。\n");
        renderer.appendDiff("ROADMAP.md",
                "# Cafecli ROADMAP\n\n## 里程碑\n- 已交付 21 期\n- TUI 渲染管线\n",
                "# Cafecli 路线图（v16.1 测试）\n\n## 里程碑\n- 已交付 22 期\n- TUI 渲染管线\n");
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

    private static ToolCallView tc(String name, String args) {
        return new ToolCallView(name + "-id", name, args);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
