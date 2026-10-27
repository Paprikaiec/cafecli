package com.cafecli.render.inline;

import com.cafecli.render.ApprovalRequest;
import com.cafecli.render.Renderer;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批卡由渲染线程独家绘制、更新、移除，**而渲染器从不读终端**（issue 07）。
 *
 * <p>这条纪律没有变，变的是它现在**结构上不可违反**：审批的「问」与「答」被拆到两条线程——卡片经
 * {@link Renderer#showApproval} 交给渲染循环，答案经[[输入框]]提交、由常驻行编辑器读键、由
 * {@code InteractiveRepl} 路由。此前 {@code InlineRenderer.promptApproval} 自己在输入线程上抢一次
 * raw-mode 单键读取（退役掉的 {@code InlineApprovalPrompter}），与常驻读取器争同一个
 * {@code NonBlockingReader}。
 */
class InlineApprovalRenderThreadTest {

    @Test
    void theCardIsDrawnByTheRenderThreadAndNotOneByteBeforeAFrameRuns() {
        Fixture f = new Fixture();
        try (InlineRenderer renderer = f.renderer()) {
            renderer.showApproval(
                    ApprovalRequest.of("write_file", "{}", "test", "main Agent"), "");

            assertEquals("", f.sink.toString(StandardCharsets.UTF_8),
                    "呈现一张卡片只是投一个事件：帧没跑之前一个字节都不该上屏");

            f.pump.pump();

            String visible = AttributedString.stripAnsi(f.sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("需要审批"), visible);
            assertTrue(visible.contains("[y] approve"), visible);
        }
        Mockito.verify(f.terminal, Mockito.never()).reader();
        Mockito.verify(f.terminal, Mockito.never()).enterRawMode();
    }

    /**
     * 卡片立着的时候渲染循环照常跑，撤掉之后它就从活区消失——请求方 park 在 future 上，屏幕不为它停。
     *
     * <p>整段过程里 {@code terminal.reader()} 一次都没被要过：这正是「渲染线程上任何终端读取都是死锁
     * bug」这条纪律在拆分之后的形状——不是「没有读」，而是**没有可读的地方**。
     */
    @Test
    void theRenderLoopStaysLiveWhileTheCardIsUpAndNeverReadsTheTerminal() {
        Fixture f = new Fixture();
        try (InlineRenderer renderer = f.renderer()) {
            ApprovalRequest request = ApprovalRequest.of(
                    "write_file", "{\"path\":\"a.txt\"}", "test", "task_7 · reviewer");
            renderer.showApproval(request, "");
            f.pump.pump();

            String visible = AttributedString.stripAnsi(f.sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("task_7 · reviewer"), visible);

            // 无法识别的答案带一句提示重问——同一张卡片，不是新的一张。
            f.sink.reset();
            renderer.showApproval(request, "无法识别的回答「z」；请用 y / n / a（或 s / m）");
            f.pump.pump();
            assertTrue(AttributedString.stripAnsi(f.sink.toString(StandardCharsets.UTF_8))
                    .contains("无法识别的回答"), f.sink.toString(StandardCharsets.UTF_8));

            f.sink.reset();
            Renderer.BlockWriter answer = renderer.beginAnswer();
            answer.append("render loop remains live during approval\n");
            f.pump.pump();
            assertTrue(AttributedString.stripAnsi(f.sink.toString(StandardCharsets.UTF_8))
                    .contains("render loop remains live during approval"),
                    f.sink.toString(StandardCharsets.UTF_8));

            f.sink.reset();
            renderer.dismissApproval();
            f.pump.pump();
            assertFalse(AttributedString.stripAnsi(f.sink.toString(StandardCharsets.UTF_8))
                    .contains("需要审批"),
                    "答完之后卡片必须离开活区：" + f.sink.toString(StandardCharsets.UTF_8));
        }
        Mockito.verify(f.terminal, Mockito.never()).reader();
        assertFalse(f.writerThread.get() == Thread.currentThread().threadId(),
                "终端写入只发生在渲染线程上");
    }

    private static final class Fixture {
        final Terminal terminal = Mockito.mock(Terminal.class);
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final ManualFramePump pump = new ManualFramePump();
        final AtomicLong writerThread = new AtomicLong();
        private final PrintWriter writer;

        Fixture() {
            Mockito.when(terminal.getType()).thenReturn("xterm-256color");
            Size size = new Size(80, 30);
            Mockito.when(terminal.getSize()).thenReturn(size);
            Mockito.when(terminal.getBufferSize()).thenReturn(size);
            writer = new RecordingPrintWriter(sink, writerThread);
            Mockito.when(terminal.writer()).thenReturn(writer);
            Mockito.doAnswer(invocation -> {
                writer.flush();
                return null;
            }).when(terminal).flush();
        }

        InlineRenderer renderer() {
            return new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8), pump,
                    new ManualFrameTicker());
        }
    }

    /** Records which thread actually wrote to the terminal. */
    private static final class RecordingPrintWriter extends PrintWriter {
        private final AtomicLong writerThread;

        RecordingPrintWriter(ByteArrayOutputStream sink, AtomicLong writerThread) {
            super(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
            this.writerThread = writerThread;
        }

        @Override
        public void write(String s, int off, int len) {
            writerThread.set(Thread.currentThread().threadId());
            super.write(s, off, len);
        }
    }
}
