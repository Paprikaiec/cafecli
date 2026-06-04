# PaiCLI `run` Headless 子命令 + Trace 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 PaiCLI 增加 `paicli run --file <issue.md> [--trace-file <trace.jsonl>]` 同步无人值守子命令，跑完一条任务后退出，并可选地把完整对话 + tool use 落成对齐 Claude Code stream-json 语义的 NDJSON trace。

**Architecture:** 复用现有 headless 装配（裸 `ToolRegistry` = 工具自动放行 + 保留 PathGuard/CommandGuard，`new Agent(client, registry)`，`agent.run(prompt)→String`）。新增三个 `com.paicli.cli` 小类（argv 解析 / trace 序列化 / 组装执行）+ 给 `Agent` 加一个只读 token getter，最后在 `Main.main` 早退分支接线。墙钟超时不在 PaiCLI 内实现，由 Part 2 编排器用 shell `timeout` 在 `docker run` 外层兜底。

**Tech Stack:** Java 17、JUnit 5（Jupiter）、Jackson（`com.fasterxml.jackson.databind`）、Maven。注意本仓库默认 `mvn test` 跳过测试，跑测试要带 `-DskipTests=false`。

**Scope:** 本 plan 仅覆盖 spec 的 **Part 1（PaiCLI 侧源码改动）**。Part 2（`run_eval.py` / `solve_one.sh` / `normalize_trace.py` / `summarize.py` + Docker + 官方 harness 编排）是独立 plan，在云机环境迭代。

---

## File Structure

**新建（生产代码，`src/main/java/com/paicli/cli/`）：**
- `RunCommand.java` — argv → `RunCommand(issueFile, traceFile)` record + 静态 `parse`
- `AgentTraceWriter.java` — `List<LlmClient.Message>` + 元数据 → NDJSON（纯序列化，无 IO 副作用以外逻辑）
- `HeadlessRunner.java` — 组装：读 issue 文件 → `agent.run` → 写 trace → 返回最终回答

**修改（生产代码）：**
- `src/main/java/com/paicli/agent/Agent.java` — 加 `TokenUsage` 嵌套 record + 字段 + `getLastRunUsage()`，在正常完成路径回填
- `src/main/java/com/paicli/cli/Main.java` — `main` 早退加 `run` 分支 + `isRunCommand` + `runHeadlessCommand`

**新建（测试，`src/test/java/com/paicli/cli/` 与 `.../agent/`）：**
- `RunCommandTest.java` / `AgentTraceWriterTest.java` / `HeadlessRunnerTest.java` / `AgentLastRunUsageTest.java`

**文档（按 AGENTS.md 硬规则 #2）：** `README.md` + `AGENTS.md`

---

## Task 1: `RunCommand` argv 解析

**Files:**
- Create: `src/main/java/com/paicli/cli/RunCommand.java`
- Test: `src/test/java/com/paicli/cli/RunCommandTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/paicli/cli/RunCommandTest.java
package com.paicli.cli;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RunCommandTest {

    @Test
    void parsesFileAndTraceFile() {
        RunCommand cmd = RunCommand.parse(
                new String[]{"run", "--file", "/tmp/issue.md", "--trace-file", "/tmp/t.jsonl"});
        assertEquals(Path.of("/tmp/issue.md"), cmd.issueFile());
        assertEquals(Path.of("/tmp/t.jsonl"), cmd.traceFile());
    }

    @Test
    void traceFileIsOptional() {
        RunCommand cmd = RunCommand.parse(new String[]{"run", "--file", "/tmp/issue.md"});
        assertEquals(Path.of("/tmp/issue.md"), cmd.issueFile());
        assertNull(cmd.traceFile());
    }

    @Test
    void missingFileThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parse(new String[]{"run", "--trace-file", "/tmp/t.jsonl"}));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RunCommandTest -DskipTests=false`
Expected: 编译失败（`RunCommand` 不存在）。

- [ ] **Step 3: 写最小实现**

