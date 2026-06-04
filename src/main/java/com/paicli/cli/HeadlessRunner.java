package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;

/**
 * `paicli run` 的核心组装：读 issue 文件 → 跑 Agent → 可选写 trace → 返回最终回答。
 * 与 Main 解耦以便单测（不碰 System.exit / argv）。
 *
 * <p>注意：{@link com.paicli.agent.Agent#run(String)} 内部会捕获 LLM 调用失败并以
 * "❌ ..." 错误字符串返回，不会抛 IOException；本方法声明的 IOException 仅来自 issue
 * 文件读取与 trace 文件写入。调用方若需区分 agent 内部失败，应检查返回字符串内容，
 * 而不是依赖异常传播。
 */
public final class HeadlessRunner {

    private HeadlessRunner() {}

    public static String run(RunCommand cmd, Agent agent, LlmClient client) throws IOException {
        String issue = Files.readString(cmd.issueFile());
        long startNanos = System.nanoTime();
        String answer = agent.run(issue);
        long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (cmd.traceFile() != null) {
            try (Writer w = Files.newBufferedWriter(cmd.traceFile())) {
                AgentTraceWriter.write(w, agent.getConversationHistory(), agent.getLastRunUsage(),
                        client.getProviderName(), client.getModelName(), wallMs);
            }
        }
        return answer;
    }
}
