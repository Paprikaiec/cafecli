package com.cafecli.ui;

import org.jline.terminal.*;
import org.jline.utils.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Observes real INT delivery even while JLine temporarily owns the handler. A direct input-owner wake does not
 * increment this counter, so it cannot hide a racing Ctrl+C. All I/O still belongs to the same terminal and reader.
 */
public final class InputTerminal extends org.jline.terminal.impl.AbstractTerminal {
    private final Terminal terminal;
    private final AtomicLong interrupts=new AtomicLong();
    private final AtomicLong wakes=new AtomicLong();
    private final Map<SignalHandler,SignalHandler> observedHandlers=new IdentityHashMap<>();

    public InputTerminal(Terminal terminal) throws IOException {
        super(terminal.getName(),terminal.getType(),terminal.encoding(),SignalHandler.SIG_DFL);
        this.terminal=terminal;
    }
    @Override public org.jline.terminal.spi.TerminalProvider getProvider() {
        return terminal instanceof org.jline.terminal.spi.TerminalExt extension ? extension.getProvider() : null;
    }
    @Override public org.jline.terminal.spi.SystemStream getSystemStream() {
        return terminal instanceof org.jline.terminal.spi.TerminalExt extension ? extension.getSystemStream() : null;
    }
    @Override public Status getStatus(boolean create) {
        return terminal instanceof org.jline.terminal.impl.AbstractTerminal concrete ? concrete.getStatus(create) : super.getStatus(create);
    }
    long interrupts() { return interrupts.get(); }
    void wake(Thread owner) { wakes.incrementAndGet(); owner.interrupt(); }
    @Override public synchronized SignalHandler handle(Signal signal,SignalHandler handler) {
        SignalHandler observed=handler;
        if (signal==Signal.INT && handler!=SignalHandler.SIG_DFL && handler!=SignalHandler.SIG_IGN) {
            observed=delivered -> { interrupts.incrementAndGet(); handler.handle(delivered); };
            observedHandlers.put(observed,handler);
        }
        SignalHandler previous=terminal.handle(signal,observed);
        SignalHandler original=observedHandlers.remove(previous);
        return original==null ? previous : original;
    }
    @Override public void raise(Signal signal) { terminal.raise(signal); }
    @Override public String getName() { return terminal.getName(); }
    @Override public NonBlockingReader reader() { return terminal.reader(); }
    @Override public PrintWriter writer() { return terminal.writer(); }
    @Override public Charset encoding() { return terminal.encoding(); }
    @Override public Charset inputEncoding() { return terminal.inputEncoding(); }
    @Override public Charset outputEncoding() { return terminal.outputEncoding(); }
    @Override public InputStream input() { return terminal.input(); }
    @Override public OutputStream output() { return terminal.output(); }
    @Override public boolean canPauseResume() { return terminal.canPauseResume(); }
    @Override public void pause() { terminal.pause(); }
    @Override public void pause(boolean wait) throws InterruptedException { terminal.pause(wait); }
    @Override public void resume() { terminal.resume(); }
    @Override public boolean paused() { return terminal.paused(); }
    // Capture the original attributes once. Retrying enterRawMode as a whole could capture already-raw
    // attributes after an interrupted stty applied its change, preventing JLine from restoring cooked mode.
    @Override public Attributes enterRawMode() { return super.enterRawMode(); }
    @Override public boolean echo() { return super.echo(); }
    @Override public boolean echo(boolean enabled) { return super.echo(enabled); }
    @Override public Attributes getAttributes() { return control(terminal::getAttributes); }
    @Override public void setAttributes(Attributes attributes) { control(() -> { terminal.setAttributes(attributes); return null; }); }
    @Override public Size getSize() { return control(terminal::getSize); }
    @Override public void setSize(Size size) { control(() -> { terminal.setSize(size); return null; }); }
    @Override public Size getBufferSize() { return control(terminal::getBufferSize); }

