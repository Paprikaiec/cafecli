package com.cafecli.ui;

import com.cafecli.harness.AgentSession;
import com.cafecli.harness.SessionCompletion;
import com.cafecli.harness.SubmitResult;
import com.cafecli.render.Renderer;
import com.cafecli.render.inline.CafeCliLineReader;
import com.cafecli.render.inline.InlineRenderer;
import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import java.util.*;
import java.util.concurrent.*;

/** Resident input pump. Session subscribers only enqueue; a separate UI consumer projects their events. */
public final class InteractiveRepl implements AutoCloseable {
    private final Terminal terminal;
    private final LineReader reader;
    private final Renderer renderer;
    private final AgentSession session;
    private final ExecutorService ui = Executors.newSingleThreadExecutor(
            task -> Thread.ofVirtual().name("cafecli-ui-events").unstarted(task));
    private final List<AgentSession.Subscription> subscriptions = new ArrayList<>();
    private final Set<Throwable> reported = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EventRenderAdapter adapter;
    private Terminal.SignalHandler previousInt;
    private boolean closed;

    public InteractiveRepl(Terminal terminal, LineReader reader, Renderer renderer, AgentSession session) {
        this.terminal = Objects.requireNonNull(terminal);
        this.reader = Objects.requireNonNull(reader);
        this.renderer = Objects.requireNonNull(renderer);
        this.session = Objects.requireNonNull(session);
        this.adapter = new EventRenderAdapter(renderer,session.status());
        subscriptions.add(session.subscribe(event -> ui.execute(() -> adapter.accept(event))));
        subscriptions.add(session.subscribeStatus(status -> ui.execute(() -> adapter.updateSessionStatus(status))));
        subscriptions.add(session.subscribeActivity(busy -> ui.execute(this::refreshInput)));
        subscriptions.add(session.subscribeCompletion(completion -> ui.execute(() -> {
            if (completion instanceof SessionCompletion.Failed failure) reportFailure(failure.failure());
        })));
        subscriptions.add(session.subscribeFailure(failure -> ui.execute(() -> reportFailure(failure))));
        if (reader instanceof CafeCliLineReader capture) capture.setRightPromptSupplier(this::rightPrompt);
        if (renderer instanceof InlineRenderer inline) inline.bindSessionInterrupt(this::interrupt);
        bindKeys();
    }

    /** Queued presentation for composition diagnostics and capability notices. */
    public void notice(String text) { ui.execute(() -> renderer.stream().println(text)); }

    private void reportFailure(Throwable failure) {
        if (reported.add(failure)) adapter.fail(failure);
    }
    private String rightPrompt() { return session.isBusy() ? "steer · esc 停止" : renderer.inputRightPrompt(); }
    private void refreshInput() { if (reader instanceof CafeCliLineReader capture) capture.refreshInputBox(); }
    private boolean interrupt() {
        if (!session.isBusy()) return false;
        session.cancel();
        ui.execute(() -> renderer.stream().println("⏹️ 已请求取消"));
        return true;
    }
    private void bindKeys() {
        reader.getWidgets().put("cafecli-v2-cancel", () -> { interrupt(); return true; });
        reader.getWidgets().put("cafecli-v2-newline", () -> { reader.getBuffer().write('\n'); return true; });
        for (String name : List.of(LineReader.MAIN,LineReader.EMACS,LineReader.VIINS)) {
            var map = reader.getKeyMaps().get(name);
            if (map == null) continue;
            map.bind(new Reference("cafecli-v2-cancel"),KeyMap.esc());
            map.bind(new Reference("cafecli-v2-newline"),"\033\r","\033\n","\033[13;2u","\033[13;5u",
                    "\033[27;2;13~","\033[27;5;13~");
        }
    }

    public void run() {
        String pending = null;
        long armed = 0, eof = 0;
        previousInt = terminal.handle(Terminal.Signal.INT, signal -> interrupt());
        try {
            while (true) {
                String input;
                try {
                    renderer.beforeInput();
                    input = reader.readLine(renderer.inputPrompt(),rightPrompt(),(MaskingCallback)null,pending);
                    pending = null;
                    renderer.afterInput();
                } catch (UserInterruptException interrupted) {
                    if (interrupt()) { pending = interrupted.getPartialLine(); armed = 0; }
                    else if (interrupted.getPartialLine() != null && !interrupted.getPartialLine().isEmpty()) armed = 0;
                    else if (armed != 0 && System.nanoTime()-armed < 2_000_000_000L) return;
                    else { armed = System.nanoTime(); ui.execute(() -> renderer.stream().println("(再按一次 Ctrl+C 退出)")); }
                    continue;
                } catch (EndOfFileException ended) {
                    long now = System.nanoTime();
                    if (!session.isBusy() || (eof != 0 && now-eof < 5_000_000L)) return;
                    eof = now; continue;
                }
                eof = 0; armed = 0;
                if (input == null || input.isBlank()) continue;
                if (input.equals("/exit") || input.equals("/quit")) return;
                if (input.startsWith("/")) {
                    String message = input.equals("/plan") ? "Plan-and-Execute 已退役（ADR 0015）。" : "未知命令: " + input;
                    ui.execute(() -> renderer.stream().println(message));
                    continue;
                }
                String submitted = input;
                // The submitted source is what the human typed; never use a model presentation or re-read attachments.
                ui.execute(() -> renderer.appendUserPrompt(submitted));
                try {
                    SubmitResult result = session.submit(input);
                    if (result instanceof SubmitResult.Blocked blocked)
                        ui.execute(() -> renderer.stream().println("❌ UserPromptSubmit: " + blocked.reason()));
                } catch (RuntimeException failure) { ui.execute(() -> reportFailure(failure)); }
            }
        } finally { close(); }
    }

    /** Cancel/drain the session before closing subscribers, then drain presentation and restore the signal handler. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        boolean interrupted = false;
        try {
            try { session.close(); }
            finally {
                subscriptions.forEach(AgentSession.Subscription::close);
                ui.shutdown();
                while (!ui.isTerminated()) {
                    try { ui.awaitTermination(1,TimeUnit.SECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
        } finally {
            if (previousInt != null) terminal.handle(Terminal.Signal.INT,previousInt);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
