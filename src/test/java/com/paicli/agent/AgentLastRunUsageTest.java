package com.paicli.agent;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLastRunUsageTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesTokenUsageAfterRun() {
        String old = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        try {
            RecordingClient client = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "done", null, 1234, 56)));
            Agent agent = new Agent(client);

            agent.run("Resolve: NullPointerException in Parser.parse");

            Agent.TokenUsage usage = agent.getLastRunUsage();
            assertEquals(1234, usage.inputTokens());
            assertEquals(56, usage.outputTokens());
            assertEquals(0, usage.cachedInputTokens());
        } finally {
            if (old == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", old);
            }
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        RecordingClient(List<ChatResponse> responses) { this.responses = new ArrayDeque<>(responses); }
        @Override public ChatResponse chat(List<Message> m, List<Tool> t) throws IOException {
            return chat(m, t, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> m, List<Tool> t, StreamListener l) throws IOException {
            ChatResponse r = responses.poll();
            if (r == null) throw new IOException("no preset response");
            return r;
        }
        @Override public String getModelName() { return "deepseek-v4-pro"; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 256_000; }
    }
}
