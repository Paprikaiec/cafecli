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
    private final InteractiveApprover approvals;
    private final ExecutorService ui = Executors.newSingleThreadExecutor(
            task -> Thread.ofVirtual().name("cafecli-ui-events").unstarted(task));
    private final List<AgentSession.Subscription> subscriptions = new ArrayList<>();
    private final Set<Throwable> reported = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EventRenderAdapter adapter;
    private Terminal.SignalHandler previousInt;
    private volatile boolean closed;
    private final Object inputLock=new Object();
    private final ArrayDeque<String> deferredInputs=new ArrayDeque<>();
    private boolean commandActive;
    private boolean reading;
    private boolean modal;
    private boolean wakeInterrupted;
    private long wakeSignals;
    private long typedInterrupts;
    private long wakeTypedInterrupts;
    private Thread inputOwner;

    public InteractiveRepl(Terminal terminal, LineReader reader, Renderer renderer, AgentSession session) {
        this(terminal,reader,renderer,session,null);
    }

    public InteractiveRepl(Terminal terminal, LineReader reader, Renderer renderer, AgentSession session,
                           InteractiveApprover approvals) {
        Objects.requireNonNull(terminal);
        this.terminal = reader.getTerminal() instanceof InputTerminal ? reader.getTerminal() : terminal;
        this.reader = Objects.requireNonNull(reader);
        this.renderer = Objects.requireNonNull(renderer);
        this.session = Objects.requireNonNull(session);
        this.approvals = approvals;
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
        if (approvals != null) approvals.bind(renderer,ui::execute,this::refreshInput);
        if (reader instanceof org.jline.reader.impl.LineReaderImpl implementation) implementation.setCompleter((current,line,candidates) -> {
            if (line == null || candidates == null) return;
            for (var item : session.complete(new AgentSession.CompletionQuery(line.line(),line.word(),line.cursor())))
                candidates.add(new Candidate(item.value(),item.display(),item.group(),item.description(),null,null,item.complete()));
        });
        bindKeys();
    }

    /** Queued presentation for composition diagnostics and capability notices. */
    public void notice(String text) { ui.execute(() -> renderer.stream().println(text)); }

    private void reportFailure(Throwable failure) {
        if (reported.add(failure)) adapter.fail(failure);
    }
    private String rightPrompt() { if (approvals != null && approvals.pending()) return "y / n / a"; return session.isBusy() ? "steer · esc 停止" : renderer.inputRightPrompt(); }
    private void refreshInput() { if (reader instanceof CafeCliLineReader capture) capture.tryRefreshInputBox(); }
    private boolean interrupt() {
        if (!session.isBusy()) return false;
        // Cancellation attempts every owned token even when one listener fails. Signal delivery must
        // not leak that failure to the JVM handler (which would write directly to the terminal).
        try { session.cancel(); }
        catch (RuntimeException | Error failure) {
            enqueueCancellationNotice("❌ 取消回调失败: " + failure.getMessage());
        }
        enqueueCancellationNotice("⏹️ 已请求取消");
        return true;
    }
    private void enqueueCancellationNotice(String text) {
        try { ui.execute(() -> renderer.stream().println(text)); }
        catch (RejectedExecutionException closed) { /* The closing owner has already drained presentation. */ }
    }
    private void bindKeys() {
        reader.getWidgets().put("cafecli-v2-cancel", () -> { interrupt(); return true; });
        reader.getWidgets().put("cafecli-v2-transcript", () -> {
            openTranscript();
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        reader.getWidgets().put("cafecli-v2-approval-toggle", () -> {
            session.setApprovalEnabled(!session.approvalEnabled());
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        reader.getWidgets().put("cafecli-v2-interrupt",() -> {
            synchronized (inputLock) { typedInterrupts++; }
            throw new UserInterruptException(reader.getBuffer().toString());
        });
        reader.getWidgets().put("cafecli-v2-newline", () -> { reader.getBuffer().write('\n'); return true; });
        for (String name : List.of(LineReader.MAIN,LineReader.EMACS,LineReader.VIINS)) {
            var map = reader.getKeyMaps().get(name);
            if (map == null) continue;
            map.bind(new Reference("cafecli-v2-cancel"),KeyMap.esc());
            map.bind(new Reference("cafecli-v2-interrupt"),"\003");
            map.bind(new Reference("cafecli-v2-transcript"),"\017");
            map.bind(new Reference("cafecli-v2-approval-toggle"),"\031");
            map.bind(new Reference("cafecli-v2-newline"),"\033\r","\033\n","\033[13;2u","\033[13;5u",
                    "\033[27;2;13~","\033[27;5;13~");
        }
    }

    private void openTranscript() {
        synchronized (inputLock) { modal=true; }
        try {
            if (renderer instanceof InlineRenderer inline) inline.openTranscript();
            else renderer.stream().println("Transcript overlay is available only in inline mode.");
        } finally { synchronized (inputLock) { modal=false; wakeInputOwner(); } }
    }

    /** Called with inputLock held. A modal owns its own cancellation and must never receive this wake. */
    private void wakeInputOwner() {
        if (!closed && !commandActive && !deferredInputs.isEmpty() && reading && !modal && !wakeInterrupted) {
            wakeInterrupted=true;
            inputOwner.interrupt();
        }
    }
    private boolean finishRead() {
        synchronized (inputLock) {
            reading=false;
            if (!wakeInterrupted) return false;
            wakeInterrupted=false;
            boolean realSignal=(terminal instanceof InputTerminal observed && observed.interrupts()!=wakeSignals)
                    || typedInterrupts!=wakeTypedInterrupts;
            Thread.interrupted();
            return !realSignal;
        }
    }
    private String nextDeferredInput() {
        synchronized (inputLock) { return commandActive ? null : deferredInputs.pollFirst(); }
    }
    private void commandReleased() {
        synchronized (inputLock) { commandActive=false; wakeInputOwner(); }
    }
    private void commandOutput(String text) {
        renderer.stream().print(text);
        if (!text.endsWith("\n")) renderer.stream().println();
    }

    public void run() {
        String pending = null;
        long armed = 0, eof = 0;
        inputOwner=Thread.currentThread();
        previousInt = terminal.handle(Terminal.Signal.INT, signal -> interrupt());
        try {
            while (true) {
                String deferred=nextDeferredInput();
                if (deferred!=null) {
                    if (acceptInput(deferred)) return;
                    continue;
                }
                String input;
                try {
                    renderer.beforeInput();
                    synchronized (inputLock) {
                        reading=true;
                        wakeSignals=terminal instanceof InputTerminal observed ? observed.interrupts() : 0;
                        wakeTypedInterrupts=typedInterrupts;
                        wakeInputOwner();
                    }
                    input = reader.readLine(renderer.inputPrompt(),rightPrompt(),(MaskingCallback)null,pending);
                    finishRead();
                    pending = null;
                    renderer.afterInput();
                } catch (UserInterruptException interrupted) {
                    if (finishRead()) { pending=interrupted.getPartialLine(); continue; }
                    if (interrupt()) { pending = interrupted.getPartialLine(); armed = 0; }
                    else if (interrupted.getPartialLine() != null && !interrupted.getPartialLine().isEmpty()) armed = 0;
                    else if (armed != 0 && System.nanoTime()-armed < 2_000_000_000L) return;
                    else { armed = System.nanoTime(); ui.execute(() -> renderer.stream().println("(再按一次 Ctrl+C 退出)")); }
                    continue;
                } catch (EndOfFileException ended) {
                    finishRead();
                    long now = System.nanoTime();
                    boolean commandPending;
                    synchronized (inputLock) { commandPending=commandActive || !deferredInputs.isEmpty(); }
                    if ((!session.isBusy() && !commandPending) || (eof != 0 && now-eof < 5_000_000L)) return;
                    eof = now; continue;
                }
                eof = 0; armed = 0;
                if (approvals != null && approvals.answer(input)) continue;
                if (input == null || input.trim().isEmpty()) continue;
                input=input.trim();
                var presentation=session.commandPresentation(input);
                synchronized (inputLock) {
                    if ((commandActive || !deferredInputs.isEmpty()) && (presentation.isEmpty() || !presentation.get().control()
                            || presentation.get().effect()==AgentSession.CommandEffect.EXIT)) {
                        deferredInputs.addLast(input);
                        continue;
                    }
                }
                if (acceptInput(input)) return;
            }
        } finally { close(); }
    }

    /** Only the input owner classifies source submissions and enters terminal modals. */
    private boolean acceptInput(String input) {
        var presentation=session.commandPresentation(input);
        if (presentation.isPresent() && presentation.get().effect()==AgentSession.CommandEffect.TRANSCRIPT) {
            openTranscript(); return false;
        }
        String submitted=input;
        // The submitted source is what the human typed; never use a model presentation or re-read attachments.
        ui.execute(() -> renderer.appendUserPrompt(submitted));
        if (presentation.isPresent() || input.startsWith("/")) {
            if (presentation.isEmpty()) { ui.execute(() -> renderer.stream().print(session.unknownCommand(submitted))); return false; }
            var effect=presentation.get();
            if (effect.effect()==AgentSession.CommandEffect.HISTORY_CLEAR) {
                try { reader.getHistory().purge(); }
                catch (java.io.IOException failure) { ui.execute(() -> reportFailure(failure)); return false; }
            }
            if (effect.effect()==AgentSession.CommandEffect.PALETTE) {
                synchronized (inputLock) { modal=true; }
                try {
                    int selected=renderer.openPalette(effect.title(),effect.items());
                    String text=selected<0 ? "(已关闭)" : effect.hints().get(selected);
                    ui.execute(() -> renderer.stream().println(text));
                } finally { synchronized (inputLock) { modal=false; } }
                return false;
            }
            try {
                if (effect.control()) {
                    session.dispatch(input).ifPresent(text -> ui.execute(() -> commandOutput(text)));
                } else {
                    synchronized (inputLock) { commandActive=true; }
                    session.dispatchAsync(input,text -> ui.execute(() -> {
                        if (effect.effect()==AgentSession.CommandEffect.CLEAR_VIEW) renderer.clearView();
                        commandOutput(text);
                    }),failure -> ui.execute(() -> reportFailure(failure)),() -> ui.execute(this::commandReleased));
                }
            } catch (RuntimeException failure) {
                synchronized (inputLock) { if (!effect.control()) commandActive=false; }
                ui.execute(() -> reportFailure(failure));
            }
            return effect.effect()==AgentSession.CommandEffect.EXIT;
        }
        try {
            SubmitResult result=session.submit(input);
            if (result instanceof SubmitResult.Blocked blocked)
                ui.execute(() -> renderer.stream().println("❌ UserPromptSubmit: " + blocked.reason()));
        } catch (RuntimeException failure) { ui.execute(() -> reportFailure(failure)); }
        return false;
    }

    /** Cancel/drain the session before closing subscribers, then drain presentation and restore the signal handler. */
    @Override public void close() {
        synchronized (inputLock) { if (closed) return; closed=true; }
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
