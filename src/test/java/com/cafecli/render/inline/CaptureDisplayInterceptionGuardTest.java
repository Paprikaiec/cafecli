package com.cafecli.render.inline;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-C 常驻守卫（ADR 0014）：钉住「行编辑器交出画笔」赖以成立的 JLine 4.0.0 假设。
 *
 * <p>由 Wave 0 spike（{@code com.cafecli.spike}）升级而来，断言不是「零字节」而是三条：
 * <ol>
 *   <li>全栈扫描下，一次完整 {@code readLine} 期间由 {@code Display.update} 产生的终端写入为 <b>0</b>；</li>
 *   <li>{@code super.redisplay()} 返回后仍持有截获 {@code Display}（{@code doDisplay()} 第四调用点守卫）；</li>
 *   <li>残留写入集合仍等于已枚举的终端**模式位**清单——集合变大即 JLine 行为变了。</li>
 * </ol>
 * 并静态钉住 ADR 0014 列出的**全部六个**版本耦合点，而不只是 {@code Display.update} 一条。
 *
 * <p>驱动内容覆盖真实编辑动作：打字、退格、Ctrl-U、Tab 补全菜单、多行缓冲、非空右提示符、
 * 编辑中 WINCH、历史召回、accept-line。
 */
class CaptureDisplayInterceptionGuardTest {

    // ------------------------------------------------------------------ rig

    private static final class Rig implements AutoCloseable {
        final TracingTerminal terminal;
        final PipedOutputStream keys;

        Rig(int cols, int rows) throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            this.keys = new PipedOutputStream(in);
            this.terminal = new TracingTerminal("guard", "xterm-256color", in, new ByteArrayOutputStream());
            this.terminal.setSize(new Size(cols, rows));
        }

        void send(String s) throws Exception {
            keys.write(s.getBytes(StandardCharsets.UTF_8));
            keys.flush();
            Thread.sleep(160);
        }