```java
// src/main/java/com/paicli/cli/RunCommand.java
package com.paicli.cli;

import java.nio.file.Path;
import java.util.Locale;

/** `paicli run` 子命令的参数：issue 文件（必填）+ trace 输出文件（可选）。 */
public record RunCommand(Path issueFile, Path traceFile) {

    public static RunCommand parse(String[] args) {
        Path issueFile = null;
        Path traceFile = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--file" -> {
                    if (i + 1 < args.length) {
                        issueFile = Path.of(args[++i]);
                    }
                }
                case "--trace-file" -> {
                    if (i + 1 < args.length) {
                        traceFile = Path.of(args[++i]);
                    }
                }
                default -> { /* 忽略未知参数，保持向前兼容 */ }
            }
        }
        if (issueFile == null) {
            throw new IllegalArgumentException("paicli run 需要 --file <issue.md>");
        }
        return new RunCommand(issueFile, traceFile);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=RunCommandTest -DskipTests=false`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/paicli/cli/RunCommand.java src/test/java/com/paicli/cli/RunCommandTest.java
git commit -m "$(cat <<'EOF'
Add RunCommand argv parser for paicli run

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `Agent.getLastRunUsage()` token getter

**Files:**
- Modify: `src/main/java/com/paicli/agent/Agent.java`（加嵌套 record + 字段 + getter + 回填）
- Test: `src/test/java/com/paicli/agent/AgentLastRunUsageTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/paicli/agent/AgentLastRunUsageTest.java
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=AgentLastRunUsageTest -DskipTests=false`
Expected: 编译失败（`Agent.TokenUsage` / `getLastRunUsage` 不存在）。

- [ ] **Step 3: 写最小实现**

在 `Agent.java` 类体内（与其它 `private final` 字段相邻处，如第 57 行附近）加字段与 getter：

```java
    /** 最近一次 run 的 LLM token 用量（仅正常完成路径回填，供 headless trace 使用）。 */
    public record TokenUsage(long inputTokens, long outputTokens, long cachedInputTokens) {
        public static TokenUsage zero() { return new TokenUsage(0, 0, 0); }
    }

    private volatile TokenUsage lastRunUsage = TokenUsage.zero();

    public TokenUsage getLastRunUsage() {
        return lastRunUsage;
    }
```

在 `run(String)` 的正常完成路径回填 —— 紧接现有第 230 行
`memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());`
之后插入一行：

```java
                this.lastRunUsage = new TokenUsage(
                        budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
```

（该位置在 `pushStatus(budget, startNanos, "idle")` 之前，覆盖第 243 行 `return ""` 与第 246 行 `return formatUserFacingResponse(...)` 两个正常 return 分支。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=AgentLastRunUsageTest -DskipTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/paicli/agent/Agent.java src/test/java/com/paicli/agent/AgentLastRunUsageTest.java
git commit -m "$(cat <<'EOF'
Expose Agent.getLastRunUsage for headless trace

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `AgentTraceWriter` NDJSON 序列化

**Files:**
- Create: `src/main/java/com/paicli/cli/AgentTraceWriter.java`
- Test: `src/test/java/com/paicli/cli/AgentTraceWriterTest.java`

