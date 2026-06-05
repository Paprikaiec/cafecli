# edit_file Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a precise string-replacement edit tool (`edit_file`) to Paicli so the agent edits existing files surgically instead of rewriting whole files with `write_file`.

**Architecture:** New `edit_file` tool registered in `ToolRegistry.registerFileTools()`. Correctness comes from unique on-disk substring match (0 / >1 matches error out). Reuses the existing `writeFileObserver` (inline diff render) and `runPostEditLspHook` (LSP self-correction loop) via a small shared `persistEditedFile` helper. Treated as a write tool (audited + HITL-approvable). Spec: `docs/superpowers/specs/2026-06-05-edit-file-tool-design.md`.

**Tech Stack:** Java 17, Maven, JUnit 5 (Jupiter), Jackson.

---

## File Structure

- Modify `src/main/java/com/paicli/tool/ToolRegistry.java` — register `edit_file`; add private `editFile`, `countOccurrences`, `formatEditResult`, `truncateForDiff`, `persistEditedFile`; append a clause to `write_file` description; later reuse `persistEditedFile` in `write_file`.
- Modify `src/main/java/com/paicli/hitl/ApprovalPolicy.java` — add `edit_file` to `DANGEROUS_TOOLS` + danger-level/risk-description switches.
- Modify `src/main/resources/prompts/modes/agent.md` — one-line nudge to prefer `edit_file`.
- Modify `AGENTS.md` — bump core tool count 11 → 12 and list `edit_file`.
- Create `src/test/java/com/paicli/tool/EditFileToolTest.java` — unit tests.

**Conventions for every commit in this plan:** end the commit message body with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Run tests with `mvn test -Dtest=<TestClass> -DskipTests=false` (the default build skips tests). Work stays on branch `feat/edit-file-tool`. Do **not** `git add` the untracked `claude-code/` directory — stage only the specific files listed.

---

### Task 1: edit_file happy path (unique match) + registration

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java` (register in `registerFileTools()` at `:232`; add private helpers near `parseBoolean` at `:761`)
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/paicli/tool/EditFileToolTest.java`:

