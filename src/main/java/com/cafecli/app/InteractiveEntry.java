package com.cafecli.app;

import com.cafecli.harness.AgentSession;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.render.Renderer;
import com.cafecli.render.RendererFactory;
import com.cafecli.render.inline.CafeCliLineReader;
import com.cafecli.render.inline.InlineRenderer;
import com.cafecli.render.inline.TerminalCapabilities;
import com.cafecli.ui.InteractiveRepl;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.function.Consumer;

/** v2 interactive composition: terminal ownership stays outside the session facade. */
public final class InteractiveEntry {
    private InteractiveEntry() {}

    static int run(ConfigSources sources, PrintStream err) {
        var config = ConfigLoader.load(sources);
        var notices = new Notices();
        config.warnings().forEach(warning -> notices.accept("⚠️ 配置文件读取失败，使用默认配置: " + warning.detail()));
        var approvals = new com.cafecli.ui.InteractiveApprover();
        // Provider validation precedes terminal creation. A project trust request may lazily open the same
        // reader/render lifetime that the resident REPL later uses, without waiting on an unstarted UI queue.
        try (var surface = new Surface(sources,notices)) {
            AgentSession session;
            try { session = Composition.interactiveSession(config,notices,notices,approvals,surface::confirmTrust,() -> surface.renderer == null ? "(none)" : surface.renderer.getClass().getSimpleName()); }
            catch (Composition.Unavailable failure) { err.println(failure.getMessage()); return Cli.FAILED; }
            try (session) {
                surface.open();
                try (var repl = new InteractiveRepl(surface.terminal,surface.reader,surface.renderer,session,approvals)) {
                    notices.bind(repl::notice);
                    repl.run();
                }
            }
            return Cli.OK;
        } catch (IOException | RuntimeException failure) {
            err.println("❌ 运行失败: " + failure.getMessage());
            return Cli.FAILED;
        }
    }

    private static final class Surface implements AutoCloseable {
        private final ConfigSources sources;
        private final Notices notices;
        private Terminal terminal;
        private Renderer renderer;
        private LineReader reader;
        private Terminal.SignalHandler previousInt;
        Surface(ConfigSources sources, Notices notices) { this.sources=sources; this.notices=notices; }
        void open() throws IOException {
            if (terminal != null) return;
            terminal = new com.cafecli.ui.InputTerminal(TerminalCapabilities.systemTerminalBuilder().build());
            previousInt = terminal.handle(Terminal.Signal.INT, signal -> {});
            renderer = RendererFactory.create(mode(ConfigLoader.rendererMode(sources),notices),terminal);
            Map<String,Object> variables = new HashMap<>();
            variables.put(LineReader.AMBIGUOUS_BINDING,100L);
            reader = renderer instanceof InlineRenderer && CafeCliLineReader.supported(terminal)
                    ? CafeCliLineReader.create(terminal,"cafecli",variables,new DefaultHistory(),null,null)
                    : LineReaderBuilder.builder().terminal(terminal).variables(variables).history(new DefaultHistory()).build();
            reader.option(LineReader.Option.BRACKETED_PASTE,true);
            if (renderer instanceof InlineRenderer inline) inline.bindLineReader(reader);
            renderer.start();
            notices.bind(text -> renderer.stream().println(text));
        }
        boolean confirmTrust(java.nio.file.Path project, List<String> commands) {
            try { open(); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return new com.cafecli.ui.ProjectHookTrustPrompt(renderer,reader).confirm(project,commands);
        }
        @Override public void close() throws IOException {
            try { if (renderer != null) renderer.close(); }
            finally {
                if (terminal != null) {
                    if (previousInt != null) terminal.handle(Terminal.Signal.INT,previousInt);
                    terminal.close();
                }
            }
        }
    }

    private static RendererFactory.Mode mode(String configured, Consumer<String> warning) {
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "plain" -> RendererFactory.Mode.PLAIN;
            case "inline" -> RendererFactory.Mode.INLINE;
            case "lanterna", "tui" -> {
                warning.accept("⚠️ Lanterna 全屏 TUI 已移除（ADR 0015），CAFECLI_RENDERER='"
                        + configured.trim() + "' 回退到 inline；如需纯文本降级请用 CAFECLI_RENDERER=plain");
                yield RendererFactory.Mode.INLINE;
            }
            default -> {
                warning.accept("⚠️ 未识别的 CAFECLI_RENDERER='" + configured.trim() + "'，回退到 inline");
                yield RendererFactory.Mode.INLINE;
            }
        };
    }
    /** Construction notices and later callbacks share one enqueue-only sink, without a handoff race. */
    private static final class Notices implements Consumer<String> {
        private final List<String> pending = new ArrayList<>();
        private Consumer<String> sink;
        @Override public synchronized void accept(String text) {
            if (sink == null) pending.add(text); else sink.accept(text);
        }
        synchronized void bind(Consumer<String> sink) {
            this.sink = sink;
            pending.forEach(sink);
            pending.clear();
        }
    }
}