事件语义对齐 Claude Code stream-json：`system` / `user` / `assistant`（含 `tool_calls` = tool_use）/ `tool_result` / 末行 `result`（含 provider、model、wall_ms、tokens、turns）。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/paicli/cli/AgentTraceWriterTest.java
package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClient.Message;
import com.paicli.llm.LlmClient.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentTraceWriterTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void writesConversationAndToolUseAsNdjson() throws IOException {
        List<Message> history = List.of(
                Message.system("SYS PROMPT"),
                Message.user("fix the bug"),
                Message.assistant("", List.of(
                        new ToolCall("call_1", new ToolCall.Function("read_file", "{\"path\":\"A.java\"}")))),
                Message.tool("call_1", "file content"),
                Message.assistant("done"));

        StringWriter sw = new StringWriter();
        AgentTraceWriter.write(sw, history, new Agent.TokenUsage(100, 20, 5),
                "deepseek", "deepseek-v4-pro", 1234L);

        String[] lines = sw.toString().strip().split("\n");
        assertEquals(6, lines.length);

        assertEquals("system", M.readTree(lines[0]).get("type").asText());
        assertEquals("user", M.readTree(lines[1]).get("type").asText());

        JsonNode toolUse = M.readTree(lines[2]);
        assertEquals("assistant", toolUse.get("type").asText());
        assertEquals("read_file", toolUse.get("tool_calls").get(0).get("name").asText());
        assertEquals("{\"path\":\"A.java\"}", toolUse.get("tool_calls").get(0).get("arguments").asText());

        JsonNode toolResult = M.readTree(lines[3]);
        assertEquals("tool_result", toolResult.get("type").asText());
        assertEquals("call_1", toolResult.get("tool_call_id").asText());
        assertEquals("file content", toolResult.get("content").asText());

        JsonNode result = M.readTree(lines[5]);
        assertEquals("result", result.get("type").asText());
        assertEquals("deepseek-v4-pro", result.get("model").asText());
        assertEquals(1234, result.get("wall_ms").asLong());
        assertEquals(100, result.get("tokens").get("in").asInt());
        assertEquals(2, result.get("turns").asInt());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=AgentTraceWriterTest -DskipTests=false`
Expected: 编译失败（`AgentTraceWriter` 不存在）。

- [ ] **Step 3: 写最小实现**

```java
// src/main/java/com/paicli/cli/AgentTraceWriter.java
package com.paicli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient.Message;
import com.paicli.llm.LlmClient.ToolCall;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次 headless run 的 conversationHistory + 元数据序列化成 NDJSON。
 * 每条消息一行，末尾追加一行 result 摘要；事件语义对齐 Claude Code stream-json，
 * 便于 Part 2 的 normalizer 统一两臂日志。
 */
public final class AgentTraceWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTraceWriter() {}

    public static void write(Writer out, List<Message> history, Agent.TokenUsage usage,
                             String provider, String model, long wallMs) throws IOException {
        for (Message m : history) {
            out.write(MAPPER.writeValueAsString(toEvent(m)));
            out.write("\n");
        }
        out.write(MAPPER.writeValueAsString(summary(history, usage, provider, model, wallMs)));
        out.write("\n");
    }

    private static Map<String, Object> toEvent(Message m) {
        Map<String, Object> ev = new LinkedHashMap<>();
        switch (m.role()) {
            case "system" -> { ev.put("type", "system"); ev.put("text", m.content()); }
            case "user" -> { ev.put("type", "user"); ev.put("text", m.content()); }
            case "tool" -> {
                ev.put("type", "tool_result");
                ev.put("tool_call_id", m.toolCallId());
                ev.put("content", m.content());
            }
            case "assistant" -> {
                ev.put("type", "assistant");
                ev.put("text", m.content());
                if (m.reasoningContent() != null && !m.reasoningContent().isBlank()) {
                    ev.put("reasoning", m.reasoningContent());
                }
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (ToolCall tc : m.toolCalls()) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("id", tc.id());
                        c.put("name", tc.function().name());
                        c.put("arguments", tc.function().arguments());
                        calls.add(c);
                    }
                    ev.put("tool_calls", calls);
                }
            }
            default -> { ev.put("type", m.role()); ev.put("text", m.content()); }
        }
        return ev;
    }

    private static Map<String, Object> summary(List<Message> history, Agent.TokenUsage usage,
                                               String provider, String model, long wallMs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "result");
        s.put("provider", provider);
        s.put("model", model);
        s.put("wall_ms", wallMs);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("in", usage.inputTokens());
        tokens.put("out", usage.outputTokens());
        tokens.put("cache", usage.cachedInputTokens());
        s.put("tokens", tokens);
        long turns = history.stream().filter(m -> "assistant".equals(m.role())).count();
        s.put("turns", turns);
        return s;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=AgentTraceWriterTest -DskipTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/paicli/cli/AgentTraceWriter.java src/test/java/com/paicli/cli/AgentTraceWriterTest.java
