package com.cafecli.cli;

import com.cafecli.agent.Agent;
import com.cafecli.agent.ForegroundAgentSpawner;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.config.CafeCliConfig;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.hitl.HitlToolRegistry;
import com.cafecli.hitl.SwitchableHitlHandler;
import com.cafecli.llm.LlmClient;
import com.cafecli.llm.LlmClientFactory;
import com.cafecli.mcp.McpServerManager;
import com.cafecli.memory.MemoryEntry;
import com.cafecli.rag.CodeIndex;
import com.cafecli.rag.CodeRelation;
import com.cafecli.rag.CodeRetriever;
import com.cafecli.rag.SearchResultFormatter;
import com.cafecli.render.Renderer;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.skill.SkillStateStore;
import com.cafecli.browser.BrowserConnectivityCheck;
import com.cafecli.browser.BrowserSession;

import org.jline.reader.LineReader;

import java.io.File;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 斜杠命令的执行体：{@link InteractiveRepl} 只做路由，命令怎么跑在这里。
 *
 * <p>这条边界的意义是**依赖方向**：REPL 的路由断言（斜杠不落到 Agent、未知命令不回退、提交先回写
 * 转写块）因此不必站起一个 Agent / MCP / Skill 注册表，而这里的每条命令仍然拿着它真正需要的协作者。
 * 命令体本身逐字保留自 {@code Main} 的原主循环，06 是零行为变化的纯重构。
 */
final class SlashCommandExecutor implements InteractiveRepl.SlashCommandRouter {

    private final Renderer renderer;
    private final LineReader lineReader;
    private final CafeCliConfig config;
    private final AtomicReference<LlmClient> llmClientRef;
    private final Agent reactAgent;
    private final SwitchableHitlHandler hitlHandler;
    private final ApprovalPump approvalPump;
    private final HitlToolRegistry hitlToolRegistry;
    private final McpServerManager mcpServerManager;
    private final SkillRegistry skillRegistry;
    private final SkillStateStore skillStateStore;
    private final BrowserSession browserSession;
    private final BrowserConnectivityCheck browserConnectivityCheck;
    private final TaskRegistry taskRegistry;
    private final CancellationRegistry cancellationRegistry;
    private final ForegroundAgentSpawner agentSpawner;
    /** 刷新底部状态坞——多条命令改完状态后都要走一次。 */
    private final Runnable refreshStatus;

    SlashCommandExecutor(Renderer renderer,
                         LineReader lineReader,
                         CafeCliConfig config,
                         AtomicReference<LlmClient> llmClientRef,
                         Agent reactAgent,
                         SwitchableHitlHandler hitlHandler,
                         ApprovalPump approvalPump,
                         HitlToolRegistry hitlToolRegistry,
                         McpServerManager mcpServerManager,
                         SkillRegistry skillRegistry,
                         SkillStateStore skillStateStore,
                         BrowserSession browserSession,
                         BrowserConnectivityCheck browserConnectivityCheck,
                         TaskRegistry taskRegistry,
                         CancellationRegistry cancellationRegistry,
                         ForegroundAgentSpawner agentSpawner,
                         Runnable refreshStatus) {
        this.renderer = renderer;
        this.lineReader = lineReader;
        this.config = config;
        this.llmClientRef = llmClientRef;
        this.reactAgent = reactAgent;
        this.hitlHandler = hitlHandler;
        this.approvalPump = approvalPump;
        this.hitlToolRegistry = hitlToolRegistry;
        this.mcpServerManager = mcpServerManager;
        this.skillRegistry = skillRegistry;
        this.skillStateStore = skillStateStore;
        this.browserSession = browserSession;
        this.browserConnectivityCheck = browserConnectivityCheck;
        this.taskRegistry = taskRegistry;
        this.cancellationRegistry = cancellationRegistry;
        this.agentSpawner = agentSpawner;
        this.refreshStatus = refreshStatus;
    }

