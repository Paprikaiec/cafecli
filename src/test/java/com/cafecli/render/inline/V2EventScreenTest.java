package com.cafecli.render.inline;

import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.message.*;
import com.cafecli.core.*;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.render.inline.replay.TerminalScreenModel;
import com.cafecli.ui.EventRenderAdapter;
import org.jline.terminal.Size;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.cafecli.render.inline.replay.TerminalScreenModel.*;

class V2EventScreenTest {
    private static final AgentEvent.Origin MAIN = new AgentEvent.Origin("main");
    @Test void inputRemainsWhileStreamingAndCompletedToolAndAnswerLeaveNoLiveCopies() throws Exception {
        var sink = new ByteArrayOutputStream();
        var pump = new ManualFramePump();
        var input = new PipedInputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = new TracingTerminal("v2-screen","xterm-256color",input,sink)) {
            terminal.setSize(new Size(80,24));
            try (var renderer = new InlineRenderer(terminal,new PrintStream(sink,false,StandardCharsets.UTF_8),
                    pump,new ManualFrameTicker(),new ManualClock())) {
                renderer.start();
                renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                        List.of(new AttributedString("› draft survives")),3,80,false));
                var ui = new EventRenderAdapter(renderer,"fake-model",10000);
                pump.drain();
                assertEquals(1,rowsContaining(document(sink),"draft survives"));
                ui.accept(new AgentEvent.RunStart(MAIN));
                ui.accept(new AgentEvent.MessageStart(MAIN));
                ui.accept(new AgentEvent.MessageUpdate(MAIN,new StreamEvent.ThinkingDelta(0,"considering\n")));
                pump.drain();
                assertEquals(1,rowsContaining(document(sink),"draft survives"));
                ui.accept(new AgentEvent.MessageUpdate(MAIN,new StreamEvent.TextDelta(1,"I will read.\n")));
                var call = new ToolCall("read","read_file","{\"path\":\"README.md\"}");
                ui.accept(new AgentEvent.MessageEnd(MAIN,new AssistantMessage(List.of(new ThinkingBlock("considering\n",null),
                        new TextBlock("I will read.\n"),call),StopReason.TOOL_USE,Usage.ZERO,"fake","fake-model")));
                pump.drain();
                ui.accept(new AgentEvent.ToolEnd(MAIN,new ToolResultEntry(new ToolResultMessage("read","read_file",
                        List.of(new TextBlock("file result")),false))));
                ui.accept(new AgentEvent.IterationEnd(MAIN,1));
                ui.accept(new AgentEvent.MessageStart(MAIN));
                ui.accept(new AgentEvent.MessageUpdate(MAIN,new StreamEvent.TextDelta(0,"Final answer.\n")));
                var answer = new AssistantMessage(List.of(new TextBlock("Final answer.\n")),StopReason.END_TURN,Usage.ZERO,"fake","fake-model");
                ui.accept(new AgentEvent.MessageEnd(MAIN,answer));
                ui.accept(new AgentEvent.IterationEnd(MAIN,2));
                ui.accept(new AgentEvent.RunEnd(MAIN,new RunOutcome.Completed(answer)));
                pump.drain();
                for (int i=0;i<3;i++) { renderer.beforeInput(); pump.drain(); }
                var doc = document(sink);
                assertEquals(1,rowsContaining(doc,"ReadFile(README.md)"),dump(doc));
                assertEquals(1,rowsContaining(doc,"file result"),dump(doc));
                assertEquals(1,rowsContaining(doc,"Final answer."),dump(doc));
                assertEquals(1,rowsContaining(doc,"draft survives"),dump(doc));
                assertEquals(0,rowsContaining(doc,"Thinking..."),dump(doc));
                assertEquals(0,blankRowsBetween(doc,"ReadFile(README.md)","file result"),dump(doc));
            }
        }
    }
    private static List<String> document(ByteArrayOutputStream sink) {
        var screen = new TerminalScreenModel(80,24); screen.feed(sink.toByteArray()); return screen.document();
    }
}