git commit -m "$(cat <<'EOF'
Add AgentTraceWriter NDJSON serializer aligned to stream-json

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `HeadlessRunner` 组装执行

**Files:**
- Create: `src/main/java/com/paicli/cli/HeadlessRunner.java`
- Test: `src/test/java/com/paicli/cli/HeadlessRunnerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/paicli/cli/HeadlessRunnerTest.java
package com.paicli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=HeadlessRunnerTest -DskipTests=false`
Expected: 编译失败（`HeadlessRunner` 不存在）。

- [ ] **Step 3: 写最小实现**

```java
// src/main/java/com/paicli/cli/HeadlessRunner.java
package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;

/**
 * `paicli run` 的核心组装：读 issue 文件 → 跑 Agent → 可选写 trace → 返回最终回答。
 * 与 Main 解耦以便单测（不碰 System.exit / argv）。
 */
public final class HeadlessRunner {

    private HeadlessRunner() {}

    public static String run(RunCommand cmd, Agent agent, LlmClient client) throws IOException {
        long startNanos = System.nanoTime();
        String issue = Files.readString(cmd.issueFile());
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=HeadlessRunnerTest -DskipTests=false`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/paicli/cli/HeadlessRunner.java src/test/java/com/paicli/cli/HeadlessRunnerTest.java
git commit -m "$(cat <<'EOF'
Add HeadlessRunner assembling run -> agent -> trace

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `Main` 接线 `run` 子命令 + 文档联动

**Files:**
- Modify: `src/main/java/com/paicli/cli/Main.java`
- Modify: `README.md`、`AGENTS.md`

`main` 早退接线无法纯单元测（涉及 `System.exit`），核心逻辑已在 Task 1–4 覆盖；本任务用「编译 + 不依赖 LLM 的烟雾验收」确认接线正确。

- [ ] **Step 1: 在 `Main.main` 加 `run` 早退分支**

在 `Main.java` 第 198 行（serve 早退块的 `}` 之后、第 200 行 `configureLogging();` 之前）插入：

```java
        if (isRunCommand(args)) {
            configureLogging();
            runHeadlessCommand(args);
            return;
        }
```

- [ ] **Step 2: 加 `isRunCommand` 与 `runHeadlessCommand`**

在 `Main.java` 内（紧邻现有 `isRuntimeServeCommand` / `runHeadlessTask` 方法处，约第 775–834 行区域）加：

```java
    static boolean isRunCommand(String[] args) {
        return args != null && args.length >= 1 && "run".equalsIgnoreCase(args[0]);
    }

    private static void runHeadlessCommand(String[] args) {
        RunCommand cmd;
        try {
            cmd = RunCommand.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("❌ " + e.getMessage());
            System.exit(2);
            return;
        }
        PaiCliConfig config = PaiCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("❌ 未找到可用的 API Key");
            System.exit(1);
            return;
        }
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        Agent agent = new Agent(client, registry);
        try {
            String answer = HeadlessRunner.run(cmd, agent, client);
            if (answer != null && !answer.isBlank()) {
                System.out.println(answer);
            }
        } catch (IOException e) {
            System.err.println("❌ 运行失败: " + e.getMessage());
            System.exit(1);
        }
    }
```

确认 `Main.java` 顶部已 import（多数已存在，缺则补）：`com.paicli.agent.Agent`、`com.paicli.tool.ToolRegistry`、`com.paicli.llm.LlmClient`、`com.paicli.llm.LlmClientFactory`、`java.io.IOException`、`java.nio.file.Path`。

- [ ] **Step 3: 编译 + 全量快速回归（确认没破坏现有行为）**

Run: `mvn clean package` 然后 `mvn test -Pquick -DskipTests=false`
Expected: 编译通过、jar 产出；quick 回归全绿。

- [ ] **Step 4: 不依赖 LLM 的烟雾验收**

