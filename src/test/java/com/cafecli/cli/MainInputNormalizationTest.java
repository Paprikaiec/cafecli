package com.cafecli.cli;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainInputNormalizationTest {

    @Test
    void keepsMultilinePasteStructure() {
        String normalized = Main.prepareSeedBuffer("请把任务拆成可并行的 DAG:\n1. 读 pom.xml\r\n2. 列出 src/main/java");

        assertEquals("请把任务拆成可并行的 DAG:\n1. 读 pom.xml\n2. 列出 src/main/java", normalized);
    }

    @Test
    void keepsSingleLineInputUntouched() {
        String normalized = Main.prepareSeedBuffer("帮我读取 pom.xml");

        assertEquals("帮我读取 pom.xml", normalized);
    }

    @Test
    void normalizesLegacyCarriageReturnsWithoutChangingTextLayout() {
        String normalized = Main.prepareSeedBuffer("第一行\r第二行\r\n第三行");

        assertEquals("第一行\n第二行\n第三行", normalized);
    }

    @Test
    void startupHintsKeepSlashCommandDetailsOutOfInitialScreen() {
        List<String> hints = Main.startupHints();

        assertTrue(hints.stream().anyMatch(hint -> hint.contains("输入 '/' 后按 Tab 补全命令")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/model")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/index [路径]")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/skill list")));
    }

    @Test
    void startupBannerUsesOpenLayoutWithoutRightBorder() {
        List<String> lines = Main.startupBannerLines();

        assertTrue(lines.stream().anyMatch(line -> line.contains("Cafecli")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("freshly brewed agents")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("v1.0.0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("`----'")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Tips for getting started")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("@path")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("for shortcuts")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("────────────────")));
        assertTrue(lines.stream().noneMatch(line -> line.endsWith("║")),
                "banner should not depend on a padded right border");
    }

    @Test
    void startupBannerPaintsCoffeeCupGradientInsteadOfGreen() {
        List<String> lines = Main.startupBannerLines();
        List<String> cup = lines.stream()
                .filter(line -> line.contains("........") || line.contains("|      |]")
                        || line.contains("\\      /") || line.contains("`----'"))
                .toList();

        assertEquals(4, cup.size(), cup.toString());
        assertTrue(cup.stream().noneMatch(line -> line.contains("\u001B[32m")),
                "cup body should no longer be green: " + cup);
        if (com.cafecli.util.AnsiStyle.isEnabled()) {
            assertEquals(4, cup.stream().map(MainInputNormalizationTest::leadingStyle).distinct().count(),
                    "cup body should render four distinct gradient layers: " + cup);
        }
    }

    private static String leadingStyle(String line) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(?:\u001B\\[[;\\d]*m)+").matcher(line);
        return matcher.find() ? matcher.group() : "";
    }

    @Test
    void slashCommandTailTipsExposeCommandDescriptions() {
        var tips = Main.slashCommandTailTips();

        assertTrue(tips.containsKey("/model"));
        assertTrue(tips.get("/model").getMainDesc().get(0).toString().contains("查看当前模型"));
        assertTrue(tips.containsKey("/task"));
    }

    @Test
    void mcpStartupWaitCanBeTunedForTerminalSmoke() {
        String old = System.getProperty("paicli.mcp.startup.wait.seconds");
        try {
            System.setProperty("paicli.mcp.startup.wait.seconds", "2");

            assertEquals(Duration.ofSeconds(2), Main.mcpStartupWait());
        } finally {
            restoreProperty("paicli.mcp.startup.wait.seconds", old);
        }
    }

    @Test
    void submittedPromptIsRenderedBackIntoTranscript() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Main.printSubmittedPrompt(new PrintStream(sink, true, StandardCharsets.UTF_8), "  沉默王二是谁？  ");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("› "), emitted);
        assertTrue(emitted.contains("沉默王二是谁？"), emitted);
        assertTrue(emitted.endsWith("\n"), emitted);
        assertFalse(emitted.endsWith("\n\n"), emitted);
    }

    @Test
    void submittedSingleLinePromptDoesNotAddExtraBlankLine() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Main.printSubmittedPrompt(new PrintStream(sink, true, StandardCharsets.UTF_8), "你好啊");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertEquals(1, emitted.chars().filter(ch -> ch == '\n').count(), emitted);
        assertTrue(emitted.contains("› "), emitted);
        assertTrue(emitted.contains("你好啊"), emitted);
    }

    @Test
    void submittedSlashCommandIsRenderedBackIntoTranscript() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Main.printSubmittedInput(null, new PrintStream(sink, true, StandardCharsets.UTF_8), "/memory list");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("› "), emitted);
        assertTrue(emitted.contains("/memory list"), emitted);
        assertEquals(1, emitted.chars().filter(ch -> ch == '\n').count(), emitted);
    }

    @Test
    void configuresAwtHeadlessOnMac() {
        String oldOs = System.getProperty("os.name");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.clearProperty("java.awt.headless");

            Main.configureAwtForCli();

            assertEquals("true", System.getProperty("java.awt.headless"));
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("java.awt.headless", oldHeadless);
        }
    }

    @Test
    void doesNotForceAwtHeadlessOnNonMac() {
        String oldOs = System.getProperty("os.name");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("os.name", "Linux");
            System.clearProperty("java.awt.headless");

            Main.configureAwtForCli();

            assertFalse(System.getProperties().containsKey("java.awt.headless"));
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("java.awt.headless", oldHeadless);
        }
    }

    @Test
    void slashCommandHintsIncludeRagSlashCommands() {
        List<String> commands = Main.slashCommandHints().stream()
                .map(Main.SlashCommandHint::display)
                .toList();

        assertTrue(commands.contains("/index [路径]"));
        assertTrue(commands.contains("/search <查询>"));
        assertTrue(commands.contains("/graph <类名>"));
    }

    @Test
    void slashCommandChoicesAreRenderedDirectlyWithoutJLineConfirmationText() {
        String choices = Main.formatSlashCommandChoices(120);

        assertTrue(choices.contains("/model glm-5.1"), choices);
        assertTrue(choices.contains("/model glm-5v-turbo"), choices);
        assertTrue(choices.contains("/model step"), choices);
        assertTrue(choices.contains("/model kimi"), choices);
        assertTrue(choices.contains("/model freellmapi"), choices);
        assertTrue(choices.contains("/browser status"), choices);
        assertFalse(choices.contains("do you wish"), choices);
        assertTrue(choices.lines().count() < Main.slashCommandHints().size(),
                "choices should be compact multi-column output");
    }

    @Test
    void upArrowPrefillsLatestHistoryEntry() throws Exception {
        LineReader lineReader = newLineReader();
        History history = lineReader.getHistory();
        history.add("第一条");
        history.add("最近一条");

        assertEquals("最近一条", Main.seedBufferForHistoryNavigation(lineReader, "[A"));
    }

    @Test
    void downArrowKeepsPromptEmpty() throws Exception {
        LineReader lineReader = newLineReader();
        lineReader.getHistory().add("最近一条");

        assertEquals("", Main.seedBufferForHistoryNavigation(lineReader, "[B"));
    }

    @Test
    void applyingHitlModeKeepsSwitchableHandlerAndApprovalPumpInSync() {
        com.cafecli.hitl.ApprovalPump pump = new com.cafecli.hitl.ApprovalPump();
        com.cafecli.hitl.SwitchableHitlHandler handler =
                new com.cafecli.hitl.SwitchableHitlHandler(pump);

        Main.toggleHitl(handler, pump, false);

        assertFalse(handler.isEnabled());
        assertFalse(pump.isEnabled());

        Main.toggleHitl(handler, pump, true);

        assertTrue(handler.isEnabled());
        assertTrue(pump.isEnabled());
    }

    @Test
    void ctrlYWidgetTogglesHitlFromIdleInput() {
        LineReader reader = Mockito.mock(LineReader.class);
        java.util.Map<String, org.jline.reader.Widget> widgets = new java.util.HashMap<>();
        java.util.Map<String, org.jline.keymap.KeyMap<org.jline.reader.Binding>> keyMaps =
                new java.util.HashMap<>();
        keyMaps.put(LineReader.MAIN, new org.jline.keymap.KeyMap<>());
        keyMaps.put(LineReader.EMACS, new org.jline.keymap.KeyMap<>());
        keyMaps.put(LineReader.VIINS, new org.jline.keymap.KeyMap<>());
        Mockito.when(reader.getWidgets()).thenReturn(widgets);
        Mockito.when(reader.getKeyMaps()).thenReturn(keyMaps);
        java.util.concurrent.atomic.AtomicBoolean toggled = new java.util.concurrent.atomic.AtomicBoolean();

        Main.bindCtrlYToHitlToggle(reader, () -> toggled.set(true));
        assertTrue(widgets.get("cafecli-toggle-hitl").apply());

        assertTrue(toggled.get());
        Mockito.verify(reader).callWidget(LineReader.REDISPLAY);
        for (String map : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS)) {
            assertTrue(keyMaps.get(map).getBound(String.valueOf((char) 25))
                    instanceof org.jline.reader.Reference);
        }
    }

    /**
     * The enqueue-time notification does two things, and the second is new (ADR 0010 amendment A14):
     * it still announces a waiting approval for the user to drain with Enter, and it now also tells
     * the renderer to hand the screen back if the [[转写浏览层]] is holding the input thread.
     *
     * <p>The announcement is asserted <b>unchanged</b> on purpose. A background approval keeps its
     * existing notification and Enter-to-drain flow; the overlay closing is added beside it, not
     * instead of it — the notice is invisible under a fullscreen overlay, so closing is what makes it
     * readable rather than what replaces it.
     */
    @Test
    void aPendingApprovalIsStillAnnouncedAndAlsoHandsTheScreenBack() {
        com.cafecli.render.inline.InlineRenderer inline =
                Mockito.mock(com.cafecli.render.inline.InlineRenderer.class);
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        Mockito.when(inline.stream()).thenReturn(new java.io.PrintStream(sink));

        Main.notifyApprovalPending(inline, com.cafecli.hitl.ApprovalRequest.of(
                "edit_file", "{}", null, "task_5 · 后台调研", null));

        String announced = org.jline.utils.AttributedString.stripAnsi(
                sink.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(announced.contains("task_5 · 后台调研"), announced);
        assertTrue(announced.contains("需要审批"), announced);
        assertTrue(announced.contains("回车处理"), announced);
        Mockito.verify(inline).onApprovalPending();
    }

    /**
     * 可 grep 的验收（issue 07）：**输入 regime 里没有第二个按键消费者**。
     *
     * <p>{@code Main.java} 与 {@code InteractiveRepl.java} 里 {@code terminal.reader()} 与
     * {@code enterRawMode()} 出现零次。判据是「终端按键只有一个消费者」，不是「全仓库没有 raw mode」
     * ——[[转写浏览层]] / 斜杠 palette 按[[模态租借]]合法拥有终端，它们不在这条规则里。
     *
     * <p>断言的是源文件本身而不是行为，因为这条要防的正是**下一次**有人在装配或 REPL 里重新开一条
     * 读取通路：那种改动不会让任何行为断言变红，只会让两个消费者悄悄回来。
     */
    @Test
    void neitherTheAssemblyNorTheReplReadsTheTerminalItself() throws java.io.IOException {
        for (String file : List.of("cli/Main.java", "cli/InteractiveRepl.java", "app/SystemEntry.java", "app/Cli.java",
                "app/InteractiveEntry.java", "ui/InteractiveRepl.java")) {
            java.nio.file.Path source = java.nio.file.Path.of("src/main/java/com/cafecli").resolve(file);
            assertTrue(java.nio.file.Files.isRegularFile(source), "missing source: " + source);
            String text = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(text.contains("terminal.reader()"),
                    file + " must not read terminal keys: the resident line editor is the only consumer");
            assertFalse(text.contains("enterRawMode()"),
                    file + " must not take raw mode: modals own the terminal under a lease instead");
        }
    }

    @Test
    void transcriptCommandAppearsInSlashHelpAndCompletionHints() {
        assertTrue(Main.slashCommandHints().stream()
                .anyMatch(hint -> "/transcript".equals(hint.insertText())));
    }

    @Test
    void ctrlOIdleWidgetOpensTranscriptOverlayOnTheInputThread() {
        LineReader reader = Mockito.mock(LineReader.class);
        com.cafecli.render.inline.InlineRenderer inline =
                Mockito.mock(com.cafecli.render.inline.InlineRenderer.class);
        java.util.Map<String, org.jline.reader.Widget> widgets = new java.util.HashMap<>();
        java.util.Map<String, org.jline.keymap.KeyMap<org.jline.reader.Binding>> keyMaps =
                new java.util.HashMap<>();
        keyMaps.put(LineReader.MAIN, new org.jline.keymap.KeyMap<>());
        keyMaps.put(LineReader.EMACS, new org.jline.keymap.KeyMap<>());
        keyMaps.put(LineReader.VIINS, new org.jline.keymap.KeyMap<>());
        Mockito.when(reader.getWidgets()).thenReturn(widgets);
        Mockito.when(reader.getKeyMaps()).thenReturn(keyMaps);

        long inputThread = Thread.currentThread().threadId();
        Main.bindCtrlOToTranscript(reader, inline);
        assertTrue(widgets.get("cafecli-transcript-overlay").apply());

        Mockito.verify(inline).openTranscript();
        assertEquals(inputThread, Thread.currentThread().threadId());
        Mockito.verify(reader).callWidget(LineReader.REDISPLAY);
        for (String map : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS)) {
            assertTrue(keyMaps.get(map).getBound(String.valueOf((char) 15))
                    instanceof org.jline.reader.Reference);
            assertNull(keyMaps.get(map).getBound(String.valueOf((char) 20)));
        }
    }

    @Test
    void transcriptCommandOpensOverlayWithoutCreatingOrEchoingATurn() {
        com.cafecli.render.inline.InlineRenderer inline =
                Mockito.mock(com.cafecli.render.inline.InlineRenderer.class);
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/transcript");

        assertTrue(Main.handleTranscriptCommand(command, inline));

        Mockito.verify(inline).openTranscript();
        Mockito.verify(inline, Mockito.never()).beginTurn();
        Mockito.verify(inline, Mockito.never()).appendUserPrompt(Mockito.anyString());
    }

    /** {@code /transcript} has no idle gate either — the overlay is available at any time (A13). */
    @Test
    void transcriptCommandDuringAnAutonomousTurnOpensTheOverlay() {
        com.cafecli.render.inline.InlineRenderer inline =
                Mockito.mock(com.cafecli.render.inline.InlineRenderer.class);
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        Mockito.when(inline.stream()).thenReturn(new java.io.PrintStream(sink));

        assertTrue(Main.handleTranscriptCommand(CliCommandParser.parse("/transcript"), inline));

        Mockito.verify(inline).openTranscript();
        assertEquals("", sink.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static LineReader newLineReader() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();

        DefaultHistory history = new DefaultHistory();
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .build();
    }
}