    /**
     * Native ExecPty control calls spawn stty in the terminal's foreground process group. Ctrl+C can
     * kill that subprocess while JLine is entering raw mode or cleaning up; an owned reader wake can
     * also leave its thread interrupted. Keep those idempotent calls outside the interrupt window,
     * then let the resident reader observe the same interrupt. Only a fresh INT or owned wake plus
     * its specific native failure permits one retry. Unrelated I/O errors and the retry's error still escape.
     */
    private <T> T control(java.util.function.Supplier<T> operation) {
        boolean interrupted = Thread.interrupted();
        long before = interrupts.get();
        long beforeWake = wakes.get();
        try {
            try { return operation.get(); }
            catch (IOError failure) {
                interrupted |= Thread.interrupted();
                interrupted |= consumedInterrupt(failure);
                if (!interruptedControl(failure)) throw failure;
                // The kernel can kill stty before the JVM signal-dispatch thread runs our handler.
                // Await only that operation's fresh INT, never infer one from an older signal.
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
                while (interrupts.get() == before && wakes.get() == beforeWake && System.nanoTime() < deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    interrupted |= Thread.interrupted();
                }
                if (interrupts.get() == before && wakes.get() == beforeWake) throw failure;
                return operation.get();
            }
        } catch (IOError failure) {
            // The retry can itself consume a genuine interruption before it fails. A global INT/wake
            // epoch authorizes recovery, but says nothing about which caller owns the interrupt flag.
            interrupted |= Thread.interrupted();
            interrupted |= consumedInterrupt(failure);
            throw failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean consumedInterrupt(IOError failure) {
        return failure.getCause() instanceof InterruptedIOException interrupted
                && interrupted.getCause() instanceof InterruptedException;
    }

    private static boolean interruptedControl(IOError failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof InterruptedIOException) return true;
        if (!(cause instanceof IOException) || cause.getMessage() == null) return false;
        String message = cause.getMessage();
        String command = OSUtils.STTY_COMMAND;
        return (message.startsWith("Error executing '" + command + " ") && message.endsWith("': "))
                || (message.startsWith("Cannot run program \"" + command + "\": Failed to exec spawn helper:")
                    && message.contains("signal: 2,"));
    }
    @Override public void flush() { terminal.flush(); }
    @Override public String getType() { return terminal.getType(); }
    @Override public boolean puts(InfoCmp.Capability capability,Object... parameters) { return terminal.puts(capability,parameters); }
    @Override public boolean getBooleanCapability(InfoCmp.Capability capability) { return terminal==null ? super.getBooleanCapability(capability) : terminal.getBooleanCapability(capability); }
    @Override public Integer getNumericCapability(InfoCmp.Capability capability) { return terminal==null ? super.getNumericCapability(capability) : terminal.getNumericCapability(capability); }
    @Override public String getStringCapability(InfoCmp.Capability capability) { return terminal==null ? super.getStringCapability(capability) : terminal.getStringCapability(capability); }
    @Override public Cursor getCursorPosition(IntConsumer discarded) { return terminal.getCursorPosition(discarded); }
    @Override public boolean hasMouseSupport() { return terminal.hasMouseSupport(); }
    @Override public boolean trackMouse(MouseTracking tracking) { return terminal.trackMouse(tracking); }
    @Override public MouseTracking getCurrentMouseTracking() { return terminal.getCurrentMouseTracking(); }
    @Override public MouseEvent readMouseEvent() { return terminal.readMouseEvent(); }
    @Override public MouseEvent readMouseEvent(IntSupplier input) { return terminal.readMouseEvent(input); }
    @Override public MouseEvent readMouseEvent(String prefix) { return terminal.readMouseEvent(prefix); }
    @Override public MouseEvent readMouseEvent(IntSupplier input,String prefix) { return terminal.readMouseEvent(input,prefix); }
    @Override public boolean hasFocusSupport() { return terminal.hasFocusSupport(); }
    @Override public boolean trackFocus(boolean tracking) { return terminal.trackFocus(tracking); }
    @Override public boolean supportsGraphemeClusterMode() { return terminal.supportsGraphemeClusterMode(); }
    @Override public boolean getGraphemeClusterMode() { return terminal.getGraphemeClusterMode(); }
    @Override public boolean setGraphemeClusterMode(boolean enabled) { return terminal.setGraphemeClusterMode(enabled); }
    @Override public ColorPalette getPalette() { return terminal.getPalette(); }
    @Override public int getDefaultForegroundColor() { return terminal.getDefaultForegroundColor(); }
    @Override public int getDefaultBackgroundColor() { return terminal.getDefaultBackgroundColor(); }
    @Override protected void doClose() throws IOException {
        boolean interrupted = Thread.interrupted();
        try { terminal.close(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
