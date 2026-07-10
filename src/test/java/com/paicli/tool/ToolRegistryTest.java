package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.browser.BrowserConnector;
import com.paicli.lsp.LspManager;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.policy.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void shouldReadSmallFileWithoutRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Small.txt");
        Files.writeString(file, "alpha\nbeta\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Small.txt\"}");

        assertTrue(result.contains("文件内容:\nalpha\nbeta"), result);
    }

    @Test
    void shouldRejectOversizedUnrangedReadAndNudgeToPreciseReads(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.read_file.max_unranged_bytes");
        System.setProperty("paicli.read_file.max_unranged_bytes", "64");
        try {
            Path file = tempDir.resolve("Large.txt");
            Files.writeString(file, String.join("\n",
                    "line 1: alpha",
                    "line 2: beta",
                    "line 3: gamma",
                    "line 4: delta",
                    "line 5: epsilon"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("read_file", "{\"path\":\"Large.txt\"}");

            assertTrue(result.contains("读取文件失败"), result);
            assertTrue(result.contains("文件过大"), result);
            assertTrue(result.contains("offset/limit"), result);
            assertTrue(result.contains("grep_code"), result);
            assertTrue(result.contains("jq"), result);
            assertFalse(result.contains("line 5: epsilon"), result);
        } finally {
            restoreSystemProperty("paicli.read_file.max_unranged_bytes", previous);
        }
    }

    @Test
    void shouldStillPageOversizedFileWhenRangeIsRequested(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.read_file.max_unranged_bytes");
        System.setProperty("paicli.read_file.max_unranged_bytes", "64");
        try {
            Path file = tempDir.resolve("Large.txt");
            Files.writeString(file, String.join("\n",
                    "line 1: alpha",
                    "line 2: beta",
                    "line 3: gamma",
                    "line 4: delta",
                    "line 5: epsilon"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("read_file",
                    "{\"path\":\"Large.txt\",\"offset\":2,\"limit\":2}");

            assertTrue(result.contains("lines 2-3 of 5"), result);
            assertTrue(result.contains("2 | line 2: beta"), result);
            assertTrue(result.contains("3 | line 3: gamma"), result);
            assertTrue(result.contains("offset=4"), result);
            assertFalse(result.contains("文件过大"), result);
        } finally {
            restoreSystemProperty("paicli.read_file.max_unranged_bytes", previous);
        }
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldExposePartialWhenGrepReachesHeadLimit(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.search.disable.rg");
        System.setProperty("paicli.search.disable.rg", "true");
        try {
            Files.writeString(tempDir.resolve("Many.java"), String.join("\n",
                    "class Many {",
                    "  String first = \"needle\";",
                    "  String second = \"needle\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"head_limit\":1,\"max_results\":10}");

            assertTrue(result.contains("Many.java:2"));
            assertTrue(!result.contains("Many.java:3"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("head_limit=1"));
            assertTrue(result.contains("suggested_reads"));
            assertTrue(result.contains("read_file {\"path\":\"Many.java\""));
        } finally {
            restoreSystemProperty("paicli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldExposePartialWhenGrepResultReachesCharacterBudget(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.search.disable.rg");
        System.setProperty("paicli.search.disable.rg", "true");
        try {
            String longNeedleLine = "needle " + "x".repeat(1200);
            Files.writeString(tempDir.resolve("Budget.java"), String.join("\n",
                    "class Budget {",
                    "  String first = \"" + longNeedleLine + "\";",
                    "  String second = \"" + longNeedleLine + "\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"max_results\":10,\"max_chars\":1000}");

            assertTrue(result.contains("Budget.java:2"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("max_chars=1000"));
        } finally {
            restoreSystemProperty("paicli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldRecoverPersistedMcpResultWithGrepAndPagedRead(@TempDir Path tempDir) throws Exception {
        Path resultFile = tempDir.resolve(".paicli/tool-results/session-abc/main/call_1.json");
        Files.createDirectories(resultFile.getParent());
        Files.writeString(resultFile, String.join("\n",
                "{",
                "  \"requests\": [",
                "    {\"requestId\": \"req-1\", \"status\": 200},",
                "    {\"requestId\": \"req-399\", \"status\": 500}",
                "  ]",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String grepResult = registry.executeTool("grep_code",
                "{\"pattern\":\"req-399\",\"path\":\".paicli/tool-results/session-abc/main\",\"max_results\":5}");
        String readResult = registry.executeTool("read_file",
                "{\"path\":\".paicli/tool-results/session-abc/main/call_1.json\",\"offset\":3,\"limit\":2}");

        assertTrue(grepResult.contains(".paicli/tool-results/session-abc/main/call_1.json:4"), grepResult);
        assertTrue(grepResult.contains("suggested_reads"), grepResult);
        assertTrue(readResult.contains("req-399"), readResult);
        assertTrue(readResult.contains("status"), readResult);
    }

    @Test
    void shouldRecoverPersistedMcpResultWithJqWhenAvailable(@TempDir Path tempDir) throws Exception {
        assumeTrue(isJqAvailable(), "jq is not installed");
        Path resultFile = tempDir.resolve(".paicli/tool-results/session-abc/main/call_1.json");
        Files.createDirectories(resultFile.getParent());
        Files.writeString(resultFile, String.join("\n",
                "{",
                "  \"requests\": [",
                "    {\"requestId\": \"req-1\", \"status\": 200},",
                "    {\"requestId\": \"req-399\", \"status\": 500}",
                "  ]",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        ObjectNode args = MAPPER.createObjectNode();
        args.put("command", "jq -r '.requests[] | select(.requestId==\"req-399\") | .status' "
                + ".paicli/tool-results/session-abc/main/call_1.json");

        String result = registry.executeTool("execute_command", args.toString());

        assertTrue(result.contains("exit code: 0"), result);
        assertTrue(result.contains("500"), result);
    }

    @Test
    void shouldClearCurrentConversationScopeResultsAndActiveMcpTools(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        registry.registerMcpTool(stepSearchDescriptor("demo_tool", "{}"), args -> "demo");
        Path sessionRoot = tempDir.resolve(".paicli/tool-results").resolve(registry.getSessionId());
        Path mainFile = sessionRoot.resolve("main/call_main.json");
        Path taskFile = sessionRoot.resolve("task_1/call_task.json");
        Files.createDirectories(mainFile.getParent());
        Files.createDirectories(taskFile.getParent());
        Files.writeString(mainFile, "{}");
        Files.writeString(taskFile, "{}");

        registry.clearConversationScopedState();

        assertFalse(Files.exists(sessionRoot.resolve("main")), "main scope result directory should be deleted");
        assertTrue(Files.exists(taskFile), "other task scopes should remain");
        assertFalse(registry.hasTool("mcp__step_search__demo_tool"), "active MCP tool should be cleared");
    }

    @Test
    void shouldClearEntireResultSession(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Path sessionRoot = tempDir.resolve(".paicli/tool-results").resolve(registry.getSessionId());
        Path mainFile = sessionRoot.resolve("main/call_main.json");
        Path taskFile = sessionRoot.resolve("task_1/call_task.json");
        Files.createDirectories(mainFile.getParent());
        Files.createDirectories(taskFile.getParent());
        Files.writeString(mainFile, "{}");
        Files.writeString(taskFile, "{}");

        registry.clearResultSession();

        assertFalse(Files.exists(sessionRoot));
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldRouteWebSearchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"},
                    "top_k": {"type": "integer"}
                  }
                }
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\",\"top_k\":3}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-result"));
        assertTrue(result.contains("\"query\":\"Step 3.7 Flash\""));
        assertTrue(result.contains("\"top_k\":3"));
    }

    @Test
    void shouldRouteWebFetchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_fetch", """
                {
                  "type": "object",
                  "properties": {
                    "url": {"type": "string"},
                    "max_chars": {"type": "integer"}
                  }
                }
                """), args -> "step-fetch:" + args);

        String result = registry.executeTool("web_fetch",
                "{\"url\":\"https://platform.stepfun.com/docs/zh/step-plan/integrations/search-mcp\",\"max_chars\":1200}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-fetch"));
        assertTrue(result.contains("\"url\":\"https://platform.stepfun.com/docs/zh/step-plan/integrations/search-mcp\""));
        assertTrue(result.contains("\"max_chars\":1200"));
    }

    @Test
    void shouldNotRouteStepSearchForOlderStepModel() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.5-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {"type": "object", "properties": {"query": {"type": "string"}}}
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\"}");

        assertFalse(result.contains("step-result"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    @Test
    void shouldSerializeConcurrentWritesToSameFileAndPublishCommitChain(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("same.txt");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<WriteEvent> events = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) ->
                events.add(new WriteEvent(path, beforeAfter[0], beforeAfter[1])));

        List<ToolRegistry.ToolInvocation> invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file", writeArgs("same.txt", "first")),
                new ToolRegistry.ToolInvocation("call_2", "write_file", writeArgs("same.txt", "second"))
        );

        for (int i = 0; i < 10; i++) {
            Files.writeString(file, "base");
            events.clear();

            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(invocations);

            assertFalse(results.get(0).timedOut());
            assertFalse(results.get(1).timedOut());
            assertEquals(2, events.size(), "observer 应收到两次提交");
            assertEquals("base", events.get(0).before());
            assertEquals(events.get(0).after(), events.get(1).before(), "第二次写入应看到第一次写入后的内容");
            assertEquals(events.get(1).after(), Files.readString(file), "终态应等于最后提交内容");
        }
    }

    @Test
    void shouldWriteFileAndPublishDiffEvent(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("nested"));
        Path file = tempDir.resolve("nested").resolve("sample.txt");
        Files.writeString(file, "before\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<WriteEvent> events = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) ->
                events.add(new WriteEvent(path, beforeAfter[0], beforeAfter[1])));

        String result = registry.executeTool("write_file", writeArgs("nested/sample.txt", "after\n"));

        assertTrue(result.contains("文件已写入"), result);
        assertEquals("after\n", Files.readString(file));
        assertEquals(1, events.size());
        assertEquals("before\n", events.get(0).before());
        assertEquals("after\n", events.get(0).after());
    }

    @Test
    void shouldExposeEditFileToolDefinition() {
        ToolRegistry registry = new ToolRegistry();

        com.paicli.llm.LlmClient.Tool tool = toolDefinition(registry, "edit_file");

        JsonNode required = tool.parameters().path("required");
        assertTrue(required.toString().contains("\"path\""));
        assertTrue(required.toString().contains("\"edits\""));
        assertFalse(required.toString().contains("\"old_string\""), "top-level old_string should not be in required");
        assertFalse(required.toString().contains("\"new_string\""), "top-level new_string should not be in required");
        assertFalse(required.toString().contains("\"replace_all\""));
    }

    @Test
    void shouldAllowDisablingEditFileForBaselineRuns() {
        String previous = System.getProperty("paicli.edit_file.disabled");
        try {
            System.clearProperty("paicli.edit_file.disabled");
            ToolRegistry treatment = new ToolRegistry();
            assertTrue(treatment.hasTool("edit_file"));

            System.setProperty("paicli.edit_file.disabled", "true");
            ToolRegistry baseline = new ToolRegistry();

            assertFalse(baseline.hasTool("edit_file"));
            assertTrue(baseline.getToolDefinitions().stream().noneMatch(tool -> "edit_file".equals(tool.name())));
        } finally {
            restoreSystemProperty("paicli.edit_file.disabled", previous);
        }
    }

    @Test
    void shouldGuideModelsToUseEditFileSafely() {
        ToolRegistry registry = new ToolRegistry();

        com.paicli.llm.LlmClient.Tool editFile = toolDefinition(registry, "edit_file");
        com.paicli.llm.LlmClient.Tool writeFile = toolDefinition(registry, "write_file");

        assertTrue(editFile.description().contains("唯一"), editFile.description());
        assertTrue(editFile.description().contains("replace_all"), editFile.description());
        assertTrue(editFile.description().contains("edits[]"), editFile.description());
        assertTrue(writeFile.description().contains("edit_file"), writeFile.description());
    }

    @Test
    void shouldEditUniqueMatchAndPublishDiffEvent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "alpha\nold value\nomega\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<WriteEvent> events = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) ->
                events.add(new WriteEvent(path, beforeAfter[0], beforeAfter[1])));

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", "old value", "new value", false));

        assertTrue(result.contains("文件已编辑"), result);
        assertTrue(result.contains("替换 1 处"), result);
        assertEquals("alpha\nnew value\nomega\n", Files.readString(file));
        assertEquals(1, events.size());
        assertEquals("alpha\nold value\nomega\n", events.get(0).before());
        assertEquals("alpha\nnew value\nomega\n", events.get(0).after());
    }

    @Test
    void shouldApplyConcurrentNonOverlappingEditsToSameFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<WriteEvent> events = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) ->
                events.add(new WriteEvent(path, beforeAfter[0], beforeAfter[1])));

        List<ToolRegistry.ToolInvocation> invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "edit_file",
                        editArgs("Sample.txt", "alpha", "ALPHA", false)),
                new ToolRegistry.ToolInvocation("call_2", "edit_file",
                        editArgs("Sample.txt", "omega", "OMEGA", false))
        );

        for (int i = 0; i < 10; i++) {
            Files.writeString(file, "alpha\nmiddle\nomega\n");
            events.clear();

            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(invocations);

            assertFalse(results.get(0).timedOut());
            assertFalse(results.get(1).timedOut());
            assertEquals("ALPHA\nmiddle\nOMEGA\n", Files.readString(file));
            assertEquals(2, events.size(), "observer 应收到两次编辑提交");
            assertEquals(events.get(0).after(), events.get(1).before(), "第二次编辑应基于第一次编辑后的内容");
        }
    }

    @Test
    void shouldRunLspHookOnlyAfterSuccessfulEditIsPersisted(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "class Sample { String value = \"old\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        RecordingLspManager lsp = new RecordingLspManager(tempDir.toString());
        registry.setLspManager(lsp);

        String success = registry.executeTool("edit_file",
                editArgs("Sample.java", "\"old\"", "\"new\"", false));
        String failure = registry.executeTool("edit_file",
                editArgs("Sample.java", "\"missing\"", "\"other\"", false));

        assertTrue(success.contains("文件已编辑"), success);
        assertTrue(failure.contains("未找到"), failure);
        assertEquals(1, lsp.calls.size());
        assertTrue(lsp.calls.get(0).contains("Sample.java"));
        assertTrue(lsp.calls.get(0).contains("\"new\""));
    }

    @Test
    void shouldKeepFileUnchangedWhenEditMatchIsMissing(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "alpha\nbeta\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", "gamma", "delta", false));

        assertTrue(result.contains("未找到"), result);
        assertTrue(result.contains("完全一致"), result);
        assertTrue(result.contains("缩进"), result);
        assertTrue(result.contains("空白"), result);
        assertTrue(result.contains("换行"), result);
        assertEquals("alpha\nbeta\n", Files.readString(file));
    }

    @Test
    void shouldKeepFileUnchangedWhenEditMatchIsAmbiguous(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "name\nname\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", "name", "title", false));

        assertTrue(result.contains("匹配 2 处"), result);
        assertTrue(result.contains("replace_all=true"), result);
        assertEquals("name\nname\n", Files.readString(file));
    }

    @Test
    void shouldReplaceAllMatchesWithNonOverlappingCount(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "aaaa\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", "aa", "b", true));

        assertTrue(result.contains("替换 2 处"), result);
        assertEquals("bb\n", Files.readString(file));
    }

    @Test
    void shouldRejectEmptyOldStringWithoutChangingFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "alpha\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", "", "beta", false));

        assertTrue(result.contains("old_string"), result);
        assertTrue(result.contains("write_file"), result);
        assertEquals("alpha\n", Files.readString(file));
    }

    @Test
    void shouldRejectNoOpEditWithoutChangingFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "alpha\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<WriteEvent> events = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) ->
                events.add(new WriteEvent(path, beforeAfter[0], beforeAfter[1])));

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", "alpha", "alpha", false));

        assertTrue(result.contains("相同"), result);
        assertEquals("alpha\n", Files.readString(file));
        assertTrue(events.isEmpty(), "无改动不应触发 diff observer");
    }

    @Test
    void shouldRejectMissingNewStringWithoutChangingFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "alpha\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file", editArgsWithoutNewString("Sample.txt", "alpha"));

        assertTrue(result.contains("new_string"), result);
        assertEquals("alpha\n", Files.readString(file));
    }

    @Test
    void shouldAllowExplicitEmptyNewStringAsDeletion(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "alpha beta\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Sample.txt", " beta", "", false));

        assertTrue(result.contains("文件已编辑"), result);
        assertTrue(result.contains("替换 1 处"), result);
        assertEquals("alpha\n", Files.readString(file));
    }

    @Test
    void shouldRejectEditingMissingFileAndPointToWriteFile(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("missing.txt", "alpha", "beta", false));

        assertTrue(result.contains("文件不存在"), result);
        assertTrue(result.contains("write_file"), result);
    }

    @Test
    void shouldRejectEditingDirectoryAndPointToWriteFile(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("dir"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("dir", "alpha", "beta", false));

        assertTrue(result.contains("write_file"), result);
    }

    @Test
    void shouldRejectEditFilePathOutsideProject(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("../outside.txt", "alpha", "beta", false));

        assertTrue(result.contains("策略拒绝"), result);
        assertTrue(result.contains("路径越界"), result);
    }

    @Test
    void shouldAuditPolicyDenyWhenEditedContentExceedsLimit(@TempDir Path tempDir) throws Exception {
        String previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            Path file = tempDir.resolve("Sample.txt");
            Files.writeString(file, "alpha");
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            String tooLarge = "x".repeat((5 * 1024 * 1024) + 1);

            String result = registry.executeTool("edit_file",
                    editArgs("Sample.txt", "alpha", tooLarge, false));

            assertTrue(result.contains("策略拒绝"), result);
            assertTrue(result.contains("5MB"), result);
            assertEquals("alpha", Files.readString(file));
            List<AuditLog.AuditEntry> entries = registry.getAuditLog().readRecent(1);
            assertEquals(1, entries.size());
            assertEquals("edit_file", entries.get(0).tool());
            assertEquals(AuditLog.OUTCOME_DENY, entries.get(0).outcome());
            assertEquals(AuditLog.APPROVER_POLICY, entries.get(0).approver());
        } finally {
            restoreSystemProperty("paicli.audit.dir", previousAuditDir);
        }
    }

    @Test
    void shouldAuditBusinessEditFailureAsAllow(@TempDir Path tempDir) throws Exception {
        String previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            Path file = tempDir.resolve("Sample.txt");
            Files.writeString(file, "alpha");
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("edit_file",
                    editArgs("Sample.txt", "missing", "beta", false));

            assertTrue(result.contains("未找到"), result);
            assertEquals("alpha", Files.readString(file));
            List<AuditLog.AuditEntry> entries = registry.getAuditLog().readRecent(1);
            assertEquals(1, entries.size());
            assertEquals("edit_file", entries.get(0).tool());
            assertEquals(AuditLog.OUTCOME_ALLOW, entries.get(0).outcome());
            assertEquals(AuditLog.APPROVER_NONE, entries.get(0).approver());
        } finally {
            restoreSystemProperty("paicli.audit.dir", previousAuditDir);
        }
    }

    @Test
    void shouldDenyEditingTestDirWhenSafeEditEnabled(@TempDir Path tempDir) throws Exception {
        String previousSafeEdit = System.getProperty("paicli.safe_edit.enabled");
        String previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.safe_edit.enabled", "true");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            Path file = tempDir.resolve("src/test/java/com/Sample.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "alpha");
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("edit_file",
                    editArgs("src/test/java/com/Sample.java", "alpha", "beta", false));

            assertTrue(result.contains("策略拒绝"), result);
            assertTrue(result.contains("测试目录"), result);
            assertEquals("alpha", Files.readString(file));
            List<AuditLog.AuditEntry> entries = registry.getAuditLog().readRecent(1);
            assertEquals(1, entries.size());
            assertEquals("edit_file", entries.get(0).tool());
            assertEquals(AuditLog.OUTCOME_DENY, entries.get(0).outcome());
            assertEquals(AuditLog.APPROVER_POLICY, entries.get(0).approver());
        } finally {
            restoreSystemProperty("paicli.safe_edit.enabled", previousSafeEdit);
            restoreSystemProperty("paicli.audit.dir", previousAuditDir);
        }
    }

    @Test
    void shouldDenyWritingTestDirWhenSafeEditEnabled(@TempDir Path tempDir) throws Exception {
        String previousSafeEdit = System.getProperty("paicli.safe_edit.enabled");
        String previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.safe_edit.enabled", "true");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            Path file = tempDir.resolve("src/test/java/com/Sample.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "alpha");
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("write_file",
                    writeArgs("src/test/java/com/Sample.java", "beta"));

            assertTrue(result.contains("策略拒绝"), result);
            assertTrue(result.contains("测试目录"), result);
            assertEquals("alpha", Files.readString(file));
            List<AuditLog.AuditEntry> entries = registry.getAuditLog().readRecent(1);
            assertEquals(1, entries.size());
            assertEquals("write_file", entries.get(0).tool());
            assertEquals(AuditLog.OUTCOME_DENY, entries.get(0).outcome());
            assertEquals(AuditLog.APPROVER_POLICY, entries.get(0).approver());
        } finally {
            restoreSystemProperty("paicli.safe_edit.enabled", previousSafeEdit);
            restoreSystemProperty("paicli.audit.dir", previousAuditDir);
        }
    }

    @Test
    void shouldAllowEditingTestDirWhenSafeEditDisabled(@TempDir Path tempDir) throws Exception {
        String previousSafeEdit = System.getProperty("paicli.safe_edit.enabled");
        System.clearProperty("paicli.safe_edit.enabled");
        try {
            Path file = tempDir.resolve("src/test/java/com/Sample.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "alpha");
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String editResult = registry.executeTool("edit_file",
                    editArgs("src/test/java/com/Sample.java", "alpha", "beta", false));
            assertFalse(editResult.contains("策略拒绝"), editResult);
            assertEquals("beta", Files.readString(file));

            String writeResult = registry.executeTool("write_file",
                    writeArgs("src/test/java/com/Sample.java", "gamma"));
            assertFalse(writeResult.contains("策略拒绝"), writeResult);
            assertEquals("gamma", Files.readString(file));
        } finally {
            restoreSystemProperty("paicli.safe_edit.enabled", previousSafeEdit);
        }
    }

    private static McpToolDescriptor stepSearchDescriptor(String name, String schema) throws Exception {
        JsonNode inputSchema = MAPPER.readTree(schema);
        return new McpToolDescriptor(
                "step_search",
                name,
                "mcp__step_search__" + name,
                "StepSearch " + name,
                inputSchema);
    }

    private static String writeArgs(String path, String content) {
        return MAPPER.createObjectNode()
                .put("path", path)
                .put("content", content)
                .toString();
    }

    private static String editArgs(String path, String oldString, String newString, boolean replaceAll) {
        ObjectNode edit = MAPPER.createObjectNode()
                .put("old_string", oldString)
                .put("new_string", newString)
                .put("replace_all", replaceAll);
        return MAPPER.createObjectNode()
                .put("path", path)
                .set("edits", MAPPER.createArrayNode().add(edit))
                .toString();
    }

    private static String editArgsWithoutNewString(String path, String oldString) {
        ObjectNode edit = MAPPER.createObjectNode()
                .put("old_string", oldString);
        return MAPPER.createObjectNode()
                .put("path", path)
                .set("edits", MAPPER.createArrayNode().add(edit))
                .toString();
    }

    private static String multiEditArgs(String path, String[][] edits) {
        ArrayNode editsNode = MAPPER.createArrayNode();
        for (String[] e : edits) {
            ObjectNode editNode = MAPPER.createObjectNode()
                    .put("old_string", e[0])
                    .put("new_string", e[1]);
            if (e.length > 2) editNode.put("replace_all", Boolean.parseBoolean(e[2]));
            editsNode.add(editNode);
        }
        return MAPPER.createObjectNode()
                .put("path", path)
                .set("edits", editsNode)
                .toString();
    }

    private static String emptyEditsArgs(String path) {
        return MAPPER.createObjectNode()
                .put("path", path)
                .set("edits", MAPPER.createArrayNode())
                .toString();
    }

    private static com.paicli.llm.LlmClient.Tool toolDefinition(ToolRegistry registry, String name) {
        return registry.getToolDefinitions().stream()
                .filter(tool -> name.equals(tool.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing tool definition: " + name));
    }

    private record WriteEvent(String path, String before, String after) {}

    private static final class RecordingLspManager extends LspManager {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        private RecordingLspManager(String projectPath) {
            super(projectPath);
        }

        @Override
        public void runPostEditLspHook(String displayPath, Path editedFile) {
            try {
                calls.add(displayPath + ":" + Files.readString(editedFile));
            } catch (Exception e) {
                calls.add(displayPath + ":<unreadable>");
            }
        }
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }

    // ---- Step 3: edits[] transaction new tests ----

    @Test
    void shouldApplyMultiEditBothLand(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Multi.txt");
        Files.writeString(file, "alpha\nomega\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                multiEditArgs("Multi.txt", new String[][]{
                        {"alpha", "ALPHA"},
                        {"omega", "OMEGA"}
                }));

        assertTrue(result.contains("文件已编辑"), result);
        assertEquals("ALPHA\nOMEGA\n", Files.readString(file));
    }

    @Test
    void shouldAbortBatchWhenOneEditHasZeroMatches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Abort.txt");
        Files.writeString(file, "alpha\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                multiEditArgs("Abort.txt", new String[][]{
                        {"alpha", "X"},
                        {"MISSING", "Y"}
                }));

        assertTrue(result.contains("第2条"), result);
        assertTrue(result.contains("未找到"), result);
        assertEquals("alpha\n", Files.readString(file), "file must be unchanged after batch abort");
    }

    @Test
    void shouldRejectOverlappingSpans(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Overlap.txt");
        Files.writeString(file, "abcdef\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                multiEditArgs("Overlap.txt", new String[][]{
                        {"abc", "X"},
                        {"cde", "Y"}
                }));

        assertTrue(result.contains("重叠"), result);
        assertEquals("abcdef\n", Files.readString(file), "file must be unchanged after overlap rejection");
    }

    @Test
    void shouldReplaceAllOccurrencesWithReplaceAllTrue(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ReplaceAll.txt");
        Files.writeString(file, "aa aa aa\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                multiEditArgs("ReplaceAll.txt", new String[][]{
                        {"aa", "bb", "true"}
                }));

        assertTrue(result.contains("文件已编辑"), result);
        assertEquals("bb bb bb\n", Files.readString(file));
    }

    @Test
    void shouldDeleteWhenNewStringIsEmpty(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Delete.txt");
        Files.writeString(file, "keep to delete end\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                multiEditArgs("Delete.txt", new String[][]{
                        {" to delete", ""}
                }));

        assertTrue(result.contains("文件已编辑"), result);
        assertEquals("keep end\n", Files.readString(file));
    }

    @Test
    void shouldRejectEmptyEditsArray(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Empty.txt");
        Files.writeString(file, "content\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file", emptyEditsArgs("Empty.txt"));

        assertTrue(result.contains("不能为空"), result);
        assertEquals("content\n", Files.readString(file));
    }

    @Test
    void shouldRejectMissingNewStringKeyInEditsArray(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("MissingKey.txt");
        Files.writeString(file, "alpha\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file", editArgsWithoutNewString("MissingKey.txt", "alpha"));

        assertTrue(result.contains("new_string"), result);
        assertEquals("alpha\n", Files.readString(file));
    }

    @Test
    void shouldCatchNoChangeAtStructuralValidation(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("NoChange.txt");
        Files.writeString(file, "alpha\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<WriteEvent> events = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) ->
                events.add(new WriteEvent(path, beforeAfter[0], beforeAfter[1])));

        String result = registry.executeTool("edit_file",
                editArgs("NoChange.txt", "alpha", "alpha", false));

        assertTrue(result.contains("相同"), result);
        assertEquals("alpha\n", Files.readString(file));
        assertTrue(events.isEmpty(), "no-change must not fire observer");
    }

    // ---- Issue 03: input tolerance — stringified edits + top-level old/new fold ----

    @Test
    void editFile_editsAsJsonString_parsed(@TempDir Path tempDir) throws Exception {
        // Some models serialize edits as a JSON string
        Path file = tempDir.resolve("Strng.txt");
        Files.writeString(file, "hello world");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // Build args where edits is a JSON string, not an array
        String editsJson = "[{\"old_string\":\"hello\",\"new_string\":\"bye\",\"replace_all\":false}]";
        String args = MAPPER.createObjectNode()
                .put("path", "Strng.txt")
                .put("edits", editsJson)   // <-- string, not array
                .toString();

        String result = registry.executeTool("edit_file", args);
        assertTrue(result.contains("编辑"), result);
        assertEquals("bye world", Files.readString(file));
    }

    @Test
    void editFile_editsAsInvalidJsonString_returnsError(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Strng2.txt");
        Files.writeString(file, "hello world");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String args = MAPPER.createObjectNode()
                .put("path", "Strng2.txt")
                .put("edits", "not valid json {{{{")
                .toString();

        String result = registry.executeTool("edit_file", args);
        // Should return a meaningful error, not crash; file must be unchanged
        assertFalse(result.contains("编辑"), result);
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void editFile_topLevelOldNewFolded(@TempDir Path tempDir) throws Exception {
        // Old-style top-level old_string/new_string with no edits key
        Path file = tempDir.resolve("Fold.txt");
        Files.writeString(file, "hello world");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String args = MAPPER.createObjectNode()
                .put("path", "Fold.txt")
                .put("old_string", "hello")
                .put("new_string", "goodbye")
                .toString();  // no "edits" key

        String result = registry.executeTool("edit_file", args);
        assertTrue(result.contains("编辑"), result);
        assertEquals("goodbye world", Files.readString(file));
    }

    @Test
    void editFile_topLevelAndEditsCoexist_topLevelAppended(@TempDir Path tempDir) throws Exception {
        // Both top-level old/new AND edits[] present — top-level appended as extra edit
        Path file = tempDir.resolve("Both.txt");
        Files.writeString(file, "alpha beta gamma");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        ObjectNode edit1 = MAPPER.createObjectNode().put("old_string", "alpha").put("new_string", "A").put("replace_all", false);
        String args = MAPPER.createObjectNode()
                .put("path", "Both.txt")
                .put("old_string", "gamma")
                .put("new_string", "G")
                .<ObjectNode>set("edits", MAPPER.createArrayNode().add(edit1))
                .toString();

        String result = registry.executeTool("edit_file", args);
        assertTrue(result.contains("编辑"), result);
        assertEquals("A beta G", Files.readString(file));
    }

    @Test
    void editFile_crlfFile_lfOldStringMatches_diskRemainsClrf(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Crlf.txt");
        Files.write(file, "line1\r\nold value\r\nline3\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Crlf.txt", "old value", "new value", false));

        assertTrue(result.contains("编辑"), result);
        byte[] afterBytes = Files.readAllBytes(file);
        String afterStr = new String(afterBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(afterStr.contains("\r\n"), "Disk should keep CRLF: " + afterStr.replace("\r","\\r").replace("\n","\\n"));
        assertTrue(afterStr.contains("new value"), afterStr);
    }

    @Test
    void editFile_bomFile_preservesBom(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Bom.txt");
        // Write UTF-8 BOM (﻿) + content
        String content = "﻿hello world\n";
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Bom.txt", "hello", "goodbye", false));

        assertTrue(result.contains("编辑"), result);
        byte[] afterBytes = Files.readAllBytes(file);
        // BOM is EF BB BF
        assertEquals(0xEF, afterBytes[0] & 0xFF, "First byte should be BOM EF");
        assertEquals(0xBB, afterBytes[1] & 0xFF);
        assertEquals(0xBF, afterBytes[2] & 0xFF);
        String afterStr = new String(afterBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(afterStr.contains("goodbye"), afterStr);
    }

    @Test
    void editFile_pureLfFile_regressionUnchanged(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Lf.txt");
        Files.writeString(file, "line1\nold\nline3\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Lf.txt", "old", "new", false));

        assertTrue(result.contains("编辑"), result);
        assertEquals("line1\nnew\nline3\n", Files.readString(file));
    }

    @Test
    void editFile_crlfObserverGetsLfSpace(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("CrlfObs.txt");
        Files.write(file, "before\r\nafter\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String[] observed = new String[2];
        registry.setWriteFileObserver((path, beforeAfter) -> {
            observed[0] = beforeAfter[0];
            observed[1] = beforeAfter[1];
        });

        registry.executeTool("edit_file", editArgs("CrlfObs.txt", "before", "BEFORE", false));

        assertNotNull(observed[0]);
        assertFalse(observed[0].contains("\r"), "Observer before should be LF-space");
        assertFalse(observed[1].contains("\r"), "Observer after should be LF-space");
        String disk = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(disk.contains("\r\n"), "Disk should keep CRLF");
    }

    // ---- Issue 05: whole-line fuzzy match fallback ----

    @Test
    void editFile_fuzzy_smartQuoteDiff_matchesLine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy1.txt");
        // File has smart quotes (curly), model sends straight quotes
        Files.writeString(file, "line1\n\u201csmart quoted\u201d\nline3\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // old_string uses straight quote — should fuzzy-match the smart-quote line
        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy1.txt", "\"smart quoted\"", "replaced", false));

        assertTrue(result.contains("编辑"), result);
        String disk = Files.readString(file);
        assertTrue(disk.contains("replaced"), disk);
        // Other lines must be byte-identical
        assertTrue(disk.startsWith("line1\n"), disk);
        assertTrue(disk.endsWith("line3\n"), disk);
    }

    @Test
    void editFile_fuzzy_trailingWhitespaceDiff_matchesLine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy2.txt");
        // File line has trailing spaces
        Files.writeString(file, "before\nfoo bar   \nafter\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // old_string has no trailing spaces — fuzzy matches
        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy2.txt", "foo bar", "baz", false));

        assertTrue(result.contains("编辑"), result);
        String disk = Files.readString(file);
        assertTrue(disk.contains("baz"), disk);
        // before/after lines unchanged
        assertTrue(disk.contains("before\n"), disk);
        assertTrue(disk.contains("after\n"), disk);
    }

    @Test
    void editFile_fuzzy_onlyThatLineChanges_otherBytesIntact(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy3.txt");
        // File line uses smart quotes — exact match with straight quotes will fail, fuzzy will succeed
        // Straight-quote old_string cannot exact-match the smart-quote line
        Files.writeString(file, "prefix\n\u201ctarget line\u201d\nsuffix\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // old_string uses straight quotes — exact match fails, fuzzy replaces whole line
        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy3.txt", "\"target line\"", "replaced line", false));

        assertTrue(result.contains("编辑"), result);
        String disk = Files.readString(file);
        // Only the matched line changes — other lines byte-identical
        assertEquals("prefix\nreplaced line\nsuffix\n", disk);
    }

    @Test
    void editFile_fuzzy_multiLineOldString_returnsError(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy4.txt");
        // File lines have trailing whitespace so multi-line old_string won't exact-match
        Files.writeString(file, "line1   \nline2   \nline3\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // old_string spans multiple lines and won't exact-match — fuzzy fallback should error (multi-line)
        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy4.txt", "line1\nline2", "replacement", false));

        // Result should indicate fuzzy not supported for multi-line
        assertTrue(result.contains("仅支持整行") || result.contains("未找到") || result.contains("多行"),
                "Expected multi-line fuzzy error but got: " + result);
        // File must be unchanged
        assertEquals("line1   \nline2   \nline3\n", Files.readString(file));
    }

    @Test
    void editFile_fuzzy_resultLabelsMatch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy5.txt");
        // File uses en-dash — exact match with hyphen will fail, fuzzy normalizes it
        Files.writeString(file, "only line – here\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // old_string uses hyphen — exact match fails (file has en-dash), fuzzy matches
        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy5.txt", "only line - here", "new line", false));

        assertTrue(result.contains("编辑"), result);
        // Result should mention fuzzy match
        assertTrue(result.contains("模糊") || result.contains("fuzzy"),
                "Result should label fuzzy match: " + result);
    }

    @Test
    void editFile_fuzzy_exactPriority_noFuzzyWhenExactMatches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy6.txt");
        // File has the old_string exactly (no trailing spaces, no quote diff)
        Files.writeString(file, "exact match here\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy6.txt", "exact match here", "replaced", false));

        // Exact match should succeed — no fuzzy label in result
        assertTrue(result.contains("编辑"), result);
        assertFalse(result.contains("模糊"), "Exact match should not produce fuzzy label: " + result);
    }

    @Test
    void editFile_fuzzy_replaceAll_allMatchingLinesReplaced(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fuzzy7.txt");
        // Two lines that both fuzzy-match old_string — file uses en-dash, old_string uses hyphen
        // Exact match fails (different characters), fuzzy normalizes em/en dash → hyphen
        Files.writeString(file, "target–line\nother\ntarget–line\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // old_string uses hyphen — exact match fails, fuzzy replace_all matches both lines
        String result = registry.executeTool("edit_file",
                editArgs("Fuzzy7.txt", "target-line", "NEW", true));  // replace_all=true

        assertTrue(result.contains("编辑"), result);
        String disk = Files.readString(file);
        // Both "target–line" lines should be replaced
        assertFalse(disk.contains("target"), disk);
        assertEquals("NEW\nother\nNEW\n", disk);
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static boolean isJqAvailable() {
        try {
            Process process = new ProcessBuilder("jq", "--version").start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
