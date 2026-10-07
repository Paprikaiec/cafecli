package com.cafecli.tool;

import com.cafecli.agent.task.AgentSpawner;
import com.cafecli.browser.BrowserConnector;
import com.cafecli.lsp.LspManager;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.skill.SkillContextBuffer;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.snapshot.RestoreResult;
import com.cafecli.snapshot.SnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拆出去的工具组（{@link CommandTool} / {@link SearchTools} / {@link FileTools}）必须在<b>每次调用时</b>经
 * {@link ToolEnv} 现取项目根、路径围栏、写入观察者与 LSP 管理器，而不是在注册时抓值。
 *
 * <p>会在注册之后换掉这些状态的路径：{@code setProjectPath}、{@code setWriteFileObserver} 与 {@code setLspManager}。
 * 若工具抓的是注册时的值，围栏与命令工作目录会停在 JVM 工作目录上——项目内的文件被拒、相对路径写到错误的目录里。
 * 子任务 registry 的工具则必须读到子任务自己这份状态（父的工作区、父 LSP 的 fork），而不是 JVM 工作目录或父的那份。
 * 所以项目目录必须<b>不在</b> {@code user.dir} 之下，否则判据失明。
 *
 * <p>只有一个组用到的协作者（{@link TaskTools} / {@link BrowserTools} / {@link SessionTools} / {@link WebTools}）以 supplier
 * 传进组构造器，规则相同：在一个已构造好的 registry 上<b>事后</b> set，工具必须用到的是 set 进来的那个。
 */
class ToolEnvWiringTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void childRegistryFencesSearchAndRunsCommandsInTheParentsProject(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("projectA"));
        Path outside = Files.createDirectories(tempDir.resolve("outsideB"));
        String token = seed(project);
        seed(outside);
        assertNotUnderJvmWorkingDirectory(project);

        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(project.toString());
        ToolRegistry child = parent.createChildRegistry();

        assertFencedTo(child, project, outside, token);
    }

    @Test
    void setProjectPathAfterConstructionMovesTheFenceAndTheCommandDirectory(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("projectB"));
        Path outside = Files.createDirectories(tempDir.resolve("outsideA"));
        String token = seed(project);
        seed(outside);
        assertNotUnderJvmWorkingDirectory(project);

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());

        assertFencedTo(registry, project, outside, token);
    }

    @Test
    void childRegistryFileToolsResolveAgainstTheParentsProject(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("projectA"));
        Path outside = Files.createDirectories(tempDir.resolve("outsideB"));
        seed(project);
        seed(outside);
        assertNotUnderJvmWorkingDirectory(project);

        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(project.toString());
        ToolRegistry child = parent.createChildRegistry();

        assertFileToolsFencedTo(child, project, outside);
    }

    @Test
    void setProjectPathAfterConstructionMovesTheFileToolsFence(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("projectB"));
        Path outside = Files.createDirectories(tempDir.resolve("outsideA"));
        seed(project);
        seed(outside);
        assertNotUnderJvmWorkingDirectory(project);

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());

        assertFileToolsFencedTo(registry, project, outside);
    }

    @Test
    void writeObserverAndLspManagerSetAfterConstructionAreWhatWriteAndEditUse(@TempDir Path tempDir)
            throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("projectC"));
        assertNotUnderJvmWorkingDirectory(project);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        List<String> written = new CopyOnWriteArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) -> written.add(path + "=" + beforeAfter[1]));
        RecordingLspManager lsp = new RecordingLspManager(project.toString());
        registry.setLspManager(lsp);

        assertWriteAndEditReach(registry, project, written, lsp);
    }

    @Test
    void childWritesReachTheChildsForkedLspAndItsOwnObserver(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("projectD"));
        assertNotUnderJvmWorkingDirectory(project);
        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(project.toString());
        RecordingLspManager parentLsp = new RecordingLspManager(project.toString());
        parent.setLspManager(parentLsp);
        List<String> parentWritten = new CopyOnWriteArrayList<>();
        parent.setWriteFileObserver((path, beforeAfter) -> parentWritten.add(path));

        // 子任务的工具必须绑在子任务自己的状态上：写入要进父 LSP 的 fork，不能进父的那份
        ToolRegistry child = parent.createChildRegistry();
        RecordingLspManager childLsp = assertInstanceOf(RecordingLspManager.class, child.getLspManager());
        assertNotSame(parentLsp, childLsp);
        List<String> childWritten = new CopyOnWriteArrayList<>();
        child.setWriteFileObserver((path, beforeAfter) -> childWritten.add(path + "=" + beforeAfter[1]));

        assertWriteAndEditReach(child, project, childWritten, childLsp);
        assertEquals(List.of(), parentLsp.calls, "子任务的 post-edit 诊断不能进父的 pending 缓冲");
        assertEquals(List.of(), parentWritten, "子任务的写入不走父的观察者");
    }

    @Test
    void agentSpawnerSetAfterConstructionIsWhatTheTaskToolsUse() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.executeTool("spawn_agent", "{\"prompt\":\"p\"}").startsWith("spawn_agent 暂不可用"));

        registry.setAgentSpawner(new AgentSpawner() {
            @Override public String spawn(String d, String p) { return "spawn:" + d + "/" + p; }
            @Override public String spawnBackground(String d, String p) { return "background:" + d + "/" + p; }
            @Override public String continueTask(String id, String m) { return "continue:" + id + "/" + m; }
            @Override public String taskOutput(String id) { return "output:" + id; }
        });

        assertEquals("spawn:d/p", registry.executeTool("spawn_agent", "{\"description\":\"d\",\"prompt\":\"p\"}"));
        assertEquals("background:d/p", registry.executeTool("spawn_agent",
                "{\"description\":\"d\",\"prompt\":\"p\",\"background\":true}"));
        assertEquals("continue:task_1/m", registry.executeTool("send_message", "{\"to\":\"task_1\",\"message\":\"m\"}"));
        assertEquals("output:task_1", registry.executeTool("task_output", "{\"task_id\":\"task_1\"}"));
    }

    @Test
    void browserConnectorSetAfterConstructionIsWhatTheBrowserToolsUse() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.executeTool("browser_status", "{}").startsWith("浏览器连接器未初始化"));

        registry.setBrowserConnector(new BrowserConnector() {
            @Override public String status() { return "fake-status"; }
            @Override public String connectDefault() { return "fake-connect"; }
            @Override public String disconnect() { return "fake-disconnect"; }
        });

        assertEquals("fake-connect", registry.executeTool("browser_connect", "{}"));
        assertEquals("fake-disconnect", registry.executeTool("browser_disconnect", "{}"));
        assertEquals("fake-status", registry.executeTool("browser_status", "{}"));
    }

    @Test
    void memorySaverSetAfterConstructionIsWhatSaveMemoryUses() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new CopyOnWriteArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\" likes tabs \",\"scope\":\"GLOBAL\"}");

        assertEquals("💾 已保存到长期记忆(global): likes tabs", result);
        assertEquals(List.of("global:likes tabs"), saved);
    }

    @Test
    void childSaveMemoryUsesTheSaverTheParentHadWhenItForked() {
        ToolRegistry parent = new ToolRegistry();
        List<String> saved = new CopyOnWriteArrayList<>();
        parent.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        // 子任务的 save_memory 用的是 fork 那一刻从父接过的 memorySaver
        ToolRegistry child = parent.createChildRegistry();
        String result = child.executeTool("save_memory", "{\"fact\":\"from child\"}");

        assertEquals("💾 已保存到长期记忆(project): from child", result);
        assertEquals(List.of("project:from child"), saved);
    }

    @Test
    void skillRegistryAndBufferSetAfterConstructionAreWhatLoadSkillUses(@TempDir Path tempDir) throws Exception {
        Path skillDir = Files.createDirectories(tempDir.resolve("user-skills").resolve("wiring-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: wiring-skill\ndescription: d\n---\nWIRING_SKILL_BODY\n");
        SkillRegistry skills = new SkillRegistry(null, tempDir.resolve("user-skills"), null, null);
        skills.reload();
        ToolRegistry registry = new ToolRegistry();
        assertEquals("load_skill 失败: Skill 系统未初始化",
                registry.executeTool("load_skill", "{\"name\":\"wiring-skill\"}"));

        SkillContextBuffer buffer = new SkillContextBuffer();
        registry.setSkillRegistry(skills);
        registry.setSkillContextBuffer(buffer);
        String result = registry.executeTool("load_skill", "{\"name\":\"wiring-skill\"}");

        assertTrue(result.startsWith("已加载 skill 'wiring-skill'"), result);
        assertTrue(buffer.drain().contains("WIRING_SKILL_BODY"), "Skill 正文必须进注册之后 set 的那个缓冲");
    }

    @Test
    void snapshotServiceSetAfterConstructionIsWhatRevertTurnUses() {
        ToolRegistry registry = new ToolRegistry();
        List<Integer> offsets = new CopyOnWriteArrayList<>();
        SnapshotService fake = new SnapshotService(null) {
            @Override
            public RestoreResult restorePreTurn(int offset) {
                offsets.add(offset);
                return RestoreResult.failure("fake-restore");
            }
        };
        try {
            registry.setSnapshotService(fake);
            // 偏移量故意取一个不可能存在的值：万一接线退化成抓注册期那份（指向 JVM 工作目录）的快照服务，
            // 真实服务只会报「找不到」而不会改写工作目录里的任何文件。
            assertEquals("❌ fake-restore", registry.executeTool("revert_turn", "{\"offset\":99999}"));
            assertEquals(List.of(99999), offsets);
        } finally {
            fake.close();
        }
    }

    /** StepSearch 回调必须经可覆写的 {@code executeToolOutput} 重入（HITL 靠覆写它拦截），模型选择与 MCP 工具都是事后才设的。 */
    @Test
    void stepSearchRouteReentersTheOverridableExecuteToolOutput() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public ToolOutput executeToolOutput(String name, String argumentsJson) {
                seen.add(name);
                return super.executeToolOutput(name, argumentsJson);
            }
        };
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpToolOutput(new McpToolDescriptor("step_search", "web_search",
                        "mcp__step_search__web_search", "d", MAPPER.readTree(
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"top_k\":{\"type\":\"integer\"}}}")),
                args -> ToolOutput.text("step:" + args));

        String result = registry.executeTool("web_search", "{\"query\":\"q\",\"top_k\":2}");

        assertEquals("🔍 [StepSearch] q\n\nstep:{\"query\":\"q\",\"top_k\":2}", result);
        assertEquals(List.of("web_search", "mcp__step_search__web_search"), seen);
    }

    /** write_file 新建 + edit_file 改写同一个 .java 文件（落在 {@code project} 里）：两次都要通知观察者并跑 post-edit LSP。 */
    private static void assertWriteAndEditReach(ToolRegistry registry, Path project, List<String> written,
                                                RecordingLspManager lsp) throws Exception {
        String name = "Hello_" + Long.toHexString(System.nanoTime()) + ".java";
        String write = call(registry, "write_file", Map.of("path", name, "content", "class Hello { int v = 1; }\n"));
        assertTrue(write.startsWith("文件已写入"), write);
        assertTrue(Files.isRegularFile(project.resolve(name)), "write_file 应落在项目里: " + project.resolve(name));
        String edit = call(registry, "edit_file", Map.of("path", name,
                "edits", List.of(Map.of("old_string", "v = 1", "new_string", "v = 2"))));
        assertTrue(edit.startsWith("文件已编辑"), edit);

        assertEquals(List.of(name + "=class Hello { int v = 1; }\n", name + "=class Hello { int v = 2; }\n"),
                written);
        assertEquals(List.of(name + ":class Hello { int v = 1; }\n", name + ":class Hello { int v = 2; }\n"),
                lsp.calls);
    }

    /**
     * 五个文件工具的相对路径都得落在 {@code project} 里（而不是 JVM 工作目录），{@code project} 之外的绝对路径一律被围栏拒绝。
     */
    private static void assertFileToolsFencedTo(ToolRegistry registry, Path project, Path outside) throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir"));
        String unique = "wiring_" + Long.toHexString(System.nanoTime());
        Files.writeString(project.resolve("src/Edit.txt"), "alpha " + unique + "\n");

        String read = call(registry, "read_file", Map.of("path", "src/Edit.txt"));
        assertTrue(read.startsWith("文件内容") && read.contains(unique), read);

        String written = unique + "-written.txt";
        String write = call(registry, "write_file", Map.of("path", written, "content", "hello"));
        assertTrue(write.startsWith("文件已写入"), write);
        assertEquals("hello", Files.readString(project.resolve(written)));
        assertFalse(Files.exists(cwd.resolve(written)), "write_file 不能落到 JVM 工作目录: " + cwd.resolve(written));

        String edit = call(registry, "edit_file", Map.of("path", "src/Edit.txt",
                "edits", List.of(Map.of("old_string", "alpha", "new_string", "beta"))));
        assertTrue(edit.startsWith("文件已编辑"), edit);
        assertEquals("beta " + unique + "\n", Files.readString(project.resolve("src/Edit.txt")));

        String list = call(registry, "list_dir", Map.of("path", "."));
        assertTrue(list.startsWith("目录内容") && list.contains("[F] " + written) && list.contains("[D] src"), list);

        String created = unique + "-proj";
        String create = call(registry, "create_project", Map.of("name", created, "type", "node"));
        assertTrue(create.startsWith("项目已创建"), create);
        assertTrue(Files.isRegularFile(project.resolve(created).resolve("package.json")), create);
        assertFalse(Files.exists(cwd.resolve(created)), "create_project 不能落到 JVM 工作目录: " + cwd.resolve(created));

        Path outsideFile = outside.resolve("src").resolve("Needle.java");
        String outsideBefore = Files.readString(outsideFile);
        String readOutside = call(registry, "read_file", Map.of("path", outsideFile.toString()));
        assertTrue(readOutside.startsWith("🛡️"), readOutside);
        String writeOutside = call(registry, "write_file", Map.of("path", outsideFile.toString(), "content", "pwned"));
        assertTrue(writeOutside.startsWith("🛡️"), writeOutside);
        String editOutside = call(registry, "edit_file", Map.of("path", outsideFile.toString(),
                "edits", List.of(Map.of("old_string", "class", "new_string", "pwned"))));
        assertTrue(editOutside.startsWith("🛡️"), editOutside);
        String listOutside = call(registry, "list_dir", Map.of("path", outside.toString()));
        assertTrue(listOutside.startsWith("🛡️"), listOutside);
        assertEquals(outsideBefore, Files.readString(outsideFile), "围栏外的文件必须一字节不变");
    }

    private static void assertFencedTo(ToolRegistry registry, Path project, Path outside, String token)
            throws Exception {
        String insideAbsolute = project.resolve("src").toString();

        String globRelative = call(registry, "glob_files", Map.of("pattern", "**/Needle.java", "path", "src"));
        assertTrue(globRelative.startsWith("匹配文件") && globRelative.contains("Needle.java"), globRelative);
        String globAbsolute = call(registry, "glob_files", Map.of("pattern", "**/Needle.java", "path", insideAbsolute));
        assertTrue(globAbsolute.startsWith("匹配文件") && globAbsolute.contains("Needle.java"), globAbsolute);

        String grepRelative = call(registry, "grep_code", Map.of("pattern", token, "path", "src"));
        assertTrue(grepRelative.startsWith("匹配结果") && grepRelative.contains("Needle.java"), grepRelative);
        String grepAbsolute = call(registry, "grep_code", Map.of("pattern", token, "path", insideAbsolute));
        assertTrue(grepAbsolute.startsWith("匹配结果") && grepAbsolute.contains("Needle.java"), grepAbsolute);

        String globOutside = call(registry, "glob_files", Map.of("pattern", "**/Needle.java", "path", outside.toString()));
        assertTrue(globOutside.startsWith("🛡️"), globOutside);
        String grepOutside = call(registry, "grep_code", Map.of("pattern", token, "path", outside.toString()));
        assertTrue(grepOutside.startsWith("🛡️"), grepOutside);

        String pwd = call(registry, "execute_command", Map.of("command", "pwd -P"));
        String expected = project.toRealPath().toString();
        assertTrue(pwd.lines().anyMatch(expected::equals), "命令应跑在 " + expected + "，实际: " + pwd);
    }

    /** 在 {@code dir/src/Needle.java} 写一个仓库里不可能出现的随机标记，避免误命中 JVM 工作目录下的源码。 */
    private static String seed(Path dir) throws Exception {
        String token = "wiring_" + Long.toHexString(System.nanoTime()) + "_" + Integer.toHexString(dir.hashCode());
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("Needle.java"), "class Needle { String t = \"" + token + "\"; }\n");
        return token;
    }

    private static void assertNotUnderJvmWorkingDirectory(Path project) throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir")).toRealPath();
        assertFalse(project.toRealPath().startsWith(cwd),
                "项目目录不能在 JVM 工作目录之下，否则抓值缺陷与正确接线不可区分: " + project);
    }

    private static String call(ToolRegistry registry, String tool, Map<String, ?> args) throws Exception {
        return registry.executeTool(tool, MAPPER.writeValueAsString(args));
    }

    /** 记录 post-edit LSP 调用（路径 + 当时磁盘内容）；fork 出来的也是记录器，各自一份 calls。 */
    private static final class RecordingLspManager extends LspManager {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final String projectPath;

        private RecordingLspManager(String projectPath) {
            super(projectPath);
            this.projectPath = projectPath;
        }

        @Override
        public LspManager forkSharingProcess() {
            return new RecordingLspManager(projectPath);
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
}
