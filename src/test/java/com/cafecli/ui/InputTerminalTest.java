package com.cafecli.ui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InputTerminalTest {
    @Test void metadataCallerDoesNotAcquireAnInterruptDeliveredToADifferentInputOwner() throws Exception {
        var readerStopped = new java.util.concurrent.CountDownLatch(1);
        Thread reader = Thread.ofVirtual().start(() -> {
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException expected) { readerStopped.countDown(); }
        });
        try (var leaf = new ControlledTerminal(); var terminal = new InputTerminal(leaf)) {
            terminal.handle(Terminal.Signal.INT,signal -> reader.interrupt());
            leaf.geometry.set(() -> {
                leaf.geometry.set(() -> new Size(80,24));
                terminal.raise(Terminal.Signal.INT);
                throw new IOError(new IOException("Error executing '"+org.jline.utils.OSUtils.STTY_COMMAND+" -a': "));
            });
            assertEquals(80,terminal.getSize().getColumns());
            boolean fabricated = Thread.interrupted();
            assertTrue(readerStopped.await(2,java.util.concurrent.TimeUnit.SECONDS),"the actual input owner still receives INT");
            assertFalse(fabricated,"another thread's cancellation must not interrupt the metadata caller");
        } finally {
            Thread.interrupted(); reader.interrupt(); reader.join(2000); assertFalse(reader.isAlive());
        }
    }

    @Test void unrelatedErrorsAndNativeErrorsWithoutAFreshIntRetainTheirIdentity() throws Exception {
        try (var leaf = new ControlledTerminal(); var terminal = new InputTerminal(leaf)) {
            terminal.handle(Terminal.Signal.INT,signal -> {});
            terminal.raise(Terminal.Signal.INT); // An older INT cannot authorize recovery of a later I/O failure.
            for (String message : new String[]{"unrelated device failure", "Error executing '"+org.jline.utils.OSUtils.STTY_COMMAND+" -a': "}) {
                var failure = new IOError(new IOException(message));
                leaf.geometry.set(() -> { throw failure; });
                assertSame(failure,assertThrows(IOError.class,terminal::getSize));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stty","helper","unrelated"})
    void onlyTheCapturedNativeCommandFailureWithAFreshIntCanRecover(String failureKind) throws Exception {
        try (var leaf = new ControlledTerminal(); var terminal = new InputTerminal(leaf)) {
            Thread owner = Thread.currentThread();
            terminal.handle(Terminal.Signal.INT,signal -> owner.interrupt());
            String command = org.jline.utils.OSUtils.STTY_COMMAND;
            String message = switch (failureKind) {
                case "stty" -> "Error executing '"+command+" -a': ";
                case "helper" -> "Cannot run program \""+command+"\": Failed to exec spawn helper: pid: 1, signal: 2, error: 0 (none)";
                default -> "unrelated device failure";
            };
            var failure = new IOError(new IOException(message));
            leaf.geometry.set(() -> {
                leaf.geometry.set(() -> new Size(80,24));
                terminal.raise(Terminal.Signal.INT); throw failure;
            });
            try {
                if (failureKind.equals("unrelated")) assertSame(failure,assertThrows(IOError.class,terminal::getSize));
                else assertEquals(80,terminal.getSize().getColumns());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void aFreshIntAllowsOnlyOneNativeRetryAndTheRetryFailureStillEscapes() throws Exception {
        try (var leaf = new ControlledTerminal(); var terminal = new InputTerminal(leaf)) {
            Thread owner = Thread.currentThread();
            terminal.handle(Terminal.Signal.INT,signal -> owner.interrupt());
            var retryFailure = new IOError(new IOException("unrelated retry failure"));
            leaf.geometry.set(() -> {
                leaf.geometry.set(() -> { throw retryFailure; });
                terminal.raise(Terminal.Signal.INT);
                Thread.interrupted();
                throw consumedInterrupt();
            });
            try {
                assertSame(retryFailure,assertThrows(IOError.class,terminal::getSize));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void rawModeKeepsTheOriginalAttributesWhenAnInterruptedSetAlreadyAppliedTheChange() throws Exception {
        try (var leaf = new ControlledTerminal(); var terminal = new InputTerminal(leaf)) {
            Thread owner = Thread.currentThread();
            terminal.handle(Terminal.Signal.INT,signal -> owner.interrupt());
            Attributes before = terminal.getAttributes();
            leaf.afterSet.set(() -> {
                terminal.raise(Terminal.Signal.INT);
                Thread.interrupted();
                throw consumedInterrupt();
            });
            try {
                var captured = terminal.enterRawMode();
                assertEquals(before.getLocalFlags(),captured.getLocalFlags());
                assertFalse(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
                terminal.setAttributes(captured);
                assertEquals(before.getLocalFlags(),terminal.getAttributes().getLocalFlags());
                assertEquals(before.getControlChars(),terminal.getAttributes().getControlChars());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void aRetryWhichConsumesTheCallingThreadsInterruptPreservesItsFailureAndRestoresOnlyThatFlag() throws Exception {
        try (var leaf = new ControlledTerminal(); var terminal = new InputTerminal(leaf)) {
            terminal.handle(Terminal.Signal.INT,signal -> {});
            var failure = consumedInterrupt();
            leaf.geometry.set(() -> {
                leaf.geometry.set(() -> {
                    Thread.currentThread().interrupt(); Thread.interrupted(); throw failure;
                });
                terminal.raise(Terminal.Signal.INT);
                throw new IOError(new IOException("Error executing '"+org.jline.utils.OSUtils.STTY_COMMAND+" -a': "));
            });
            try {
                assertSame(failure,assertThrows(IOError.class,terminal::getSize));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void anInheritedOwnedWakeFlagDoesNotInterruptMetadataOrCloseAndRemainsVisibleToTheReader() throws Exception {
        var leaf = new ControlledTerminal();
        try (var terminal = new InputTerminal(leaf)) {
            leaf.geometry.set(() -> {
                assertFalse(Thread.currentThread().isInterrupted());
                return new Size(80,24);
            });
            try {
                Thread.currentThread().interrupt();
                assertEquals(80,terminal.getSize().getColumns());
                assertTrue(Thread.currentThread().isInterrupted());
                terminal.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    private static final class ControlledTerminal extends ExternalTerminal {
        final AtomicReference<java.util.function.Supplier<Size>> geometry = new AtomicReference<>();
        final AtomicReference<Runnable> afterSet = new AtomicReference<>();
        ControlledTerminal() throws IOException {
            super("control-boundary","xterm",new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),StandardCharsets.UTF_8);
        }
        @Override public Size getSize() {
            var action = geometry == null ? null : geometry.get();
            return action==null ? super.getSize() : action.get();
        }
        @Override public void setAttributes(Attributes attributes) {
            super.setAttributes(attributes);
            var action = afterSet == null ? null : afterSet.getAndSet(null);
            if (action!=null) action.run();
        }
        @Override protected void doClose() throws IOException {
            assertFalse(Thread.currentThread().isInterrupted()); super.doClose();
        }
    }

    private static IOError consumedInterrupt() {
        return new IOError(new InterruptedIOException("Command interrupted").initCause(new InterruptedException()));
    }
}