```java
package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String editArgs(String path, String oldString, String newString, Boolean replaceAll) {
        var node = MAPPER.createObjectNode()
                .put("path", path)
                .put("old_string", oldString)
                .put("new_string", newString);
        if (replaceAll != null) {
            node.put("replace_all", replaceAll);
        }
        return node.toString();
    }

    @Test
    void replacesUniqueMatchAndReportsDiff(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("Sample.java", "  void first() {}", "  void renamed() {}", null));

        assertTrue(result.contains("文件已编辑"), result);
        assertTrue(result.contains("替换 1 处"), result);
        assertTrue(result.contains("void renamed()"), result); // new content echoed in the diff
        assertEquals(String.join("\n",
                "class Sample {",
                "  void renamed() {}",
                "  void second() {}",
                "}"), Files.readString(file));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: FAIL — `edit_file` is unknown, so `executeTool` returns `"未知工具: edit_file"` and the assertions fail.

- [ ] **Step 3: Add helpers + register the tool**

In `ToolRegistry.java`, add these private members (e.g. directly after the `parseBoolean` method around `:767`):

```java
    private String editFile(Map<String, String> args) {
        String path = args.get("path");
        String oldString = args.get("old_string");
        String newString = args.get("new_string") == null ? "" : args.get("new_string");
        boolean replaceAll = parseBoolean(args.get("replace_all"), false);

        Path safe = pathGuard.resolveSafe(path);

        String before;
        try {
            before = Files.readString(safe);
        } catch (Exception e) {
            return "编辑失败: 读取文件出错: " + e.getMessage();
        }

        int count = countOccurrences(before, oldString);
        String after = before.replace(oldString, newString);

        try {
            persistEditedFile(path, safe, before, after);
        } catch (Exception e) {
            return "编辑失败: 写入文件出错: " + e.getMessage();
        }

        return formatEditResult(path, replaceAll ? count : 1, oldString, newString);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String formatEditResult(String path, int replaced, String oldString, String newString) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件已编辑: ").append(path).append("（替换 ").append(replaced).append(" 处）\n");
        sb.append("@@\n");
        for (String line : truncateForDiff(oldString).split("\n", -1)) {
            sb.append("- ").append(line).append("\n");
        }
        for (String line : truncateForDiff(newString).split("\n", -1)) {
            sb.append("+ ").append(line).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String truncateForDiff(String s) {
        int max = 2000;
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    /** 落盘 + 触发 inline diff 渲染 + post-edit LSP 诊断。write_file 与 edit_file 共用，行为等价。 */
    private void persistEditedFile(String displayPath, Path safePath, String before, String after) throws IOException {
        Path parent = safePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(safePath, after);
        try {
            writeFileObserver.accept(displayPath, new String[]{before, after});
        } catch (Exception ignored) {
            // observer 失败不能影响主路径
        }
        runPostEditLspHook(displayPath, safePath);
    }
```

Then register the tool inside `registerFileTools()` (after the `write_file` block, before `list_dir`):

```java
        // edit_file 工具
        tools.put("edit_file", new Tool(
                "edit_file",
                "对已有文件做精确局部修改的首选工具：用 old_string→new_string 精确替换。"
                        + "old_string 必须在文件中唯一出现（否则报错），改名/批量替换用 replace_all=true。"
                        + "比 write_file 更省 token、不易误伤无关代码。新建文件请用 write_file。",
                createParameters(
                        new Param("path", "string", "文件路径（项目根目录之内）", true),
                        new Param("old_string", "string", "要替换的原文，需与文件内容完全一致（含缩进/空白/换行）", true),
                        new Param("new_string", "string", "替换后的文本，必须不同于 old_string", true),
                        new Param("replace_all", "boolean", "是否替换全部出现处，默认 false", false)
                ),
                this::editFile
        ));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/tool/EditFileToolTest.java
git commit -m "feat: add edit_file precise string-replacement tool (happy path)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Match-count guards (0 match, multi-match, replace_all)

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java` (`editFile`)
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java`

- [ ] **Step 1: Write the failing tests**

Add these methods to `EditFileToolTest`:

```java
    @Test
    void errorsWhenStringNotFoundAndLeavesFileUnchanged(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("A.java");
        String original = "class A {}\n";
        Files.writeString(file, original);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("A.java", "class B {}", "class C {}", null));

        assertTrue(result.contains("未找到要替换的内容"), result);
        assertEquals(original, Files.readString(file));
    }

    @Test
    void errorsOnMultipleMatchesWithoutReplaceAll(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("B.java");
        String original = "int x = 1;\nint x = 1;\n";
        Files.writeString(file, original);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("B.java", "int x = 1;", "int y = 2;", false));

        assertTrue(result.contains("出现 2 次"), result);
        assertTrue(result.contains("replace_all"), result);
        assertEquals(original, Files.readString(file));
    }

    @Test
    void replacesAllOccurrencesWhenReplaceAllTrue(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("C.java");
        Files.writeString(file, "int x = 1;\nint x = 1;\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("C.java", "int x = 1;", "int y = 2;", true));

        assertTrue(result.contains("替换 2 处"), result);
        assertEquals("int y = 2;\nint y = 2;\n", Files.readString(file));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: FAIL — `errorsWhenStringNotFound...` (minimal code writes unchanged content and reports success) and `errorsOnMultipleMatches...` (minimal code replaces all). `replacesAll...` already passes.

- [ ] **Step 3: Add the count guards**

In `ToolRegistry.editFile`, replace the lines from `int count = countOccurrences(...)` through `String after = before.replace(...)` with:

```java
        int count = countOccurrences(before, oldString);
        if (count == 0) {
            return "编辑失败: 未找到要替换的内容，请确认 old_string 与文件完全一致（含缩进/空白/换行）";
        }
        if (count > 1 && !replaceAll) {
            return "编辑失败: old_string 在文件中出现 " + count + " 次，replace_all=false 无法定位。"
                    + "请补充上下文使其唯一，或设 replace_all=true";
        }

        String after = before.replace(oldString, newString);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS (all 4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/tool/EditFileToolTest.java
git commit -m "feat: edit_file uniqueness guards (0/multi match, replace_all)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Input + safety guards (empty/equal/missing/oversize)

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java` (`editFile`)
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `EditFileToolTest`:

```java
    @Test
    void rejectsEmptyOldStringWithWriteFileHint(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("D.java");
        Files.writeString(file, "class D {}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("D.java", "", "x", null));

        assertTrue(result.contains("old_string 不能为空"), result);
        assertTrue(result.contains("write_file"), result);
        assertEquals("class D {}\n", Files.readString(file));
    }

    @Test
    void rejectsIdenticalOldAndNew(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("E.java");
        Files.writeString(file, "class E {}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("E.java", "class E {}", "class E {}", null));

        assertTrue(result.contains("无改动"), result);
    }

    @Test
    void rejectsMissingFileWithWriteFileHint(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("nope.java", "a", "b", null));

        assertTrue(result.contains("文件不存在"), result);
        assertTrue(result.contains("write_file"), result);
    }

    @Test
    void rejectsPathOutsideProjectRoot(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                editArgs("../escape.java", "a", "b", null));

        assertTrue(result.contains("策略拒绝"), result);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: FAIL — empty/equal/missing produce wrong or generic messages; `rejectsPathOutsideProjectRoot` already passes (PathGuard throws `PolicyException` → "策略拒绝").

- [ ] **Step 3: Add the input/safety guards**

In `ToolRegistry.editFile`, insert the empty/equal checks at the very top of the method (right after the four `args.get(...)` assignments), the existence check right after `Path safe = pathGuard.resolveSafe(path);`, and the size check right after `String after = before.replace(...)`. The full method must read:

```java
    private String editFile(Map<String, String> args) {
        String path = args.get("path");
        String oldString = args.get("old_string");
        String newString = args.get("new_string") == null ? "" : args.get("new_string");
        boolean replaceAll = parseBoolean(args.get("replace_all"), false);

        if (oldString == null || oldString.isEmpty()) {
            return "编辑失败: old_string 不能为空；新建文件请用 write_file";
        }
        if (oldString.equals(newString)) {
            return "编辑失败: old_string 与 new_string 相同，无改动";
        }

        Path safe = pathGuard.resolveSafe(path);
        if (!Files.exists(safe) || !Files.isRegularFile(safe)) {
            return "编辑失败: 文件不存在或不是普通文件: " + path + "；新建请用 write_file";
        }

        String before;
        try {
            before = Files.readString(safe);
        } catch (Exception e) {
            return "编辑失败: 读取文件出错: " + e.getMessage();
        }

        int count = countOccurrences(before, oldString);
        if (count == 0) {
            return "编辑失败: 未找到要替换的内容，请确认 old_string 与文件完全一致（含缩进/空白/换行）";
        }
        if (count > 1 && !replaceAll) {
            return "编辑失败: old_string 在文件中出现 " + count + " 次，replace_all=false 无法定位。"
                    + "请补充上下文使其唯一，或设 replace_all=true";
        }

        String after = before.replace(oldString, newString);

        int afterBytes = after.getBytes(StandardCharsets.UTF_8).length;
        if (afterBytes > MAX_WRITE_FILE_BYTES) {
            throw new PolicyException("编辑后内容 " + afterBytes + " 字节超过 "
                    + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
        }

        try {
            persistEditedFile(path, safe, before, after);
        } catch (Exception e) {
            return "编辑失败: 写入文件出错: " + e.getMessage();
        }

        return formatEditResult(path, replaceAll ? count : 1, oldString, newString);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS (all tests so far).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/tool/EditFileToolTest.java
git commit -m "feat: edit_file input and safety guards (empty/equal/missing/size)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Integration assertions (diff render observer + LSP hook)

**Files:**
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java`

This task adds no production code — it locks in the integration contract that Task 1's `persistEditedFile` already provides, so a future refactor can't silently drop it.

- [ ] **Step 1: Write the test**

Add to `EditFileToolTest` (and add `import java.util.concurrent.atomic.AtomicReference;` at the top):

```java
    @Test
    void invokesWriteFileObserverWithBeforeAndAfter(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("F.java");
        Files.writeString(file, "int a = 1;\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AtomicReference<String[]> captured = new AtomicReference<>();
        registry.setWriteFileObserver((p, beforeAfter) -> captured.set(beforeAfter));

        registry.executeTool("edit_file",
                editArgs("F.java", "int a = 1;", "int a = 2;", null));

        String[] beforeAfter = captured.get();
        assertTrue(beforeAfter != null, "observer not called");
        assertEquals("int a = 1;\n", beforeAfter[0]);
        assertEquals("int a = 2;\n", beforeAfter[1]);
    }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS (observer wiring is already in `persistEditedFile`).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/paicli/tool/EditFileToolTest.java
git commit -m "test: lock edit_file writeFileObserver before/after contract

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: HITL approval + audit parity with write_file

**Files:**
- Modify: `src/main/java/com/paicli/hitl/ApprovalPolicy.java:18,38,51`
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java:81` (`AUDIT_TOOLS`)
- Test: `src/test/java/com/paicli/hitl/ApprovalPolicyTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/paicli/hitl/ApprovalPolicyTest.java`:

```java
package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyTest {
    @Test
    void editFileRequiresApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("edit_file"));
        assertTrue(ApprovalPolicy.getDangerousTools().contains("edit_file"));
    }

    @Test
    void editFileHasRiskMetadata() {
        assertTrue(ApprovalPolicy.getDangerLevel("edit_file").contains("中危"));
        assertTrue(ApprovalPolicy.getRiskDescription("edit_file").contains("替换"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ApprovalPolicyTest -DskipTests=false`
Expected: FAIL — `edit_file` not in `DANGEROUS_TOOLS`; danger level falls through to "🟢 安全".

- [ ] **Step 3: Add edit_file to ApprovalPolicy + AUDIT_TOOLS**

In `ApprovalPolicy.java`, add `"edit_file"` to the `DANGEROUS_TOOLS` set (after `"write_file",`):

```java
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "edit_file",
            "execute_command",
            "create_project",
            "revert_turn"
    );
```

Add a case in `getDangerLevel` (after the `write_file` case):

```java
            case "edit_file" -> "🟡 中危";
```

Add a case in `getRiskDescription` (after the `write_file` case):

```java
            case "edit_file" -> "将对已有文件做精确替换，被替换的原内容将丢失";
```

In `ToolRegistry.java:81`, add `"edit_file"` to `AUDIT_TOOLS`:

```java
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "edit_file", "execute_command", "create_project", "revert_turn");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ApprovalPolicyTest -DskipTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/hitl/ApprovalPolicy.java src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/hitl/ApprovalPolicyTest.java
git commit -m "feat: edit_file HITL approval + audit parity with write_file

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Prompt nudges + docs sync

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java:255` (`write_file` description string)
- Modify: `src/main/resources/prompts/modes/agent.md`
- Modify: `AGENTS.md`
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java`

- [ ] **Step 1: Write the failing test**

Add to `EditFileToolTest` (add `import com.paicli.llm.LlmClient;` and `import java.util.List;`):

```java
    @Test
    void toolDescriptionsNudgeTowardEditFile() {
        ToolRegistry registry = new ToolRegistry();
        List<LlmClient.Tool> defs = registry.getToolDefinitions();

        LlmClient.Tool edit = defs.stream().filter(t -> t.name().equals("edit_file")).findFirst().orElseThrow();
        assertTrue(edit.description().contains("唯一"), edit.description());
        assertTrue(edit.description().contains("replace_all"), edit.description());

        LlmClient.Tool write = defs.stream().filter(t -> t.name().equals("write_file")).findFirst().orElseThrow();
        assertTrue(write.description().contains("edit_file"), write.description());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: FAIL — `write_file` description does not yet mention `edit_file`.

- [ ] **Step 3: Update write_file description + prompt + docs**

In `ToolRegistry.java`, change the `write_file` description string (`:255`) to:

```java
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）；新建或整体重写用此工具，对已有文件的局部修改请优先 edit_file",
```

In `src/main/resources/prompts/modes/agent.md`, add a line after line 5 (the "不要为了展示过程..." paragraph):

```markdown
修改已有文件时，优先用 `edit_file` 做精确局部替换（old_string 需唯一），不要用 `write_file` 整体重写；`write_file` 仅用于新建文件或确需整体覆盖的场景。
```

In `AGENTS.md`, update the core tools line from "核心内置工具 11 个" to "核心内置工具 12 个" and insert `edit_file` right after `write_file` in the slash-separated list:

```
核心内置工具 12 个：`read_file` / `write_file` / `edit_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/tool/ToolRegistry.java src/main/resources/prompts/modes/agent.md AGENTS.md
git commit -m "feat: nudge model toward edit_file via tool/prompt copy; sync AGENTS.md

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: DRY refactor — write_file reuses persistEditedFile

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java:277-292` (`write_file` lambda tail)
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java`

- [ ] **Step 1: Write the regression test (characterization)**

Add to `EditFileToolTest`:

```java
    @Test
    void writeFileStillWritesAndNotifiesObserver(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AtomicReference<String[]> captured = new AtomicReference<>();
        registry.setWriteFileObserver((p, beforeAfter) -> captured.set(beforeAfter));

        String result = registry.executeTool("write_file",
                MAPPER.createObjectNode().put("path", "New.java").put("content", "class New {}\n").toString());

        assertTrue(result.contains("文件已写入"), result);
        assertEquals("class New {}\n", Files.readString(tempDir.resolve("New.java")));
        assertTrue(captured.get() != null, "observer not called");
        assertEquals("class New {}\n", captured.get()[1]);
    }
```

- [ ] **Step 2: Run test to verify it passes (current behavior)**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS — this characterizes write_file's existing behavior before the refactor.

- [ ] **Step 3: Refactor write_file to use the shared helper**

In `ToolRegistry.java`, inside the `write_file` executor lambda, replace the directory-create + write + observer + LSP block with a single `persistEditedFile` call. The `try` block must read:

```java
                    try {
                        persistEditedFile(path, safe, before, content);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
```

(Removes the inline `Files.createDirectories`, `Files.writeString`, the `try/catch` around `writeFileObserver.accept`, and the `runPostEditLspHook` call — all now inside `persistEditedFile`.)

- [ ] **Step 4: Run tests to verify they still pass**

Run: `mvn test -Dtest=EditFileToolTest,ToolRegistryTest -DskipTests=false`
Expected: PASS — write_file behavior unchanged; edit_file unaffected.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/tool/EditFileToolTest.java
git commit -m "refactor: write_file reuses persistEditedFile (behavior-equivalent, DRY)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: SWE-bench A/B toggle (baseline arm without edit_file)

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java` (`registerFileTools()` edit_file registration)
- Test: `src/test/java/com/paicli/tool/EditFileToolTest.java`

Lets the eval run a baseline arm (write_file only) vs treatment arm (with edit_file) to measure resolve-rate delta. Controlled by system property `paicli.disable.edit.file`.

- [ ] **Step 1: Write the failing test**

Add to `EditFileToolTest`:

```java
    @Test
    void editFileCanBeDisabledForBaselineArm() {
        assertTrue(new ToolRegistry().hasTool("edit_file"));
        System.setProperty("paicli.disable.edit.file", "true");
        try {
            assertTrue(!new ToolRegistry().hasTool("edit_file"));
        } finally {
            System.clearProperty("paicli.disable.edit.file");
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: FAIL — `edit_file` is registered unconditionally, so the disabled-arm assertion fails.

- [ ] **Step 3: Guard the registration**

In `registerFileTools()`, wrap the `edit_file` registration added in Task 1 with the toggle:

```java
        // edit_file 工具（可用 -Dpaicli.disable.edit.file=true 在 SWE-bench baseline 臂关闭）
        if (!Boolean.getBoolean("paicli.disable.edit.file")) {
            tools.put("edit_file", new Tool(
                    "edit_file",
                    "对已有文件做精确局部修改的首选工具：用 old_string→new_string 精确替换。"
                            + "old_string 必须在文件中唯一出现（否则报错），改名/批量替换用 replace_all=true。"
                            + "比 write_file 更省 token、不易误伤无关代码。新建文件请用 write_file。",
                    createParameters(
                            new Param("path", "string", "文件路径（项目根目录之内）", true),
                            new Param("old_string", "string", "要替换的原文，需与文件内容完全一致（含缩进/空白/换行）", true),
                            new Param("new_string", "string", "替换后的文本，必须不同于 old_string", true),
                            new Param("replace_all", "boolean", "是否替换全部出现处，默认 false", false)
                    ),
                    this::editFile
            ));
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EditFileToolTest -DskipTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/tool/EditFileToolTest.java
git commit -m "feat: paicli.disable.edit.file toggle for SWE-bench A/B baseline arm

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Full regression + finish

**Files:** none (verification only)

- [ ] **Step 1: Run the quick regression profile**

Run: `mvn test -Pquick`
Expected: BUILD SUCCESS. If any failure references edit_file/write_file/ApprovalPolicy, fix before proceeding; unrelated pre-existing failures should be noted, not silently ignored.

- [ ] **Step 2: Build the verifiable jar**

Run: `mvn clean package`
Expected: BUILD SUCCESS, produces `target/paicli-1.0-SNAPSHOT.jar`.

- [ ] **Step 3: Manual smoke (optional but recommended)**

Run the agent on a throwaay repo and confirm an `edit_file` call shows the inline diff and that a follow-up LSP diagnostic (if any) is injected next turn. Confirm `-Dpaicli.disable.edit.file=true` hides the tool.

- [ ] **Step 4: Report completion**

Summarize: tasks done, test counts, any deviations. Leave PR creation / push to the user (do not push or open a PR unless they ask). Suggested next step for the user: run the SWE-bench-java A/B — treatment arm default, baseline arm with `-Dpaicli.disable.edit.file=true` — and compare resolve rate + the trace-derived edit_file-vs-write_file usage and max_output_tokens truncation counts.

---

## Notes for the executor

- **Out of scope (do NOT add):** MultiEdit, new-file creation via edit_file, CRLF/whitespace-tolerant or smart-quote matching, a readState / read-before-edit guard. These are deliberate non-goals (see spec §非目标).
- **Concurrency:** `edit_file` is registered as a plain write tool. Once the separate "read/write tool partition" change lands, it is automatically treated as non-read-only (serial). This plan does not depend on that change.
- **CRLF limitation is known and accepted for v1** — v1 matches raw bytes. If a SWE-bench-java repo turns out to be CRLF-heavy and hurts resolve rate, the fast-follow is a single normalized-line-ending retry on 0 matches (spec §风险与限制).