    @Override
    public Outcome route(CliCommandParser.ParsedCommand command, String rawInput) {
        PrintStream ui = renderer.stream();
        switch (command.type()) {
            case UNKNOWN_COMMAND -> {
                ui.println("❌ 未知命令: " + command.payload());
                Main.printSlashCommandHelp(ui);
                return Outcome.HANDLED;
            }
            case TRANSCRIPT -> throw new IllegalStateException(
                    "transcript command must be handled before submitted-turn rendering");
            case EXIT -> {
                return Outcome.EXIT;   // 道别与关闭渲染器由 REPL 的退出路径统一承担
            }
            case CANCEL -> {
                ui.println("当前没有正在运行的任务。\n");
                return Outcome.HANDLED;
            }
            case CLEAR -> {
                reactAgent.clearHistory();
                hitlHandler.clearApprovedAll();
                renderer.clearView();
                refreshStatus.run();
                ui.println("🗑️ 当前对话历史已清空，长期记忆保持不变\n");
                return Outcome.HANDLED;
            }
            case HISTORY_CLEAR -> {
                Main.clearLineReaderHistory(lineReader);
                ui.println("🧹 输入历史已清空\n");
                return Outcome.HANDLED;
            }
            case CONTEXT_STATUS -> {
                ui.println("📋 上下文状态：");
                ui.println(reactAgent.getContextStatus());
                ui.println();
                return Outcome.HANDLED;
            }
            case MEMORY_STATUS -> {
                ui.println("📋 记忆系统状态：");
                ui.println(reactAgent.getMemoryManager().getSystemStatus());
                ui.println("   当前项目作用域: " + reactAgent.getMemoryManager().getCurrentProject());
                ui.println("   /memory list - 查看长期记忆");
                ui.println("   /memory search <关键词> - 搜索当前项目可见长期记忆");
                ui.println("   /memory delete <id> - 删除单条长期记忆");
                ui.println("   /memory clear - 清空长期记忆");
                ui.println("   /save <事实> - 保存项目级长期记忆；/save --global <事实> 保存全局记忆");
                ui.println();
                return Outcome.HANDLED;
            }
            case MEMORY_LIST -> {
                List<MemoryEntry> entries = reactAgent.getMemoryManager().listLongTerm();
                ui.println(Main.formatMemoryEntries("📋 长期记忆列表", entries));
                ui.println();
                return Outcome.HANDLED;
            }
            case MEMORY_SEARCH -> {
                String query = command.payload();
                if (query == null || query.isBlank()) {
                    ui.println("❌ 请提供搜索关键词，例如 /memory search Chrome 登录态\n");
                } else {
                    List<MemoryEntry> entries = reactAgent.getMemoryManager().searchLongTerm(query, 20);
                    ui.println(Main.formatMemoryEntries("🔎 长期记忆搜索: " + query, entries));
                    ui.println();
                }
                return Outcome.HANDLED;
            }
            case MEMORY_DELETE -> {
                String id = command.payload();
                if (id == null || id.isBlank()) {
                    ui.println("❌ 请提供要删除的记忆 id，例如 /memory delete fact-abcd1234\n");
                } else if (reactAgent.getMemoryManager().deleteLongTerm(id)) {
                    ui.println("🗑️ 已删除长期记忆: " + id + "\n");
                } else {
                    ui.println("📭 未找到长期记忆: " + id + "\n");
                }
                return Outcome.HANDLED;
            }
            case MEMORY_CLEAR -> {
                reactAgent.getMemoryManager().clearLongTerm();
                ui.println("🧹 长期记忆已清空\n");
                ui.println();
                return Outcome.HANDLED;
            }
            case MEMORY_SAVE -> {
                Main.MemorySaveRequest saveRequest = Main.parseMemorySave(command.payload());
                if (saveRequest.fact().isEmpty()) {
                    ui.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17，或 /save --global 默认用中文回答\n");
                } else {
                    reactAgent.getMemoryManager().storeFact(saveRequest.fact(), saveRequest.scope());
                    ui.println("💾 已保存到长期记忆(" + saveRequest.scope() + "): " + saveRequest.fact() + "\n");
                }
                return Outcome.HANDLED;
            }
            case PLAN_RETIRED -> {
                ui.println(Main.planTombstoneMessage());
                return Outcome.HANDLED;
            }
            case SWITCH_MODEL -> {
                String selection = command.payload();
                if (selection == null || selection.isEmpty()) {
                    ui.println("🤖 当前模型: " + llmClientRef.get().getModelName() + " (" + llmClientRef.get().getProviderName() + ")");
                    ui.println("   GLM 明确模型：");
                    ui.println("   /model glm-5.1       - 切换到 GLM-5.1");
                    ui.println("   /model glm-5v-turbo  - 切换到 GLM-5V-Turbo 多模态");
                    ui.println("   其它 provider 使用你配置里的具体模型：");
                    ui.println("   /model deepseek      - 切换到 DeepSeek（读取配置模型）");
                    ui.println("   /model step          - 切换到 StepFun（读取配置模型）");
                    ui.println("   /model kimi          - 切换到 Kimi（读取配置模型）");
                    ui.println("   /model freellmapi    - 切换到本地 FreeLLMAPI（读取配置模型）\n");
                } else {
                    Main.ModelSelection target = Main.resolveModelSelection(selection);
                    if (target.explicitModel()) {
                        Main.ensureProviderConfig(config, target.provider()).setModel(target.model());
                    }
                    LlmClient newClient = LlmClientFactory.create(target.provider(), config);
                    if (newClient == null) {
                        ui.println("❌ 切换失败：未配置 " + target.provider() + " 的 API Key\n");
                    } else {
                        llmClientRef.set(newClient);
                        config.setDefaultProvider(target.provider());
                        config.save();
                        reactAgent.setLlmClient(newClient);
                        ui.println("✅ 已切换到: " + llmClientRef.get().getModelName() + " (" + llmClientRef.get().getProviderName() + ")");
                        ui.println("   上下文策略: " + reactAgent.getMemoryManager().getContextProfile().summary());
                        ui.println("   对话上下文已保留，使用 /clear 可清空\n");
                        refreshStatus.run();
                    }
                }
                return Outcome.HANDLED;
            }
            case SWITCH_HITL -> {
                String payload = command.payload();
                if ("on".equals(payload)) {
                    Main.toggleHitl(hitlHandler, approvalPump, true);
                    ui.println("🔒 HITL 审批已启用：write_file / execute_command / create_project 执行前将请求人工确认\n");
                } else if ("off".equals(payload)) {
                    Main.toggleHitl(hitlHandler, approvalPump, false);
                    ui.println("🔓 HITL 审批已关闭：危险操作将直接执行\n");
                } else {
                    String status = hitlHandler.isEnabled() ? "启用" : "关闭";
                    ui.println("🔒 HITL 当前状态：" + status);
                    ui.println("   /hitl on  - 启用人工审批");
                    ui.println("   /hitl off - 关闭人工审批\n");
                }
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case POLICY_STATUS -> {
                Main.printPolicyStatus(ui, reactAgent);
                return Outcome.HANDLED;
            }
            case CONFIG -> {
                if (command.payload() == null || command.payload().isBlank()) {
                    Main.handleConfigPalette(renderer, config, llmClientRef.get(), hitlHandler, skillRegistry);
                } else {
                    ui.println(Main.handleConfigCommand(config, command.payload()));
                    refreshStatus.run();
                }
                return Outcome.HANDLED;
            }
            case AUDIT_TAIL -> {
                Main.printAuditTail(ui, reactAgent, command.payload());
                return Outcome.HANDLED;
            }
            case SNAPSHOT -> {
                Main.printSnapshotCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                return Outcome.HANDLED;
            }
            case RESTORE_SNAPSHOT -> {
                Main.printRestoreCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                return Outcome.HANDLED;
            }
            case MCP_LIST -> {
                ui.println(mcpServerManager.formatStatus());
                ui.println();
                return Outcome.HANDLED;
            }
            case MCP_RESTART -> {
                Main.printMcpCommandResult(ui, mcpServerManager.restart(command.payload()));
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case MCP_LOGS -> {
                Main.printMcpCommandResult(ui, mcpServerManager.logs(command.payload()));
                return Outcome.HANDLED;
            }
            case MCP_DISABLE -> {
                Main.printMcpCommandResult(ui, mcpServerManager.disable(command.payload()));
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case MCP_ENABLE -> {
                Main.printMcpCommandResult(ui, mcpServerManager.enable(command.payload()));
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case MCP_RESOURCES -> {
                Main.printMcpCommandResult(ui, mcpServerManager.resources(command.payload()));
                return Outcome.HANDLED;
            }
            case MCP_PROMPTS -> {
                Main.printMcpCommandResult(ui, mcpServerManager.prompts(command.payload()));
                return Outcome.HANDLED;
            }
            case HOOKS -> {
                ui.println(Main.formatHookListing(reactAgent));
                return Outcome.HANDLED;
            }
            case BROWSER -> {
                Main.printMcpCommandResult(ui, Main.handleBrowserCommand(
                        command.payload(),
                        browserSession,
                        browserConnectivityCheck,
                        mcpServerManager,
                        hitlToolRegistry,
                        hitlHandler));
                return Outcome.HANDLED;
            }
            case TASK -> {
                ui.println(com.cafecli.agent.task.SessionTaskCommand.handle(
                        taskRegistry, cancellationRegistry, agentSpawner, command.payload()));
                ui.println();
                return Outcome.HANDLED;
            }
            case SKILL_LIST -> {
                ui.println(SkillCommandHandler.list(skillRegistry));
                return Outcome.HANDLED;
            }
            case SKILL_SHOW -> {
                ui.println(SkillCommandHandler.show(skillRegistry, command.payload()));
                return Outcome.HANDLED;
            }
            case SKILL_ON -> {
                ui.println(SkillCommandHandler.enable(skillRegistry, skillStateStore, command.payload()));
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case SKILL_OFF -> {
                ui.println(SkillCommandHandler.disable(skillRegistry, skillStateStore, command.payload()));
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case SKILL_RELOAD -> {
                skillRegistry.reload();
                ui.println("🔄 已重新扫描 skill 目录");
                ui.println(SkillCommandHandler.startupSummary(skillRegistry));
                ui.println("✅ 下一轮 LLM 调用生效");
                refreshStatus.run();
                return Outcome.HANDLED;
            }
            case INDEX_CODE -> {
                String indexPath = command.payload() != null ? command.payload() : ".";
                CodeIndex indexer = new CodeIndex(ui::println);
                indexer.index(indexPath);
                ui.println();

                // 同步项目路径到 ToolRegistry，让 search_code 工具可以正常工作
                String absPath = new File(indexPath).getAbsolutePath();
                reactAgent.getToolRegistry().setProjectPath(absPath);
                reactAgent.getMemoryManager().setProjectPath(absPath);
                return Outcome.HANDLED;
            }
            case SEARCH_CODE -> {
                String query = command.payload();
                if (query == null || query.isEmpty()) {
                    ui.println("❌ 请提供检索关键词，例如 /search 用户登录实现\n");
                    return Outcome.HANDLED;
                }
                ui.println("🔍 检索: " + query);
                try (CodeRetriever retriever = new CodeRetriever(".")) {
                    var stats = retriever.getStats();
                    if (stats.chunkCount() == 0) {
                        ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                        return Outcome.HANDLED;
                    }
                    List<com.cafecli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);
                    if (results.isEmpty()) {
                        ui.println("📭 未找到相关代码\n");
                    } else {
                        ui.println(SearchResultFormatter.formatForCli(query, results) + "\n");
                    }
                } catch (Exception e) {
                    ui.println("❌ 检索失败: " + e.getMessage() + "\n");
                }
                return Outcome.HANDLED;
            }
            case GRAPH_QUERY -> {
                String className = command.payload();
                if (className == null || className.isEmpty()) {
                    ui.println("❌ 请提供类名，例如 /graph Main\n");
                    return Outcome.HANDLED;
                }
                ui.println("🕸️ 查询类关系图谱: " + className);
                try (CodeRetriever retriever = new CodeRetriever(".")) {
                    var stats = retriever.getStats();
                    if (stats.chunkCount() == 0) {
                        ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                        return Outcome.HANDLED;
                    }
                    List<CodeRelation> relations = retriever.getRelationGraph(className);
                    if (relations.isEmpty()) {
                        ui.println("📭 未找到相关关系\n");
                    } else {
                        ui.println("📋 找到 " + relations.size() + " 条关系:\n");
                        for (CodeRelation rel : relations) {
                            String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                    : rel.relationType().equals("extends") ? "└── extends -->"
                                    : rel.relationType().equals("implements") ? "└── implements -->"
                                    : rel.relationType().equals("calls") ? "├── calls -->"
                                    : "├── " + rel.relationType() + " -->";
                            ui.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                    rel.toName() != null ? rel.toName() : "unknown");
                        }
                        ui.println();
                    }
                } catch (Exception e) {
                    ui.println("❌ 查询失败: " + e.getMessage() + "\n");
                }
                return Outcome.HANDLED;
            }
            case NONE -> throw new IllegalStateException(
                    "plain input never reaches the slash-command executor");
        }
        return Outcome.HANDLED;
    }
}
