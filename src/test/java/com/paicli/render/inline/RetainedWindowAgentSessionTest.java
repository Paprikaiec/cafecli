package com.paicli.render.inline;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentSession;
import com.paicli.agent.task.TaskRegistry;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.tool.ToolRegistry;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedWindowAgentSessionTest {

    @Test
    void backgroundPushAutonomousTurnEntersRetainedWindow() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            Agent mainAgent = new Agent(Mockito.mock(LlmClient.class), new ToolRegistry());
            AgentSession session = new AgentSession(mainAgent, new TaskRegistry(), ignored -> { });
            session.setAutonomousTurnStarter(renderer::beginTurn);
            session.setTurnRunner(input -> {
                Renderer.BlockWriter answer = renderer.beginAnswer();
                answer.append("autonomous push handled\n");
                answer.finish();
                renderer.endTurn();
                return "";
            });
            session.start();
            try {
                session.postEvent(new AgentSession.TaskDone("task_7", "background result"));
                assertTrue(session.awaitIdle(Duration.ofSeconds(5)));
                pump.pump();

                InlineRenderer.RetainedWindowSnapshot snapshot = renderer.retainedWindowSnapshot();
                assertEquals(1, snapshot.turns().size());
                assertEquals(1, snapshot.turns().get(0).blockCount());
            } finally {
                session.stop();
            }
        }
    }
}
