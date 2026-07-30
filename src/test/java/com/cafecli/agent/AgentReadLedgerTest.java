package com.cafecli.agent;

import com.cafecli.llm.LlmClient;
import com.cafecli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 6：{@code Agent.run()} + 脚本化 LLM 客户端，验证 Agent 构造期真的接线了读前写台账
 * （PreToolUse + PostToolUse 注册 + 分发器注入 registry），且 PreToolUse BLOCK 的原因回到模型下一轮请求。
 */
class AgentReadLedgerTest {

    private static final String PRE = "cafecli.hook.pre_tool_use.enabled";
    private static final String POST = "cafecli.hook.post_tool_use.enabled";
    private static final String LEDGER = "cafecli.hook.read_ledger.enabled";

    @AfterEach
    void clearProps() {
        System.clearProperty(PRE);
        System.clearProperty(POST);
        System.clearProperty(LEDGER);
    }

    private static LlmClient.ToolCall writeFile(String path) {
        return new LlmClient.ToolCall("w1", new LlmClient.ToolCall.Function(
                "write_file", "{\"path\":\"" + path + "\",\"content\":\"blind\"}"));
    }

    private static LlmClient.ChatResponse toolResp(LlmClient.ToolCall call) {
        return new LlmClient.ChatResponse("assistant", "", List.of(call), 10, 2);
    }

    private static LlmClient.ChatResponse finishResp(String text) {
        return new LlmClient.ChatResponse("assistant", text, null, 10, 2);
    }

    @Test
    void blindWriteBlockedAndReasonReachesNextRequest(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Foo.java"), "class Foo {}");
        System.setProperty(PRE, "true");
        System.setProperty(POST, "true");
        System.setProperty(LEDGER, "true");

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Foo.java")),   // 1: 盲写（没读过）-> 台账阻断
                finishResp("好的，我先去读文件。")));  // 2: 看到阻断结果后收尾

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);
        agent.run("直接改 Foo.java");

        // 工具没执行：文件内容保持原样
        assertEquals("class Foo {}", Files.readString(tempDir.resolve("Foo.java")));

        // 第 2 次请求历史里，盲写的 tool 结果带着台账阻断原因回到了模型
        List<LlmClient.Message> secondCall = llm.messagesByCall.get(1);
        assertTrue(secondCall.stream()
                        .filter(m -> "tool".equals(m.role()) && m.content() != null)
                        .anyMatch(m -> m.content().contains("还没读过") || m.content().contains("PreToolUse")),
                "台账 BLOCK 原因应作为工具结果回到下一轮请求");
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
