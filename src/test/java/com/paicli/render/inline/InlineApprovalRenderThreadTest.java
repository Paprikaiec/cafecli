package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineApprovalRenderThreadTest {

    @Test
    void bufferedKeyCannotDismissApprovalBeforeItsFirstRenderedFrame() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 30);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.enterRawMode()).thenReturn(new Attributes());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        CountDownLatch readStarted = new CountDownLatch(1);
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read()).thenAnswer(invocation -> {
            readStarted.countDown();
            return (int) 'y';
        });
        Mockito.when(terminal.reader()).thenReturn(reader);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump,
                new ManualFrameTicker());
             var inputExecutor = Executors.newSingleThreadExecutor()) {
            var result = inputExecutor.submit(() -> renderer.promptApproval(
                    ApprovalRequest.of("write_file", "{}", "test", "main Agent")));

            assertFalse(readStarted.await(150, TimeUnit.MILLISECONDS));
            assertFalse(result.isDone());
            assertEquals("", sink.toString(StandardCharsets.UTF_8));

            pump.pump();
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            assertEquals(ApprovalResult.Decision.APPROVED,
                    result.get(5, TimeUnit.SECONDS).decision());
            String visible = AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("需要审批"), visible);
        }
    }

    @Test
    void producerQueuesApprovalDrawingWhileOnlyInputThreadReadsTheTerminal() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 30);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.enterRawMode()).thenReturn(new Attributes());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicLong writerThread = new AtomicLong();
        PrintWriter writer = new RecordingPrintWriter(sink, writerThread);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicLong readerThread = new AtomicLong();
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read()).thenAnswer(invocation -> {
            readerThread.set(Thread.currentThread().threadId());
            readStarted.countDown();
            assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
            return (int) 'y';
        });
        Mockito.when(terminal.reader()).thenReturn(reader);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump,
                new ManualFrameTicker());
             var inputExecutor = Executors.newSingleThreadExecutor()) {
            ApprovalRequest request = ApprovalRequest.of(
                    "write_file", "{\"path\":\"a.txt\"}", "test", "task_7 · reviewer");
            var result = inputExecutor.submit(() -> renderer.promptApproval(request));

            assertFalse(readStarted.await(150, TimeUnit.MILLISECONDS));
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            assertFalse(result.isDone());

            pump.pump();
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            String visible = AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("需要审批"), visible);
            assertTrue(visible.contains("task_7 · reviewer"), visible);
            assertFalse(writerThread.get() == readerThread.get());

            sink.reset();
            var answer = renderer.beginAnswer();
            answer.append("render loop remains live during approval\n");
            pump.pump();
            String concurrentFrame = AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(concurrentFrame.contains("render loop remains live during approval"),
                    concurrentFrame);
            assertFalse(result.isDone());

            releaseRead.countDown();
            assertEquals(ApprovalResult.Decision.APPROVED,
                    result.get(5, TimeUnit.SECONDS).decision());
            sink.reset();
            pump.pump();
            assertFalse(AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8))
                    .contains("需要审批"));
            assertEquals(1, renderer.retainedWindowSnapshot().turns().size());
            assertEquals(1, renderer.retainedWindowSnapshot().turns().getFirst().blockCount(),
                    "the concurrent answer is retained, but the ephemeral approval is not");
        } finally {
            releaseRead.countDown();
        }
    }

    private static final class RecordingPrintWriter extends PrintWriter {
        private final AtomicLong writerThread;

        private RecordingPrintWriter(ByteArrayOutputStream sink, AtomicLong writerThread) {
            super(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
            this.writerThread = writerThread;
        }

        @Override
        public void write(String value) {
            writerThread.set(Thread.currentThread().threadId());
            super.write(value);
        }
    }
}
