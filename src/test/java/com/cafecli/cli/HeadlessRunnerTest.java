package com.cafecli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.agent.Agent;
import com.cafecli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class HeadlessRunnerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readsIssueRunsAgentAndWritesTrace() throws IOException {
        String old = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        try {
            Path issue = tempDir.resolve("issue.md");
            Files.writeString(issue, "Resolve: NullPointerException in Parser.parse");
            Path trace = tempDir.resolve("trace.jsonl");

            RecordingClient client = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "patched it", null, 10, 2)));
            Agent agent = new Agent(client);

            String answer = HeadlessRunner.run(new RunCommand(issue, trace), agent, client);

            assertNotNull(answer);
            assertFalse(answer.isBlank(), "agent 应返回非空回答");
            assertTrue(Files.exists(trace), "trace 文件应被写出");
            String[] lines = Files.readString(trace).strip().split("\n");
            assertEquals("system", M.readTree(lines[0]).get("type").asText());
            assertEquals("result", M.readTree(lines[lines.length - 1]).get("type").asText());
            assertEquals("deepseek-v4-pro", M.readTree(lines[lines.length - 1]).get("model").asText());
        } finally {
            if (old == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", old);
            }
        }
    }

    @Test
    void noTraceFileStillReturnsAnswer() throws IOException {
        String old = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        try {
            Path issue = tempDir.resolve("issue2.md");
            Files.writeString(issue, "Resolve: off-by-one in Range.contains");
            RecordingClient client = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "done", null, 5, 1)));
            Agent agent = new Agent(client);

            String answer = HeadlessRunner.run(new RunCommand(issue, null), agent, client);

            assertNotNull(answer);
            assertFalse(answer.isBlank(), "agent 应返回非空回答");
        } finally {
            if (old == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", old);
            }
        }
    }

    @Test
    void reasoningAndAnswerReturnWithoutADetachedAnswerMarker() throws IOException {
        String old = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        try {
            Path issue = tempDir.resolve("reasoning-issue.md");
            Files.writeString(issue, "Explain the fix");
            RecordingClient client = new RecordingClient(List.of(
                    new LlmClient.ChatResponse(
                            "assistant", "final answer", "checked the implementation",
                            null, 5, 1)));
            Agent agent = new Agent(client);

            String answer = HeadlessRunner.run(new RunCommand(issue, null), agent, client);

            assertEquals("🧠 思考过程:\nchecked the implementation\n\nfinal answer", answer);
            assertFalse(answer.contains("▪"), answer);
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