        @Override
        public void close() throws Exception {
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            terminal.close();
        }
    }

    private static CafeCliLineReader newReader(Terminal terminal) {
        Map<String, Object> vars = new HashMap<>();
        // 不要读开发者本机的 ~/.inputrc。variables 只能走构造器。
        vars.put(LineReader.INPUT_RC_FILE_NAME, "/nonexistent-cafecli-inputrc");
        CafeCliLineReader reader = CafeCliLineReader.create(terminal, "guard", vars,
                new DefaultHistory(),
                new StringsCompleter("foobar", "foobaz", "fooqux"),
                new org.jline.reader.impl.DefaultHighlighter());
        DefaultParser parser = new DefaultParser();
        parser.setEofOnUnclosedQuote(true);
        reader.setParser(parser);
        reader.option(LineReader.Option.HISTORY_INCREMENTAL, false);
        reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "%M> ");
        return reader;
    }

    /** 写入是否由行编辑器自己的 {@code Display.update} 产生（全栈扫描，不看栈顶）。 */
    private static boolean fromReaderDisplayUpdate(TracingWriter.WriteRecord r) {
        return r.fullStack.contains("org.jline.utils.Display.update");
    }

    // ------------------------------------------------- (a) + (b) + drive

    @Test
    void aFullReadLineDriveProducesZeroDisplayWritesAndKeepsTheCaptureInstalled() throws Exception {
        try (Rig rig = new Rig(80, 24)) {
            CafeCliLineReader reader = newReader(rig.terminal);
            List<CaptureDisplay.Frame> frames = new CopyOnWriteArrayList<>();
            reader.capture().setFrameListener(frames::add);
            TracingWriter tracer = rig.terminal.tracer();
            tracer.reset();

            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread readThread = new Thread(() -> {
                try {
                    result.set(reader.readLine("guard> ", "[RIGHT]",
                            (org.jline.reader.MaskingCallback) null, null));
                } catch (Throwable e) {
                    error.set(e);
                }
            }, "guard-readline");
            readThread.setDaemon(true);
            readThread.start();
            Thread.sleep(400);
            // history 在 readLine 起始的 attach 之后追加，才是 up-line-or-history 看得到的那一份
            reader.getHistory().add("recalled-history-entry");

            // 打字 / 退格 / Ctrl-U
            rig.send("hel");
            assertTrue(lastPlain(frames).stream().anyMatch(l -> l.contains("guard> hel")),
                    "typed text must reach the capture frame: " + lastPlain(frames));
            rig.send("\u007f");
            assertTrue(lastPlain(frames).stream().anyMatch(l -> l.contains("guard> he")),
                    "backspace must reach the capture frame: " + lastPlain(frames));
            rig.send("\u0015"); // Ctrl+U
            assertTrue(lastPlain(frames).stream().noneMatch(l -> l.contains("he")),
                    "Ctrl+U must clear the buffer in the capture frame: " + lastPlain(frames));

            // 右提示符
            assertTrue(lastPlain(frames).stream().anyMatch(l -> l.contains("[RIGHT]")),
                    "right prompt must reach the capture frame: " + lastPlain(frames));

            // 历史召回（上箭头）
            rig.send("\u001bOA");
            assertTrue(lastPlain(frames).stream().anyMatch(l -> l.contains("recalled-history-entry")),
                    "history recall must reach the capture frame: " + lastPlain(frames));
            rig.send("\u0015");

            // Tab 补全候选菜单
            frames.clear();
            rig.send("foo");
            rig.send("\t");
            assertTrue(frames.stream().anyMatch(f -> f.plain().stream()
                            .anyMatch(l -> l.contains("foobar") || l.contains("fooqux"))),
                    "completion menu must reach the capture frame: " + frames.stream()
                            .map(CaptureDisplay.Frame::plain).toList());
            rig.send("\u0015");

            // 多行缓冲（未闭合引号 → 次级提示符）
            rig.send("\"abc");
            rig.send("\r");
            rig.send("def");
            assertTrue(lastPlain(frames).size() >= 2,
                    "multi-line buffer must reach the capture frame as several rows: " + lastPlain(frames));

            // 编辑中 WINCH
            rig.terminal.setSize(new Size(60, 20));
            rig.terminal.raise(Terminal.Signal.WINCH);
            Thread.sleep(300);
            rig.send("g");
            assertSame(reader.capture(), reader.currentDisplay(),
                    "a WINCH must not leave a stock Display installed");

            // 光标移动
            rig.send("\u0002\u0002"); // Ctrl+B x2
            CaptureDisplay.Frame moved = reader.capture().lastFrame();
            assertTrue(moved.cursorPos() >= 0, "the capture frame must carry a real cursor position");

            rig.send("\""); // 闭合引号
            rig.send("\r");
            readThread.join(4000);

            assertNotNull(result.get(), "readLine must return; error=" + error.get());
            assertTrue(result.get().contains("abc"), "accept-line result: " + result.get());

            // (a) 全栈扫描：Display.update 贡献的终端写入为 0
            List<TracingWriter.WriteRecord> records = tracer.records();
            List<TracingWriter.WriteRecord> displayWrites = records.stream()
                    .filter(CaptureDisplayInterceptionGuardTest::fromReaderDisplayUpdate)
                    .toList();
            assertEquals(List.of(), displayWrites.stream().map(Object::toString).toList(),
                    "Display.update must contribute zero terminal writes during a full readLine");

            // (b) super.redisplay() 返回后仍持有截获 Display
            assertEquals(0, reader.captureSwapsAfterRedisplay(),
                    "the capture Display was swapped out during super.redisplay(): "
                            + "JLine moved doDisplay()'s call site");
            assertSame(reader.capture(), reader.currentDisplay());

            // (c) 残留写入只剩已枚举的终端模式位
            Set<String> residual = classifyResidualWrites(records);
            assertEquals(EXPECTED_RESIDUAL_KINDS, residual,
                    "residual write set changed — JLine's non-Display terminal writes moved; "
                            + "raw sample: " + records.stream().limit(30).map(Object::toString).toList());
        }
    }

    /**
     * 残留写入的分类。ADR 0014 的结论是「残留不为零，但不画东西」：它们只切终端**模式**位
     * （keypad / bracketed paste / mouse tracking）与裸 CR，不定位光标、不吐字形，故不构成第二个画者。
     *
     * <p>JLine 经 {@code Curses.doTputs} 逐字符写出，故先把全部残留字节按顺序拼回一条流，再用
     * 已枚举的序列表做前缀分词。剩下任何无法分词的字节 = JLine 多了一个写入者，测试立刻红。
     */
    private static final List<Map.Entry<String, String>> RESIDUAL_PATTERNS = List.of(
            Map.entry("\u001b[?1h", "keypad-mode"),          // keypad_xmit (smkx) 前半
            Map.entry("\u001b=", "keypad-mode"),             // keypad_xmit 后半
            Map.entry("\u001b[?1l", "keypad-mode"),          // keypad_local (rmkx) 前半
            Map.entry("\u001b>", "keypad-mode"),             // keypad_local 后半
            Map.entry("\u001b[?2004h", "bracketed-paste-mode"),
            Map.entry("\u001b[?2004l", "bracketed-paste-mode"),
            Map.entry("\u001b[?1000l", "mouse-tracking-off"),
            Map.entry("\u001b[?1002l", "mouse-tracking-off"),
            Map.entry("\u001b[?1003l", "mouse-tracking-off"),
            Map.entry("\u001b[?1004l", "mouse-tracking-off"),
            Map.entry("\u001b[?1005l", "mouse-tracking-off"),
            Map.entry("\u001b[?1006l", "mouse-tracking-off"),
            Map.entry("\u001b[?1015l", "mouse-tracking-off"),
            Map.entry("\u001b[?1016l", "mouse-tracking-off"),
            Map.entry("\r", "bare-carriage-return"));

    private static final Set<String> EXPECTED_RESIDUAL_KINDS = new LinkedHashSet<>(List.of(
            "keypad-mode",
            "bracketed-paste-mode",
            "mouse-tracking-off",
            "bare-carriage-return"));

    private static Set<String> classifyResidualWrites(List<TracingWriter.WriteRecord> records) {
        StringBuilder all = new StringBuilder();
        for (TracingWriter.WriteRecord r : records) {
            all.append(r.text);
        }
        String stream = all.toString();
        Set<String> kinds = new LinkedHashSet<>();
        int i = 0;
        while (i < stream.length()) {
            String matchedKind = null;
            int matchedLength = 0;
            for (Map.Entry<String, String> pattern : RESIDUAL_PATTERNS) {
                String seq = pattern.getKey();
                if (seq.length() > matchedLength && stream.startsWith(seq, i)) {
                    matchedKind = pattern.getValue();
                    matchedLength = seq.length();
                }
            }
            if (matchedKind == null) {
                throw new AssertionError("an unclassified residual terminal write appeared at offset "
                        + i + " — it may be a second painter. remainder="
                        + visible(stream.substring(i, Math.min(stream.length(), i + 60))));
            }
            kinds.add(matchedKind);
            i += matchedLength;
        }
        return kinds;
    }

    private static String visible(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == 27) {
                sb.append("\\e");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c < 32) {
                sb.append("\\x").append(Integer.toHexString(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> lastPlain(List<CaptureDisplay.Frame> frames) {
        return frames.isEmpty() ? List.of() : frames.get(frames.size() - 1).plain();
    }

    // --------------------------------------- the six version-coupling points

    @Test
    void allSixJLineVersionCouplingPointsStillHold() throws Exception {
        // 1. LineReaderImpl 非 final 且构造器 public
        assertFalse(Modifier.isFinal(LineReaderImpl.class.getModifiers()),
                "coupling point 1: LineReaderImpl must stay non-final");
        assertTrue(Arrays.stream(LineReaderImpl.class.getConstructors())
                        .anyMatch(c -> Modifier.isPublic(c.getModifiers())
                                && Arrays.equals(c.getParameterTypes(),
                                new Class<?>[]{Terminal.class, String.class, Map.class})),
                "coupling point 1: LineReaderImpl(Terminal, String, Map) must stay public");

        // 2. display 字段 protected
        assertTrue(Modifier.isProtected(LineReaderImpl.class.getDeclaredField("display").getModifiers()),
                "coupling point 2: LineReaderImpl.display must stay protected");

        // 3. lastStatusSize 字段 protected
        assertTrue(Modifier.isProtected(
                        LineReaderImpl.class.getDeclaredField("lastStatusSize").getModifiers()),
                "coupling point 3: LineReaderImpl.lastStatusSize must stay protected");

        // 4. redisplay(boolean) protected
        assertTrue(Modifier.isProtected(
                        LineReaderImpl.class.getDeclaredMethod("redisplay", boolean.class).getModifiers()),
                "coupling point 4: LineReaderImpl.redisplay(boolean) must stay protected");

        // 5. doCleanup(boolean) protected
        assertTrue(Modifier.isProtected(
                        LineReaderImpl.class.getDeclaredMethod("doCleanup", boolean.class).getModifiers()),
                "coupling point 5: LineReaderImpl.doCleanup(boolean) must stay protected");

        // 6. Display.update(List,int,boolean) public，且它是 Display 唯一可被外部触达的写入者：
        //    其余会写终端的成员必须非 public，公开面必须仍等于这份枚举。
        Method update = Display.class.getDeclaredMethod("update", List.class, int.class, boolean.class);
        assertTrue(Modifier.isPublic(update.getModifiers()),
                "coupling point 6: Display.update(List,int,boolean) must stay public");
        List<String> publicApi = Arrays.stream(Display.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
        assertEquals(List.of(
                        "clear",            // 只置 reset 标志，clear_screen 在 update 顶部才发射
                        "delayLineWrap",
                        "reset",            // 只改字段
                        "resize",           // 只改字段
                        "setDelayLineWrap",
                        "update",           // 三参 = 唯一写入者；两参重载委派到它
                        "updateAnsi",       // 委派到两参 update
                        "wcwidth"),         // 纯宽度计算，不写终端
                publicApi,
                "coupling point 6: Display's public surface changed — a new public method may write "
                        + "the terminal outside update(List,int,boolean)");

        // 覆写确实生效：构造完成时截获 Display 已经装上（构造期的 doDisplay() 被覆盖）。
        try (Rig rig = new Rig(80, 24)) {
            CafeCliLineReader reader = newReader(rig.terminal);
            assertSame(reader.capture(), reader.currentDisplay(),
                    "the capture Display must already be installed at construction time");
        }

        // LineReaderBuilder 依然不可用于建构子类——这是 create() 存在的理由。
        assertTrue(Modifier.isFinal(LineReaderBuilder.class.getModifiers()),
                "LineReaderBuilder is final, so manual construction stays mandatory");
        try (Rig rig = new Rig(80, 24)) {
            LineReader built = LineReaderBuilder.builder().terminal(rig.terminal).build();
            assertSame(LineReaderImpl.class, built.getClass(),
                    "LineReaderBuilder.build() still hardcodes LineReaderImpl");
        }
    }

    @Test
    void printAboveIsRoutedToTheFreezeSinkInsteadOfTheTerminal() throws Exception {
        try (Rig rig = new Rig(80, 24)) {
            CafeCliLineReader reader = newReader(rig.terminal);
            List<String> frozen = new CopyOnWriteArrayList<>();
            reader.setPrintAboveSink(frozen::add);
            TracingWriter tracer = rig.terminal.tracer();

            Thread readThread = new Thread(() -> {
                try {
                    reader.readLine("pa> ");
                } catch (Throwable ignored) {
                    // pipe closed at teardown
                }
            }, "guard-printabove");
            readThread.setDaemon(true);
            readThread.start();
            Thread.sleep(400);
            rig.send("x");

            tracer.reset();
            reader.printAbove("ABOVE-LINE");
            reader.printAbove(new AttributedString("ABOVE-ATTRIBUTED"));
            Thread.sleep(150);

            assertEquals(List.of("ABOVE-LINE", "ABOVE-ATTRIBUTED"), frozen);
            assertTrue(tracer.records().stream().noneMatch(r -> r.text.contains("ABOVE-")),
                    "printAbove must not write the terminal directly: " + tracer.records());

            rig.send("\r");
            readThread.join(2000);
        }
    }

    @Test
    void acceptingALineClearsTheInputFrameAndPrintsNoNewline() throws Exception {
        try (Rig rig = new Rig(80, 24)) {
            CafeCliLineReader reader = newReader(rig.terminal);
            TracingWriter tracer = rig.terminal.tracer();

            Thread readThread = new Thread(() -> {
                try {
                    reader.readLine("c> ");
                } catch (Throwable ignored) {
                    // pipe closed at teardown
                }
            }, "guard-cleanup");
            readThread.setDaemon(true);
            readThread.start();
            Thread.sleep(400);
            rig.send("submitted");
            assertFalse(reader.capture().lastFrame().isEmpty());

            tracer.reset();
            rig.send("\r");
            readThread.join(2000);

            assertTrue(reader.capture().lastFrame().isEmpty(),
                    "the input line must leave the live region when readLine finishes: "
                            + reader.capture().lastFrame().plain());
            assertTrue(tracer.records().stream().noneMatch(r -> r.text.contains("\n")),
                    "doCleanup must not print its own newline: " + tracer.records());
        }
    }
}