```bash
# 缺 --file → 退出码 2
java -jar target/paicli-1.0-SNAPSHOT.jar run --trace-file /tmp/t.jsonl; echo "exit=$?"
```
Expected: stderr 打印 "❌ paicli run 需要 --file <issue.md>"，`exit=2`。

```bash
# 有 --file 但环境无任何 API Key → 退出码 1（验证装配链路而非真跑 LLM）
printf 'Resolve: NPE in Parser.parse' > /tmp/issue.md
env -u GLM_API_KEY -u DEEPSEEK_API_KEY -u STEP_API_KEY -u KIMI_API_KEY -u FREELLMAPI_API_KEY \
  java -jar target/paicli-1.0-SNAPSHOT.jar run --file /tmp/issue.md; echo "exit=$?"
```
Expected: stderr 打印 "❌ 未找到可用的 API Key"，`exit=1`。
（注：若 `.env` 内含 key，PaiCliConfig 仍会加载到，此步可能转为真跑；此时改在临时空目录执行，或跳过本子步，真跑验收留给 Part 2。）

- [ ] **Step 5: 文档联动 + 提交**

在 `AGENTS.md` 的「常用命令」代码块追加一行：
```bash
java -jar target/paicli-1.0-SNAPSHOT.jar run --file issue.md --trace-file trace.jsonl  # 无人值守单任务 + NDJSON trace
```
并在「架构概览」末尾补一句：
> `run` 子命令是同步无人值守入口：复用裸 `ToolRegistry`（工具自动放行 + 保留策略层）跑一次 `Agent.run`，可选输出对齐 stream-json 语义的 NDJSON trace；墙钟超时由外层编排（shell `timeout`）兜底。

在 `README.md` 中找到介绍启动/命令的章节，加入等价的 `paicli run` 用法说明（一段 + 上面的命令示例）。

```bash
git add src/main/java/com/paicli/cli/Main.java README.md AGENTS.md
git commit -m "$(cat <<'EOF'
Wire paicli run headless subcommand into Main

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## 自审记录

**Spec coverage（对照 spec §4.1 臂 A）：**
- `paicli run --file ... --trace-file ...` 同步子命令 → Task 1（解析）+ Task 4（组装）+ Task 5（接线）✓
- 复用交互模式装配（ToolRegistry + LlmClientFactory 选 DeepSeek + Agent）→ Task 4/5 复用 `runHeadlessTask` 同款装配 ✓
- HITL 自动放行 → 用裸 `ToolRegistry`（非 HitlToolRegistry），天然无审批拦截、保留 PathGuard/CommandGuard ✓
- raw 全量 NDJSON、对齐 stream-json 语义、记录全部工具 → Task 3（system/user/assistant+tool_use/tool_result/result）✓
- token 用量 → Task 2 `getLastRunUsage` + Task 3 summary.tokens ✓
- 正常 exit 0 / 异常非零 → Task 5（缺参 2、无 key 1、IO 失败 1）✓
- 文档联动（AGENTS.md #2）→ Task 5 ✓
- **范围外（移交说明）**：`--max-turns` 沿用 Agent 现有 `AgentBudget` 硬轮数上限，第一版不单独暴露；`--timeout-seconds` 改由 Part 2 编排器 shell `timeout` 兜底 —— 已在 spec §4.1 与本 plan Architecture 注明，非遗漏。

**Placeholder 扫描：** 无 TBD/TODO；每个代码步骤含完整可编译代码与确切命令/预期。

**Type consistency：** `RunCommand(Path,Path)`、`Agent.TokenUsage(long,long,long)` 的 `inputTokens()/outputTokens()/cachedInputTokens()`、`AgentTraceWriter.write(Writer, List<Message>, Agent.TokenUsage, String, String, long)`、`HeadlessRunner.run(RunCommand, Agent, LlmClient)` 在各 Task 与测试间签名一致；`Message`/`ToolCall.Function` 访问器名与 `LlmClient.java` 一致。
