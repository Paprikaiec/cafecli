package com.cafecli.cli;

import com.cafecli.agent.Agent;
import com.cafecli.hook.UserPromptSubmitGate;
import com.cafecli.agent.AgentSession;
import com.cafecli.browser.BrowserAuditMetadata;
import com.cafecli.browser.BrowserConnectivityCheck;
import com.cafecli.browser.BrowserGuard;
import com.cafecli.browser.BrowserMode;
import com.cafecli.browser.BrowserSession;
import com.cafecli.browser.SensitivePagePolicy;
import com.cafecli.config.CafeCliConfig;
import com.cafecli.hitl.HitlHandler;
import com.cafecli.hitl.HitlToolRegistry;
import com.cafecli.hitl.SwitchableHitlHandler;
import com.cafecli.hitl.RendererHitlHandler;
import com.cafecli.hitl.TerminalHitlHandler;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.LlmClient;
import com.cafecli.llm.LlmClientFactory;
import com.cafecli.memory.LongTermMemory;
import com.cafecli.memory.MemoryEntry;
import com.cafecli.render.Renderer;
import com.cafecli.render.RendererFactory;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.inline.CafeCliLineReader;
import com.cafecli.render.inline.InlineRenderer;
import com.cafecli.render.inline.SubmittedPromptBlock;
import com.cafecli.render.inline.TerminalCapabilities;
import com.cafecli.image.ClipboardImage;
import com.cafecli.mcp.McpServer;
import com.cafecli.mcp.McpServerManager;
import com.cafecli.mcp.McpServerStatus;
import com.cafecli.mcp.mention.AtMentionExpander;
import com.cafecli.rag.CodeIndex;
import com.cafecli.hitl.ApprovalPolicy;
import com.cafecli.policy.AuditLog;
import com.cafecli.rag.CodeRetriever;
import com.cafecli.rag.CodeRelation;
import com.cafecli.rag.SearchResultFormatter;
import com.cafecli.runtime.CancellationToken;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.runtime.api.RuntimeApiServer;
import com.cafecli.runtime.api.RuntimeThreadStore;
import com.cafecli.snapshot.RestoreResult;
import com.cafecli.snapshot.SnapshotService;
import com.cafecli.snapshot.TurnSnapshot;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.tool.ToolRegistry;
import com.cafecli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Reference;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.AttributedString;
import org.jline.widget.AutopairWidgets;
import org.jline.console.CmdDesc;
import org.jline.keymap.KeyMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Cafecli v1.0.0 - Terminal-First Agent IDE
 * 支持 ReAct、Memory、RAG、会话内异步任务、HITL、并行工具调用、多模型切换、MCP、CDP 会话复用
 * 第 15 期新增：Skill 系统（三层加载 + load_skill 工具 + SkillContextBuffer 注入）、内置 web-access skill
 * 第 16 期新增：终端渲染产品化（代码高亮、对话历史可视化、配置管理面板）
 * 第 16.1 期形态修正：抽出 Renderer 接口 + 两个实现（inline/plain），默认形态为 inline 流式 TUI（Claude Code 风格）
 *   - inline 流式：prompt 下方 inline 状态区、行内可折叠工具块、行内 git diff、单字符 HITL 提示、命令 palette
 *   - plain：纯 println 兜底（CAFECLI_RENDERER=plain）
 * ADR 0015：Lanterna 全屏 TUI（旧 CAFECLI_RENDERER=lanterna / CAFECLI_TUI=true）已移除
 * HITL 增强：路径围栏（PathGuard）、命令快速拒绝（CommandGuard）、操作审计链（AuditLog）—— 见 com.cafecli.policy
 */
public class Main {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.0.0";
    private static final String ENV_FILE = ".env";
    private static final String LOG_DIR_PROPERTY = "paicli.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "paicli.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "paicli.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "paicli.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "paicli.log.totalSizeCap";
    private static final String HISTORY_FILE_PROPERTY = "paicli.history.file";
    private static final String HISTORY_SIZE_PROPERTY = "paicli.history.size";
    private static final String HISTORY_FILE_SIZE_PROPERTY = "paicli.history.fileSize";
    private static final String DEFAULT_HISTORY_FILE_NAME = "input.history";
    private static final String ARROW_UP = "[A";
    private static final String ARROW_DOWN = "[B";
    private static final String APP_ARROW_UP = "OA";
    private static final String APP_ARROW_DOWN = "OB";
    private static final Pattern SENSITIVE_FLAG_VALUE = Pattern.compile(
            "(?i)(--?(?:api[_-]?key|authorization|password|passwd|secret|token)\\s+)(\\S+)");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|authorization|password|passwd|secret|token)\\s*[=:]\\s*)(\\S+)");
    /**
     * ESC 的歧义等待窗口（ADR 0016 D2）。
     *
     * <p>ESC 是每个方向键 / CSI 序列的**前缀**，所以 JLine 的键映射要等这么久才触发较短的那个匹配。
     * 默认是 1000ms，而被本 issue 删掉的手写分类器等的是 80/20/120ms——照默认走会把「停下这次输出」
     * 变成整整一秒。JLine 的歧义超时**就是**那个分类器（同一个「等一等看后面还有没有」的算法），
     * 所以对齐窗口即可让延迟与失败模式都停在今天的位置。更短（~40ms）被否决：误触发的取消是破坏性的，
     * 而这点差别在 33ms 的帧节奏面前不可感知。
     */
    private static final long ESC_AMBIGUOUS_BINDING_MILLIS = 100L;

    private static final int CTRL_O = 15;
    private static final int CTRL_Y = 25;
    private static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    private record StartupScreenInfo(
            String model,
            String provider,
            long mcpReady,
            int mcpTotal,
            int mcpTools,
            int skillsEnabled,
            int skillsTotal,
            String note
    ) {
    }

    public static void main(String[] args) {
        com.cafecli.config.ConfigHome.installPropertyAliases();
        configureAwtForCli();
        if (isRuntimeServeCommand(args)) {
            configureLogging();
            startRuntimeApiAndBlock(args);
            return;
        }

        if (isRunCommand(args)) {
            configureLogging();
            runHeadlessCommand(args);
            return;
        }

        configureLogging();

        CafeCliConfig config = CafeCliConfig.load();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 GLM_API_KEY、DEEPSEEK_API_KEY、STEP_API_KEY、KIMI_API_KEY 或 FREELLMAPI_API_KEY");
            System.exit(1);
        }
        AtomicReference<LlmClient> llmClientRef = new AtomicReference<>(llmClient);

        try (Terminal terminal = TerminalCapabilities.systemTerminalBuilder().build()) {
            refreshTerminalColumns(terminal);
            TerminalHitlHandler terminalHitlHandler = new TerminalHitlHandler(false);
            SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(terminalHitlHandler);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);
            Runtime.getRuntime().addShutdownHook(new Thread(hitlToolRegistry::clearResultSession,
                    "cafecli-tool-results-cleanup"));
            BrowserSession browserSession = new BrowserSession();
            BrowserConnectivityCheck browserConnectivityCheck = new BrowserConnectivityCheck();
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
            AtomicReference<SkillRegistry> skillRegistryRef = new AtomicReference<>();
            hitlToolRegistry.setBrowserConnector(new com.cafecli.browser.BrowserConnector() {
                @Override
                public String status() {
                    return handleBrowserCommand("status", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String connectDefault() {
                    return handleBrowserCommand("connect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String disconnect() {
                    return handleBrowserCommand("disconnect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }
            });

            // JLine-first：启动输出、命令输出、Agent 流式内容都走同一条 Renderer.stream() 通道。
            // inline 首屏要挂到 LineReader 首次初始化回调里，避免在 readLine 接管屏幕前用裸输出抢光标。
            // renderer 必须先于 lineReader 建立：行编辑器要不要交出画笔，取决于这个终端上有没有活区。
            Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);

            // 行编辑器建构（ADR 0014 决策 2）：JLine 的 LineReaderBuilder 不可用于子类
            // （final + 私有构造 + build() 硬编码 LineReaderImpl），故手工构造 + 逐项 setter；
            // variables 只能走构造器（INPUT_RC_FILE_NAME 在构造期即被读取）。
            // 降级路径：终端能力不足或非 inline renderer 时装原生行编辑器，形态与今天一致。
            CafeCliHistory history = new CafeCliHistory();
            CafeCliCompleter completer = new CafeCliCompleter(mcpServerManager::resourceCandidates,
                    () -> skillRegistryRef.get() == null ? List.of() : skillRegistryRef.get().allSkills());
            CafeCliHighlighter highlighter = new CafeCliHighlighter();
            boolean handOverBrush = renderer instanceof InlineRenderer
                    && CafeCliLineReader.supported(terminal);
            // variables 只能走构造器：defaultKeyMaps() 在 LineReaderImpl 构造期就把它们读走了
            // （ADR 0014 记的是 INPUT_RC_FILE_NAME，AMBIGUOUS_BINDING 是第二例，所以这是一条关于
            // variables 的规则，不是某个键的怪癖）。
            Map<String, Object> readerVariables = new HashMap<>();
            readerVariables.put(LineReader.AMBIGUOUS_BINDING, ESC_AMBIGUOUS_BINDING_MILLIS);
            LineReader lineReader = handOverBrush
                    ? CafeCliLineReader.create(terminal, "cafecli", readerVariables,
                            history, completer, highlighter)
                    : LineReaderBuilder.builder()
                            .terminal(terminal)
                            .history(history)
                            .completer(completer)
                            .highlighter(highlighter)
                            .variables(readerVariables)
                            .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);
            lineReader.option(LineReader.Option.AUTO_LIST, true);
            lineReader.option(LineReader.Option.AUTO_MENU, true);
            configureHistory(lineReader, Path.of(System.getProperty("user.home")));
            configureSlashCommandHint(lineReader);
            configureJLineInteractiveWidgets(lineReader);

            // HITL 审批改走泵（ADR 0005，#11）：worker park 在 future，主线程输入泵渲染（renderer.promptApproval）+ resolve，
            // 读终端只在主线程发生，根治执行器线程读终端 vs 主线程 LineReader 抢终端的死锁。
            ApprovalPump approvalPump = new ApprovalPump();
            approvalPump.setEnabled(hitlHandler.isEnabled());
            hitlHandler.setDelegate(approvalPump);
            if (renderer instanceof InlineRenderer inline) {
                inline.bindLineReader(lineReader);
            }
            PrintStream ui = renderer.stream();
            renderer.start();
            renderer.updateStatus(statusInfo(llmClient, hitlHandler, "idle", mcpServerManager, null));

            String startupNote = "";
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));
                if (!bootstrapResult.message().isBlank()) {
                    startupNote = bootstrapResult.message();
                }
                mcpServerManager.loadConfiguredServers();
                mcpServerManager.startAll(ui, mcpStartupWait());
                Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close, "cafecli-mcp-shutdown"));
            } catch (Exception e) {
                startupNote = "MCP 初始化失败: " + e.getMessage();
            }
            AtMentionExpander mentionExpander = new AtMentionExpander(mcpServerManager);
            LocalPathMentionExpander localPathMentionExpander = new LocalPathMentionExpander(Path.of("."));

            // === Skill 系统初始化 ===
            Path userConfigHome = com.cafecli.config.ConfigHome.userConfigDir();
            Path projectConfigHome = com.cafecli.config.ConfigHome.projectConfigDir();
            Path skillsCacheDir = userConfigHome.resolve("skills-cache");
            Path userSkillsDir = userConfigHome.resolve("skills");
            Path projectSkillsDir = projectConfigHome.resolve("skills").toAbsolutePath();
            try {
                new com.cafecli.skill.SkillBuiltinExtractor(skillsCacheDir).extractAll();
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "内置 skill 解压失败: " + e.getMessage());
            }
            com.cafecli.skill.SkillStateStore skillStateStore =
                    new com.cafecli.skill.SkillStateStore(userConfigHome.resolve("skills.json"));
            com.cafecli.skill.SkillRegistry skillRegistry = new com.cafecli.skill.SkillRegistry(
                    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);
            skillRegistry.reload();
            skillRegistryRef.set(skillRegistry);
            com.cafecli.skill.SkillContextBuffer skillContextBuffer = new com.cafecli.skill.SkillContextBuffer();
            hitlToolRegistry.setSkillRegistry(skillRegistry);
            hitlToolRegistry.setSkillContextBuffer(skillContextBuffer);

            Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
            // 项目 Hook 信任确认走主线程（读终端只在主线程，setup 期 LineReader 尚未接管输入循环）。
            reactAgent.installConfiguredHooks((projectDir, commands) -> {
                renderer.stream().println("⚠️ 检测到未信任的项目级 hooks.json（" + projectDir + "）。");
                renderer.stream().println("   以下命令将随工具生命周期在你的机器上运行：");
                commands.forEach(c -> renderer.stream().println("     • " + c));
                String answer = lineReader.readLine("   信任并运行本项目的 hooks？[y/N] ");
                return answer != null && answer.trim().toLowerCase().startsWith("y");
            });
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            reactAgent.setSkillRegistry(skillRegistry);
            reactAgent.setSkillContextBuffer(skillContextBuffer);
            // 让主 Agent 能前台派生子任务（spawn_agent）：子 Agent 走独立历史 / 预算，工具表经
            // hitlToolRegistry.createChildRegistry() 隔离派生（共享 MCP/LSP/网络/审计/快照等昂贵服务，
            // 会话相邻状态每任务私有，ADR 0007）；子 registry 不带 spawner，故子任务跑完即止不再自我派生。
            com.cafecli.agent.task.TaskRegistry taskRegistry = new com.cafecli.agent.task.TaskRegistry();
            com.cafecli.agent.ForegroundAgentSpawner agentSpawner =
                    new com.cafecli.agent.ForegroundAgentSpawner(llmClient, taskRegistry, hitlToolRegistry);
            hitlToolRegistry.setAgentSpawner(agentSpawner);
            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
            StartupScreenInfo startupScreenInfo = startupScreenInfo(llmClient, mcpServerManager, skillRegistry, startupNote);
            if (renderer instanceof InlineRenderer inline) {
                inline.installStartupScreen(startupScreenLines(startupScreenInfo));
            } else {
                printStartupScreen(ui, startupScreenInfo);
            }
            // Lanterna 全屏 TUI 已随 ADR 0015 移除：交互路径只剩 inline（可用 CAFECLI_RENDERER=plain 降级）。
            reactAgent.setRenderer(renderer);
            reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled);
            reactAgent.getToolRegistry().setWriteFileObserver(
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));
            // 运行中的命令逐行喂给[[活区尾]]（ADR 0017 D5）。observer 跑在 cafecli-command-output 线程上，
            // 这里只投一个渲染事件——渲染线程仍是活区唯一的写者。
            reactAgent.getToolRegistry().setCommandOutputObserver(renderer::appendCommandOutput);

            bindCtrlVToClipboardImage(lineReader);

            // === 事件循环（ADR 0005）+ HITL 泵（#11）+ 取消令牌（#09）===
            // 主线程退化为输入泵；单串行执行器跑所有 ReAct 主回合（前台 + 后台 push），是主对话唯一写入者。
            // 审批走泵：worker park 在 future，主线程在「前台回合泵等待」或「下次回车」时渲染 + resolve（读终端只在主线程）。
            final SnapshotService mainSnapshotService = reactAgent.getToolRegistry().getSnapshotService();
            final CancellationRegistry cancellationRegistry = new CancellationRegistry();
            // 会话级 SIGINT 兜底（ADR 0016 D3）：raw mode 不清 ISIG，而 JLine 只在 readLine 期间装
            // 自己的 INT handler，所以每个读取间隙（等待泵、[[转写浏览层]]、斜杠 palette、审批读键）
            // 里的 Ctrl+C 都会落到 JVM 默认处理器上、连会话一起毁掉。装在装配期：有回合就停回合，
            // 没有可停的就什么都不做。它必须在这里而不是更早——注册表就是它的取消通路。
            AgentSession agentSession = new AgentSession(reactAgent, taskRegistry,
                    output -> { /* 回合输出由 reactAgent 的 renderer 在回合内流式呈现 */ });
            agentSession.setTurnRunner(turnInput -> {
                CancellationToken turnToken = cancellationRegistry.beginForegroundTurn();
                reactAgent.setCancellationToken(turnToken);
                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "react"));
                try {
                    return mainSnapshotService.runTurn("react", turnInput, () -> reactAgent.run(turnInput));
                } catch (Exception e) {
                    return "❌ 回合执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                } finally {
                    renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                    cancellationRegistry.endForegroundTurn(turnToken);
                    reactAgent.setCancellationToken(null);
                }
            });
            // [[取消键]]那条一句话规则的唯一实现（issue 08）：ESC widget、readLine 抛出的
            // UserInterruptException、以及模态自己认下的那个中断，三个入口共用它，所以取消范围
            // 不会分头漂移。它同时是会话级 SIGINT 兜底（ADR 0016 D3）——raw mode 不清 ISIG，而
            // JLine 只在 readLine 期间装自己的 INT handler，读取间隙里的 Ctrl+C 曾直接落到 JVM
            // 默认处理器上、连会话一起毁掉。
            SessionInterruptGuard interruptGuard = new SessionInterruptGuard(
                    cancellationRegistry, renderer, approvalPump, agentSession);
            interruptGuard.installOn(terminal);
            if (renderer instanceof InlineRenderer inline) {
                // 常驻读取器把会话级兜底**处处**盖住（JLine 的 INT handler 在整个 readLine 期间在上面，
                // 且中断落在读线程上、以 InterruptedIOException 的形式浮出来）。所以自持读取的模态
                // ——[[转写浏览层]]——要自己认下这个中断，走的仍是同一条取消通路（ADR 0016 D3）。
                inline.bindSessionInterrupt(interruptGuard::interrupt);
            }
            agentSession.setAutonomousTurnStarter(renderer::beginTurn);
            agentSpawner.setBackgroundCompletionSink(agentSession::onBackgroundTaskDone);
            agentSpawner.setCancellationRegistry(cancellationRegistry);
            agentSession.start();
            // Ctrl+O / Ctrl+Y 是常驻读取器上的两个普通 widget，因此**空闲与回合运行中是同一条通路**
            // （ADR 0016 D2）：被删掉的等待泵曾是它们在回合期间的唯一入口。
            if (renderer instanceof InlineRenderer inline) {
                bindCtrlOToTranscript(lineReader, inline);
            }
            bindCtrlYToHitlToggle(lineReader, () -> {
                toggleHitl(hitlHandler, approvalPump, !hitlHandler.isEnabled());
                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry,
                        agentSession.isIdle() ? "idle" : "react"));
            });

            // 交互 REPL（issue 06）：读输入的循环、键绑定、提交路由、回合期等待都在 InteractiveRepl 里，
            // Main 退化为装配。斜杠命令体在 SlashCommandExecutor，故 REPL 的路由不必认识 Agent / MCP / Skill。
            SlashCommandExecutor slashCommands = new SlashCommandExecutor(
                    renderer, lineReader, config, llmClientRef, reactAgent, hitlHandler, approvalPump,
                    hitlToolRegistry, mcpServerManager, skillRegistry, skillStateStore,
                    browserSession, browserConnectivityCheck, taskRegistry, cancellationRegistry,
                    agentSpawner,
                    () -> renderer.updateStatus(
                            statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle")));
            InteractiveRepl repl = new InteractiveRepl(
                    terminal, lineReader, renderer, agentSession, approvalPump, interruptGuard,
                    slashCommands,
                    // 普通提交的准备：先展开 MCP resource 与本地 @path，再过 UserPromptSubmit hook。
                    // 展开发生在可见的源提示被回写之后（REPL 用的是原文），斜杠命令不触发本 hook。
                    rawInput -> {
                        String expanded = localPathMentionExpander.expand(mentionExpander.expand(rawInput));
                        UserPromptSubmitGate.Decision decision = reactAgent.onUserPromptSubmit(expanded);
                        return decision.blocked()
                                ? InteractiveRepl.PromptPreparation.Result.blocked(decision.reason())
                                : InteractiveRepl.PromptPreparation.Result.accepted(decision.effectivePrompt());
                    });
            // 入队通知（ADR 0010 修订 A14 的顺序不变：先通知、后入队）现在做三件事——告知用户、
            // 让[[转写浏览层]]把屏幕交回来、把审批卡交给渲染线程画并把右提示符切到 y / n / a。
            approvalPump.setPendingNotifier(request -> {
                notifyApprovalPending(renderer, request);
                repl.onApprovalPending(request);
            });
            repl.run();
            agentSession.stop();

        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean isRuntimeServeCommand(String[] args) {
        return args != null
                && args.length >= 1
                && "serve".equalsIgnoreCase(args[0])
                && java.util.Arrays.stream(args).anyMatch("--http"::equalsIgnoreCase);
    }

    private static void startRuntimeApiAndBlock(String[] args) {
        CafeCliConfig config = CafeCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.exit(1);
        }
        int port = parseServePort(args, 8080);
        try {
            RuntimeThreadStore store = new RuntimeThreadStore(RuntimeThreadStore.defaultDbPath());
            RuntimeApiServer server = new RuntimeApiServer(
                    store,
                    prompt -> runHeadlessTask(prompt, client),
                    port,
                    RuntimeApiServer.configuredApiKey());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                store.close();
            }, "cafecli-runtime-api-shutdown"));
            server.start();
            System.out.println("✅ Cafecli Runtime API 已启动: http://127.0.0.1:" + server.port());
            System.out.println("   认证: Authorization: Bearer <CAFECLI_RUNTIME_API_KEY>");
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("❌ Runtime API 启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int parseServePort(String[] args, int defaultPort) {
        if (args == null) {
            return defaultPort;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equalsIgnoreCase(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultPort;
                }
            }
        }
        return defaultPort;
    }

    private static String runHeadlessTask(String prompt, LlmClient llmClient) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        Agent agent = new Agent(llmClient, registry);
        agent.installConfiguredHooks();
        try {
            return agent.run(prompt);
        } finally {
            registry.clearResultSession();
        }
    }

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
        CafeCliConfig config = CafeCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("❌ 未找到可用的 API Key");
            System.exit(1);
            return;
        }
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        Agent agent = new Agent(client, registry);
        agent.installConfiguredHooks();
        try {
            String answer = HeadlessRunner.run(cmd, agent, client);
            if (answer != null && !answer.isBlank()) {
                System.out.println(answer);
            }
        } catch (IOException e) {
            System.err.println("❌ 运行失败: " + e.getMessage());
            System.exit(1);
        } finally {
            registry.clearResultSession();
        }
    }


    /**
     * `/plan` 墓碑（ADR 0015）：Plan-and-Execute 已完整删除，但 README 长期宣传过该入口，
     * 因此保留一句解释而不是「未知命令」。
     */
    static String planTombstoneMessage() {
        return """
                📋 Plan-and-Execute（`/plan`）已移除 —— 见 ADR 0015（docs/adr/0015-retire-plan-and-execute-and-lanterna-tui.md）。
                   它与已退役的 `/team` 是同一物种：模型之外的确定性编排脚手架。
                   直接把任务描述给默认的 ReAct 即可；需要拆分并行推进时，主 Agent 会自己用 spawn_agent 派生子任务。
                """;
    }

    static void toggleHitl(SwitchableHitlHandler hitlHandler, ApprovalPump approvalPump,
                           boolean enabled) {
        hitlHandler.setEnabled(enabled);
        approvalPump.setEnabled(enabled);
        if (!enabled) {
            hitlHandler.clearApprovedAll();
        }
    }

    /**
     * What happens the moment an [[审批应答]] joins the queue: the user is told, and any renderer
     * whose [[转写浏览层]] is holding the input thread is asked to hand the screen back (ADR 0010
     * amendment A14).
     *
     * <p>The announcement is unchanged — a background approval keeps its notice and its
     * Enter-to-drain drain. The overlay close is added <b>beside</b> it, because under a fullscreen
     * overlay the notice is written to a screen nobody can see, and the [[输入框]] that answers the
     * approval is on the primary screen the overlay is covering. Letting it wait instead is worse
     * than it looks: the requesting worker parks on a get with no timeout.
     */
    static void notifyApprovalPending(Renderer renderer, ApprovalRequest request) {
        String who = request.callerContext() == null || request.callerContext().isBlank()
                ? "后台任务" : request.callerContext();
        renderer.stream().println(AnsiStyle.subtle(
                "⏸ " + who + " 需要审批 " + request.toolName() + "（回车处理）"));
        if (renderer instanceof InlineRenderer inline) {
            inline.onApprovalPending();
        }
    }

    static void printSubmittedPrompt(PrintStream out, String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        out.print(SubmittedPromptBlock.render(visible, terminalColumns(), null));
    }

    static void printSubmittedInput(Renderer renderer, PrintStream out, String input) {
        String visible = redactSensitiveInput(input);
        if (renderer instanceof InlineRenderer inline) {
            inline.printSubmittedPrompt(visible);
        } else {
            printSubmittedPrompt(out, visible);
        }
    }

    /**
     * Echo a [[转向消息]] — a submission that went into a turn that was already running (issue 10).
     *
     * <p>Same block, same redaction, same source form as {@link #printSubmittedInput}; what differs is
     * on the renderer's side, where the echo must not disturb the [[活区]]'s still-streaming entries.
     */
    static void printSteeringInput(Renderer renderer, PrintStream out, String input) {
        String visible = redactSensitiveInput(input);
        if (renderer instanceof InlineRenderer inline) {
            inline.printSteeringPrompt(visible);
        } else {
            printSubmittedPrompt(out, visible);
        }
    }

    static String redactSensitiveInput(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String redacted = SENSITIVE_FLAG_VALUE.matcher(input).replaceAll("$1***");
        return SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1***");
    }

    private static int terminalColumns() {
        String configured = System.getProperty("paicli.render.columns");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(columns.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 120;
    }

    static void refreshTerminalColumns(Terminal terminal) {
        if (terminal == null || terminal.getSize() == null || terminal.getSize().getColumns() <= 0) {
            return;
        }
        System.setProperty("paicli.render.columns", String.valueOf(Math.max(40, terminal.getSize().getColumns())));
    }

    static void configureAwtForCli() {
        if (!isMacOs()) {
            return;
        }
        System.setProperty("java.awt.headless", "true");
    }

    static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    static String prepareSeedBuffer(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return "";
        }
        return normalizeLineEndings(rawInput);
    }

    static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/' 后按 Tab 补全命令",
                "输入 '@server:protocol://path' 可显式引用 MCP resource",
                "任务运行中按 ESC 或 Ctrl+C 取消当前任务",
                "默认模式是 ReAct"
        );
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    /** 渲染本会话已加载的生命周期 Hook 清单（供 {@code /hooks}）。经 renderer stream 输出。 */
    static String formatHookListing(Agent agent) {
        List<com.cafecli.hook.HookDispatcher.HookListing> listing = agent.hookListing();
        if (listing.isEmpty()) {
            return "未加载任何生命周期 Hook（内建租户需 -D 开关，shell 租户配 hooks.json）。\n";
        }
        StringBuilder sb = new StringBuilder("生命周期 Hook（本会话）:\n");
        for (com.cafecli.hook.HookDispatcher.HookListing h : listing) {
            String enabled = hookEventEnabled(h.event()) ? "on" : "off";
            sb.append(String.format("  %-16s %-22s [%-7s] %-3s%s  fires=%d blocks=%d%n",
                    h.event(), h.name(), h.source(), enabled,
                    h.matcher() != null ? " matcher=" + h.matcher() : "",
                    h.fires(), h.blocks()));
        }
        return sb.toString();
    }

    private static boolean hookEventEnabled(com.cafecli.hook.HookEvent event) {
        return switch (event) {
            case STOP -> com.cafecli.hook.HookProperties.flag(com.cafecli.hook.HookProperties.STOP_ENABLED);
            case PRE_TOOL_USE -> com.cafecli.hook.HookProperties.flag(com.cafecli.hook.HookProperties.PRE_TOOL_USE_ENABLED);
            case POST_TOOL_USE -> com.cafecli.hook.HookProperties.flag(com.cafecli.hook.HookProperties.POST_TOOL_USE_ENABLED);
            case USER_PROMPT_SUBMIT ->
                    com.cafecli.hook.HookProperties.flag(com.cafecli.hook.HookProperties.USER_PROMPT_SUBMIT_ENABLED);
        };
    }

    static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model glm-5.1", "/model glm-5.1", "切换到 GLM-5.1"),
                new SlashCommandHint("/model glm-5v-turbo", "/model glm-5v-turbo", "切换到 GLM-5V-Turbo 多模态"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek（读取配置模型）"),
                new SlashCommandHint("/model step", "/model step", "切换到 StepFun（读取配置模型）"),
                new SlashCommandHint("/model kimi", "/model kimi", "切换到 Kimi（读取配置模型）"),
                new SlashCommandHint("/model freellmapi", "/model freellmapi", "切换到本地 FreeLLMAPI（读取配置模型）"),
                new SlashCommandHint("/config provider freellmapi ", "/config provider freellmapi <选项>", "配置本地 FreeLLMAPI provider"),
                new SlashCommandHint("/hitl", "/hitl", "查看 HITL 状态"),
                new SlashCommandHint("/hitl on", "/hitl on", "启用危险操作人工审批"),
                new SlashCommandHint("/hitl off", "/hitl off", "关闭 HITL 审批"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/task", "/task", "查看会话内任务及状态"),
                new SlashCommandHint("/task cancel ", "/task cancel <task_id>", "取消运行中的任务"),
                new SlashCommandHint("/transcript", "/transcript", "浏览最近 5 个回合并切换折叠块"),
                new SlashCommandHint("/hooks", "/hooks", "查看已加载的生命周期 Hook（来源/事件/matcher/开关/计数）"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/config", "/config", "打开配置 palette（只读视图 + 切换提示）"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/snapshot", "/snapshot", "查看最近 Side-Git 快照"),
                new SlashCommandHint("/snapshot status", "/snapshot status", "查看 Side-Git 快照状态"),
                new SlashCommandHint("/snapshot clean", "/snapshot clean", "清理当前项目 Side-Git 快照"),
                new SlashCommandHint("/restore ", "/restore <N>", "恢复到最近第 N 个 pre-turn 快照"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码（RAG 辅助）"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/history clear", "/history clear", "清空本机输入历史"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory list", "/memory list", "查看长期记忆列表"),
                new SlashCommandHint("/memory search ", "/memory search <关键词>", "搜索当前项目可见长期记忆"),
                new SlashCommandHint("/memory delete ", "/memory delete <id>", "删除单条长期记忆"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/save ", "/save [--global] <事实内容>", "手动保存项目级或全局长期记忆"),
                new SlashCommandHint("/skill", "/skill", "查看 skill 列表"),
                new SlashCommandHint("/skill list", "/skill list", "查看 skill 列表"),
                new SlashCommandHint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new SlashCommandHint("/skill on ", "/skill on <name>", "启用 skill"),
                new SlashCommandHint("/skill off ", "/skill off <name>", "禁用 skill"),
                new SlashCommandHint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new SlashCommandHint("/exit", "/exit", "退出 Cafecli"),
                new SlashCommandHint("/quit", "/quit", "退出 Cafecli")
        );
    }

    private static void printSlashCommandHelp() {
        printSlashCommandHelp(System.out);
    }

    static void printSlashCommandHelp(PrintStream out) {
        out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            out.println("   " + hint.display() + " - " + hint.description());
        }
        out.println();
    }

    static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("cafecli-slash-command-hint", () -> {
            lineReader.getBuffer().write("/");
            return true;
        });
        Reference slashHint = new Reference("cafecli-slash-command-hint");
        bindSlashWidget(lineReader, LineReader.MAIN, slashHint);
        bindSlashWidget(lineReader, LineReader.EMACS, slashHint);
        bindSlashWidget(lineReader, LineReader.VIINS, slashHint);
    }

    static void configureJLineInteractiveWidgets(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        // 历史自动建议（AutosuggestionWidgets）不装：它把上一条历史以暗色接在光标之后，看上去像
        // 输入行的残留，而 Tab 补全真正有用的只有斜杠命令 / @path / @image / MCP resource——那些
        // 由 CafeCliCompleter 提供，与历史无关。
        new AutopairWidgets(lineReader).enable();
        // JLine TailTipWidgets 会通过 Status 预留多行底部区域；如果在首屏前 enable，
        // banner 前会出现大段空白，输入行下方也会长期空出一块。命令说明后续用
        // 不预留布局的方式展示，避免破坏 Claude Code / Qoder 风格的 inline 体验。
    }

    static LinkedHashMap<String, CmdDesc> slashCommandTailTips() {
        LinkedHashMap<String, CmdDesc> tips = new LinkedHashMap<>();
        for (SlashCommandHint hint : slashCommandHints()) {
            tips.computeIfAbsent(hint.insertText(), key ->
                    new CmdDesc().mainDesc(List.of(new AttributedString(hint.description()))));
            tips.computeIfAbsent(hint.display(), key ->
                    new CmdDesc().mainDesc(List.of(new AttributedString(hint.description()))));
        }
        return tips;
    }

    private static void bindSlashWidget(LineReader lineReader, String keyMapName, Reference slashHint) {
        KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
        if (keyMap != null) {
            keyMap.bind(slashHint, "/");
        }
    }

    static String formatSlashCommandChoices(int terminalWidth) {
        List<String> commands = slashCommandHints().stream()
                .map(SlashCommandHint::display)
                .distinct()
                .toList();
        int maxLen = commands.stream().mapToInt(String::length).max().orElse(12);
        int colWidth = Math.min(Math.max(maxLen + 4, 18), Math.max(18, terminalWidth));
        int columns = Math.max(1, Math.min(4, terminalWidth / colWidth));
        int rows = (int) Math.ceil(commands.size() / (double) columns);

        StringBuilder sb = new StringBuilder();
        sb.append("可用命令（Tab 补全，Enter 执行）：\n");
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = col * rows + row;
                if (index >= commands.size()) {
                    continue;
                }
                String command = commands.get(index);
                sb.append(command);
                if (col < columns - 1) {
                    sb.append(" ".repeat(Math.max(2, colWidth - command.length())));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * /config 命令处理：用 renderer.openPalette 展示当前配置项列表。
     * 当前是只读视图——选中一项后提示对应的 CLI 命令，由用户自己执行。
     */
    static void handleConfigPalette(Renderer renderer,
                                            CafeCliConfig config,
                                            LlmClient llmClient,
                                            SwitchableHitlHandler hitlHandler,
                                            com.cafecli.skill.SkillRegistry skillRegistry) {
        var items = java.util.List.of(
                "模型: " + (llmClient == null ? "(none)" : llmClient.getModelName() + " / " + llmClient.getProviderName()),
                "默认 Provider: " + (config == null ? "(none)" : config.getDefaultProvider()),
                "HITL: " + (hitlHandler.isEnabled() ? "ON" : "OFF"),
                "Skill 启用数: " + (skillRegistry == null ? 0 : skillRegistry.enabledSkills().size()),
                "渲染器: " + renderer.getClass().getSimpleName(),
                "配置文件: ~/.cafecli/config.json (只读视图，编辑请用编辑器)"
        );
        int selected = renderer.openPalette("配置 / config", items);
        if (selected < 0) {
            renderer.stream().println("(已关闭)");
            return;
        }
        String hint = switch (selected) {
            case 0, 1 -> "💡 GLM: /model glm-5.1 / /model glm-5v-turbo；其它: /model deepseek|step|kimi|freellmapi 读取配置模型";
            case 2 -> "💡 切换 HITL: /hitl on / /hitl off";
            case 3 -> "💡 管理 Skill: /skill list / /skill on <name> / /skill off <name>";
            case 4 -> "💡 切换渲染器（重启后生效）: CAFECLI_RENDERER=inline|plain";
            case 5 -> "💡 编辑 config.json 请在编辑器里改完重启";
            default -> "(unknown)";
        };
        renderer.stream().println(hint);
    }

    static String handleConfigCommand(CafeCliConfig config, String payload) {
        ProviderConfigUpdate update = parseProviderConfigUpdate(payload);
        if (update.error() != null) {
            return "❌ " + update.error() + "\n" + providerConfigUsage();
        }

        CafeCliConfig.ProviderConfig providerConfig = ensureProviderConfig(config, update.provider());
        if (update.apiKey() != null) {
            providerConfig.setApiKey(update.apiKey());
        }
        if (update.baseUrl() != null) {
            providerConfig.setBaseUrl(update.baseUrl());
        }
        if (update.model() != null) {
            providerConfig.setModel(update.model());
        }
        if (update.setDefault()) {
            config.setDefaultProvider(update.provider());
        }
        config.save();

        StringBuilder out = new StringBuilder();
        out.append("✅ 已保存 provider 配置: ").append(update.provider()).append('\n');
        out.append("   model: ").append(providerConfig.getModel() == null || providerConfig.getModel().isBlank()
                ? "(默认)" : providerConfig.getModel()).append('\n');
        out.append("   baseUrl: ").append(providerConfig.getBaseUrl() == null || providerConfig.getBaseUrl().isBlank()
                ? "(默认)" : providerConfig.getBaseUrl()).append('\n');
        out.append("   apiKey: ").append(maskSecret(providerConfig.getApiKey())).append('\n');
        if (update.setDefault()) {
            out.append("   默认 provider 已设为 ").append(update.provider()).append('\n');
        }
        out.append("   立即切换: /model ").append(update.provider());
        return out.toString();
    }

    static ProviderConfigUpdate parseProviderConfigUpdate(String payload) {
        List<String> args = splitArgs(payload);
        if (args.size() < 2 || !"provider".equalsIgnoreCase(args.get(0))) {
            return ProviderConfigUpdate.error("用法不正确");
        }

        String provider = normalizeProviderName(args.get(1));
        if (!isSupportedProvider(provider)) {
            return ProviderConfigUpdate.error("暂不支持 provider: " + args.get(1));
        }

        String apiKey = null;
        String baseUrl = null;
        String model = null;
        boolean setDefault = false;
        for (int i = 2; i < args.size(); i++) {
            String token = args.get(i);
            if ("--default".equalsIgnoreCase(token) || "--set-default".equalsIgnoreCase(token)) {
                setDefault = true;
                continue;
            }

            String key;
            String value;
            int equals = token.indexOf('=');
            if (equals > 0) {
                key = token.substring(0, equals);
                value = token.substring(equals + 1);
            } else {
                key = token;
                if (i + 1 >= args.size()) {
                    return ProviderConfigUpdate.error("缺少 " + key + " 的值");
                }
                value = args.get(++i);
            }

            switch (normalizeConfigKey(key)) {
                case "api-key" -> apiKey = value;
                case "base-url" -> baseUrl = value;
                case "model" -> model = value;
                default -> {
                    return ProviderConfigUpdate.error("未知配置项: " + key);
                }
            }
        }

        if (apiKey == null && baseUrl == null && model == null && !setDefault) {
            return ProviderConfigUpdate.error("至少提供一个配置项");
        }
        return new ProviderConfigUpdate(provider, apiKey, baseUrl, model, setDefault, null);
    }

    private static String providerConfigUsage() {
        return """
                用法:
                  /config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
                  /config provider freellmapi --model qwen/qwen3-coder:free --default
                  /model freellmapi
                """.stripTrailing();
    }

    private static List<String> splitArgs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    private static String normalizeConfigKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        while (key.startsWith("-")) {
            key = key.substring(1);
        }
        return switch (key) {
            case "apikey", "api_key", "key" -> "api-key";
            case "baseurl", "base_url", "url" -> "base-url";
            default -> key;
        };
    }

    private static String normalizeProviderName(String raw) {
        String provider = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            default -> provider;
        };
    }

    private static boolean isSupportedProvider(String provider) {
        return List.of("glm", "deepseek", "step", "kimi", "freellmapi").contains(provider);
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "(未配置)";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    static void bindCtrlOToTranscript(LineReader lineReader, InlineRenderer inline) {
        if (lineReader == null || inline == null) {
            return;
        }
        lineReader.getWidgets().put("cafecli-transcript-overlay", () -> {
            openTranscriptOverlay(inline);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("cafecli-transcript-overlay");
        String ctrlO = String.valueOf((char) CTRL_O);
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlO);
            }
        }
    }

    static void bindCtrlYToHitlToggle(LineReader lineReader, Runnable hitlToggle) {
        if (lineReader == null || hitlToggle == null) {
            return;
        }
        lineReader.getWidgets().put("cafecli-toggle-hitl", () -> {
            hitlToggle.run();
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("cafecli-toggle-hitl");
        String ctrlY = String.valueOf((char) CTRL_Y);
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlY);
            }
        }
    }

    static boolean handleTranscriptCommand(CliCommandParser.ParsedCommand command,
                                           Renderer renderer) {
        if (command == null || command.type() != CliCommandParser.CommandType.TRANSCRIPT) {
            return false;
        }
        openTranscriptOverlay(renderer);
        return true;
    }

    /**
     * The single entry to the [[转写浏览层]] — {@code /transcript}, the idle Ctrl+O widget, and the
     * mid-turn Ctrl+O are the same door (ADR 0016 D2).
     *
     * <p>Mid-turn used to arrive through the raw-mode turn pump's display-control branch. With the
     * line editor reading continuously (issue 07) there is no pump and no second branch: Ctrl+O is one
     * ordinary widget on the resident reader, live whether or not a turn is running.
     */
    static void openTranscriptOverlay(Renderer renderer) {
        if (renderer instanceof InlineRenderer inline) {
            inline.openTranscript();
        } else {
            renderer.stream().println("Transcript overlay is available only in inline mode.");
        }
    }

    // Ctrl+V 抓系统剪贴板里的图片到 ~/.cafecli/cache/ 并把 @image:<path> 注入当前输入行。
    // 失败（无图 / headless / IO 错误）时只打提示，不破坏现有 buffer，覆盖掉 JLine 默认的
    // quoted-insert 没有交互价值。注意 macOS Cmd+V 通常被终端劫持成本地粘贴文本，所以这里
    // 绑的是 Ctrl+V（ASCII 22 / SYN），iTerm / Terminal.app 默认不会拦截。
    //
    // 输入层不按模型名拦截图片：与 Claude Code 类似，先把图片读成附件收进
    // prompt；模型是否接受 image block 由 provider API 自己处理。
    static void bindCtrlVToClipboardImage(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("cafecli-paste-clipboard-image", () -> {
            ClipboardImage.GrabResult grab = ClipboardImage.grab();
            if (!grab.ok()) {
                lineReader.printAbove("⚠️ Ctrl+V 抓图失败: " + grab.error());
                lineReader.callWidget(LineReader.REDISPLAY);
                return true;
            }
            String token = "@image:<" + grab.path().toAbsolutePath() + "> ";
            lineReader.getBuffer().write(token);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("cafecli-paste-clipboard-image");
        String ctrlV = String.valueOf((char) 22);  // Ctrl+V (SYN)
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlV);
            }
        }
    }

    static void printPolicyStatus(PrintStream out, Agent reactAgent) {
        out.println("🛡️ 安全策略状态：");
        out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
        out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        out.println("   路径围栏: 强制限定在项目根之内（read_file / write_file / list_dir / create_project）");
        out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        out.println("   写入文件上限: 5MB");
        out.println("   命令执行上限: 60 秒，输出 8KB（截断）");
        out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
        out.println();
    }

    static String handleBrowserCommand(String payload,
                                       BrowserSession browserSession,
                                       BrowserConnectivityCheck connectivityCheck,
                                       McpServerManager mcpServerManager,
                                       HitlToolRegistry registry,
                                       HitlHandler hitlHandler) {
        String normalized = payload == null || payload.isBlank() ? "status" : payload.trim();
        String[] parts = normalized.split("\\s+");
        String subCommand = parts[0].toLowerCase();
        return switch (subCommand) {
            case "status" -> browserStatus(browserSession, connectivityCheck, mcpServerManager);
            case "connect" -> {
                if (parts.length >= 2) {
                    int port = parseBrowserPort(parts[1]);
                    yield browserConnectByPort(port, browserSession, connectivityCheck, mcpServerManager, hitlHandler);
                }
                yield browserAutoConnect(browserSession, mcpServerManager, hitlHandler);
            }
            case "disconnect" -> browserDisconnect(browserSession, mcpServerManager, hitlHandler);
            case "tabs" -> browserTabs(browserSession, registry);
            default -> """
                    ❌ 未知 /browser 子命令: %s
                    可用命令：
                      /browser status
                      /browser connect [port]
                      /browser disconnect
                      /browser tabs
                    """.formatted(normalized).trim();
        };
    }

    private static String browserStatus(BrowserSession browserSession,
                                        BrowserConnectivityCheck connectivityCheck,
                                        McpServerManager mcpServerManager) {
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(9222);
        McpServer server = mcpServerManager.server("chrome-devtools");
        String serverStatus = server == null
                ? "未配置"
                : server.status() == McpServerStatus.READY
                ? "● ready (" + server.tools().size() + " tools)"
                : server.status().name().toLowerCase() + (server.errorMessage() == null ? "" : " - " + server.errorMessage());
        String mode = browserSession.mode() == BrowserMode.SHARED
                ? "shared（复用 " + browserSession.browserUrl() + "）"
                : "isolated（临时 user-data-dir，无登录态）";
        return """
                🌐 浏览器会话
                  当前模式: %s
                  chrome-devtools server: %s
                  旧式 /json/version 探活: %s
                  自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
                """.formatted(mode, serverStatus, probe.ok() ? "✅ " + probe.browserUrl() : "⚠️ " + probe.message()).trim();
    }

    private static String browserAutoConnect(BrowserSession browserSession,
                                             McpServerManager mcpServerManager,
                                             HitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.cafecli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> autoConnectArgs = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");
        String result = mcpServerManager.restartWithArgs("chrome-devtools", autoConnectArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared("autoConnect");
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect/#remote-debugging 允许远程调试）\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ autoConnect 连接失败，已回滚 chrome-devtools 启动参数：\n" + result
                + "\n\n请确认 Chrome 144+ 已打开 chrome://inspect/#remote-debugging，并勾选 Allow remote debugging for this browser instance。";
    }

    private static String browserConnectByPort(int port,
                                               BrowserSession browserSession,
                                               BrowserConnectivityCheck connectivityCheck,
                                               McpServerManager mcpServerManager,
                                               HitlHandler hitlHandler) {
        if (port < 1024 || port > 65535) {
            return "❌ /browser connect 端口必须在 1024-65535 之间。默认 /browser connect 使用 --autoConnect；旧式 CDP 端口连接可用 /browser connect 9222。";
        }
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(port);
        if (!probe.ok()) {
            return "❌ 未检测到 Chrome 调试端口 127.0.0.1:" + port + "：" + probe.message() + "\n\n"
                    + chromeLaunchHelp(port);
        }

        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.cafecli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> sharedArgs = List.of("-y", "chrome-devtools-mcp@latest", "--browser-url=" + probe.browserUrl());
        String result = mcpServerManager.restartWithArgs("chrome-devtools", sharedArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared(probe.browserUrl());
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 切换 chrome-devtools server 到 shared 模式 (" + probe.browserUrl() + ")\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ shared 模式切换失败，已回滚 chrome-devtools 启动参数：\n" + result;
    }

    private static String browserDisconnect(BrowserSession browserSession,
                                            McpServerManager mcpServerManager,
                                            HitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            browserSession.switchToIsolated();
            return "❌ 未配置 chrome-devtools MCP server，已清理本地浏览器会话状态";
        }
        String result = mcpServerManager.restartWithArgs(
                "chrome-devtools",
                List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
        browserSession.switchToIsolated();
        hitlHandler.clearApprovedAllForServer("chrome-devtools");
        return "🔄 已切回 isolated 浏览器模式\n" + result;
    }

    private static String browserTabs(BrowserSession browserSession, HitlToolRegistry registry) {
        if (browserSession.mode() != BrowserMode.SHARED) {
            return "当前为 isolated 模式，没有真实 Chrome tab 可复用。可用 /browser connect 切到 shared 模式。";
        }
        return registry.executeTool("mcp__chrome-devtools__list_pages", "{}");
    }

    private static int parseBrowserPort(String value) {
        if (value == null || value.isBlank()) {
            return 9222;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String chromeLaunchHelp(int port) {
        return """
                请先用调试端口启动 Chrome：
                  macOS: open -na "Google Chrome" --args --remote-debugging-port=%d --user-data-dir=/tmp/cafecli-chrome-profile
                  Windows: start chrome.exe --remote-debugging-port=%d --user-data-dir=%%TEMP%%\\cafecli-chrome-profile
                  Linux: google-chrome --remote-debugging-port=%d --user-data-dir=/tmp/cafecli-chrome-profile
                然后重新执行 /browser connect %d
                """.formatted(port, port, port, port).trim();
    }

    static void printMcpCommandResult(PrintStream out, String result) {
        out.println(result);
        out.println();
    }

    static void printAuditTail(PrintStream out, Agent reactAgent, String payload) {
        int requested = parseAuditCount(payload, 10);
        List<AuditLog.AuditEntry> entries = reactAgent.getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            out.println("📭 今日尚无审计记录\n");
            return;
        }
        out.println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            out.printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                out.println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                out.println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        out.println();
    }

    static void printSnapshotCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim().toLowerCase();
        if ("status".equals(normalized)) {
            out.println(snapshotService.status());
            out.println();
            return;
        }
        if ("clean".equals(normalized)) {
            out.println(snapshotService.clean());
            out.println();
            return;
        }
        if (!"list".equals(normalized)) {
            out.println("""
                    ❌ 未知 /snapshot 子命令: %s
                    可用命令：
                      /snapshot
                      /snapshot status
                      /snapshot clean
                      /restore <N>
                    """.formatted(payload).trim());
            out.println();
            return;
        }
        try {
            List<TurnSnapshot> snapshots = snapshotService.listSnapshots(20);
            if (snapshots.isEmpty()) {
                out.println("📭 暂无 Side-Git 快照\n");
                return;
            }
            out.println("📸 最近 " + snapshots.size() + " 条 Side-Git 快照：");
            int preTurnIndex = 0;
            for (TurnSnapshot snapshot : snapshots) {
                String restoreHint = "";
                if ("pre-turn".equals(snapshot.phase().label())) {
                    preTurnIndex++;
                    restoreHint = "  /restore " + preTurnIndex;
                }
                out.printf("   %s %-11s %-18s %s%s%n",
                        snapshot.shortCommitId(),
                        snapshot.phase().label(),
                        snapshot.turnId(),
                        snapshot.createdAt(),
                        restoreHint);
            }
            out.println();
        } catch (Exception e) {
            out.println("❌ 读取快照失败: " + e.getMessage() + "\n");
        }
    }

    static void printRestoreCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        int offset = parseAuditCount(payload, 1);
        try {
            RestoreResult result = snapshotService.restorePreTurn(offset);
            out.println(result.formatForCli());
            out.println();
        } catch (Exception e) {
            out.println("❌ 恢复快照失败: " + e.getMessage() + "\n");
        }
    }

    private static int parseAuditCount(String payload, int defaultN) {
        if (payload == null || payload.isBlank()) return defaultN;
        try {
            int n = Integer.parseInt(payload.trim());
            return Math.max(1, Math.min(n, 100));
        } catch (NumberFormatException e) {
            return defaultN;
        }
    }

    private static void printStartupHints(PrintStream out) {
        out.println("💡 提示:");
        for (String hint : startupHints()) {
            out.println("   - " + hint);
        }
        out.println();
    }

    private static StartupScreenInfo startupScreenInfo(LlmClient llmClient,
                                                       McpServerManager mcpServerManager,
                                                       SkillRegistry skillRegistry,
                                                       String note) {
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        int total = mcpServerManager.servers().size();
        int tools = mcpServerManager.servers().stream()
                .mapToInt(server -> server.tools().size())
                .sum();
        int skillTotal = skillRegistry.allSkills().size();
        int skillEnabled = skillRegistry.enabledSkills().size();
        return new StartupScreenInfo(
                llmClient.getModelName(),
                llmClient.getProviderName(),
                ready,
                total,
                tools,
                skillEnabled,
                skillTotal,
                note == null ? "" : note.trim()
        );
    }

    private static StatusInfo statusInfo(LlmClient llmClient,
                                         SwitchableHitlHandler hitlHandler,
                                         String phase,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        StatusInfo base = "idle".equals(normalizedPhase)
                ? StatusInfo.idle(llmClient.getModelName(), llmClient.maxContextWindow(), hitlHandler.isEnabled())
                : StatusInfo.active(llmClient.getModelName(), llmClient.maxContextWindow(),
                hitlHandler.isEnabled(), normalizedPhase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    private static StatusInfo statusInfo(Agent reactAgent,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry,
                                         String phase) {
        StatusInfo base = reactAgent.currentStatus(phase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    private static String mcpStatusSummary(McpServerManager mcpServerManager) {
        if (mcpServerManager == null || mcpServerManager.servers().isEmpty()) {
            return "MCP 0";
        }
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        return "MCP " + ready + "/" + mcpServerManager.servers().size();
    }

    private static String skillStatusSummary(SkillRegistry skillRegistry) {
        if (skillRegistry == null || skillRegistry.allSkills().isEmpty()) {
            return "Skill 0";
        }
        return "Skill " + skillRegistry.enabledSkills().size() + "/" + skillRegistry.allSkills().size();
    }

    private static String appendStartupNote(String current, String next) {
        if (next == null || next.isBlank()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "\n" + next;
    }

    static Duration mcpStartupWait() {
        String configured = System.getProperty("paicli.mcp.startup.wait.seconds");
        if (configured == null || configured.isBlank()) {
            configured = com.cafecli.config.ConfigHome.envLegacy("PAICLI_MCP_STARTUP_WAIT_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return Duration.ofSeconds(8);
        }
        try {
            long seconds = Long.parseLong(configured.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(8);
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(8);
        }
    }

    static String normalizeLineEndings(String rawInput) {
        return rawInput
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        if (lineReader == null || sequence == null || sequence.isEmpty()) {
            return "";
        }

        if (isUpArrowSequence(sequence)) {
            return latestHistoryEntry(lineReader.getHistory());
        }

        if (isDownArrowSequence(sequence)) {
            return "";
        }

        return "";
    }

    private static boolean isUpArrowSequence(String sequence) {
        return ARROW_UP.equals(sequence) || APP_ARROW_UP.equals(sequence);
    }

    private static boolean isDownArrowSequence(String sequence) {
        return ARROW_DOWN.equals(sequence) || APP_ARROW_DOWN.equals(sequence);
    }

    private static String latestHistoryEntry(History history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int lastIndex = history.last();
        if (lastIndex < 0) {
            return "";
        }

        String entry = history.get(lastIndex);
        return entry == null ? "" : entry;
    }

    static void configureHistory(LineReader lineReader, Path homeDir) {
        if (lineReader == null) {
            return;
        }
        Path historyFile = resolveHistoryFile(homeDir);
        try {
            Files.createDirectories(historyFile.getParent());
            lineReader.setVariable(LineReader.HISTORY_FILE, historyFile);
            lineReader.setVariable(LineReader.HISTORY_SIZE, historySize());
            lineReader.setVariable(LineReader.HISTORY_FILE_SIZE, historyFileSize());
            lineReader.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
            lineReader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
            lineReader.setOpt(LineReader.Option.HISTORY_REDUCE_BLANKS);
            lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            lineReader.getHistory().load();
        } catch (IOException ignored) {
            // History is a convenience feature; failed persistence must not block the CLI.
        }
    }

    static Path resolveHistoryFile(Path homeDir) {
        String configured = firstNonBlank(System.getProperty(HISTORY_FILE_PROPERTY), com.cafecli.config.ConfigHome.envLegacy("PAICLI_HISTORY_FILE"));
        if (configured != null) {
            return normalizeHistoryFile(Path.of(configured));
        }
        Path base = homeDir == null ? Path.of(System.getProperty("user.home")) : homeDir;
        return com.cafecli.config.ConfigHome.configDirUnder(base).resolve("history").resolve(DEFAULT_HISTORY_FILE_NAME)
                .toAbsolutePath().normalize();
    }

    static Path normalizeHistoryFile(Path configured) {
        Path path = configured.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return path.resolve(DEFAULT_HISTORY_FILE_NAME).toAbsolutePath().normalize();
        }
        return path;
    }

    static void clearLineReaderHistory(LineReader lineReader) {
        if (lineReader == null || lineReader.getHistory() == null) {
            return;
        }
        try {
            lineReader.getHistory().purge();
        } catch (IOException ignored) {
            // Keep command behavior simple: in-memory history may still be reset by JLine.
        }
    }

    private static int historySize() {
        return configuredPositiveInt(HISTORY_SIZE_PROPERTY, "PAICLI_HISTORY_SIZE", 2_000);
    }

    private static int historyFileSize() {
        return configuredPositiveInt(HISTORY_FILE_SIZE_PROPERTY, "PAICLI_HISTORY_FILE_SIZE", 10_000);
    }

    private static int configuredPositiveInt(String property, String env, int fallback) {
        String raw = firstNonBlank(System.getProperty(property), com.cafecli.config.ConfigHome.envLegacy(env));
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
        return loadConfigValue("GLM_API_KEY", null);
    }

    private static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "PAICLI_LOG_DIR",
                com.cafecli.config.ConfigHome.userConfigDir().resolve("logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "PAICLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "PAICLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "PAICLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "PAICLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = com.cafecli.config.ConfigHome.envLegacy(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    private static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    static ModelSelection resolveModelSelection(String raw) {
        String value = raw == null ? "" : raw.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm" -> new ModelSelection("glm", "glm-5.1", true);
            case "deepseek" -> new ModelSelection("deepseek", null, false);
            case "step", "stepfun", "step-fun" -> new ModelSelection("step", null, false);
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> new ModelSelection("kimi", null, false);
            case "freellmapi", "free-llm-api", "free_llm_api", "freellm", "free-llm" ->
                    new ModelSelection("freellmapi", null, false);
            default -> {
                if (normalized.startsWith("glm-")) {
                    yield new ModelSelection("glm", value, true);
                }
                if (normalized.startsWith("deepseek")) {
                    yield new ModelSelection("deepseek", value, true);
                }
                if (normalized.startsWith("step")) {
                    yield new ModelSelection("step", value, true);
                }
                if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) {
                    yield new ModelSelection("kimi", value, true);
                }
                yield new ModelSelection(normalized, null, false);
            }
        };
    }

    static CafeCliConfig.ProviderConfig ensureProviderConfig(CafeCliConfig config, String provider) {
        if (config.getProviders() == null) {
            config.setProviders(new LinkedHashMap<>());
        }
        return config.getProviders().computeIfAbsent(provider, ignored -> new CafeCliConfig.ProviderConfig());
    }

    private static void printStartupScreen(PrintStream out, StartupScreenInfo info) {
        for (String line : startupScreenLines(info)) {
            out.println(line);
        }
    }

    static List<String> startupScreenLines(StartupScreenInfo info) {
        List<String> lines = new ArrayList<>(startupBannerLines(info));
        lines.add("");
        return lines;
    }

    static List<String> startupBannerLines() {
        return startupBannerLines(new StartupScreenInfo(
                "auto",
                "model",
                0,
                0,
                0,
                0,
                0,
                ""));
    }

    static List<String> startupBannerLines(StartupScreenInfo info) {
        String model = info.model() == null || info.model().isBlank() ? "auto" : info.model();
        String provider = info.provider() == null || info.provider().isBlank() ? "model" : info.provider();
        String mcp = info.mcpTotal() <= 0
                ? "MCP not configured"
                : "MCP " + info.mcpReady() + "/" + info.mcpTotal() + " · " + info.mcpTools() + " tools";
        String skills = info.skillsTotal() <= 0
                ? "0 skills"
                : info.skillsEnabled() + "/" + info.skillsTotal() + " skills";
        String ready = "Model " + model + " (" + provider + ")";
        String capabilities = "ReAct · Task · MCP · Browser · Image · Tools · Memory · RAG";
        String state = mcp + " · " + skills + " · ReAct";
        // 咖啡杯配色：蒸汽暗灰，杯体自上而下从奶油白渐变到深咖啡棕。
        List<String> lines = new ArrayList<>(List.of(
                AnsiStyle.steam("     ( (   "),
                AnsiStyle.steam("      ) )  ") + "  " + AnsiStyle.emphasis("Cafecli") + AnsiStyle.subtle(" · v" + VERSION),
                AnsiStyle.cupLayer("   ........", 0) + "  " + AnsiStyle.subtle("freshly brewed agents"),
                AnsiStyle.cupLayer("   |      |]", 1) + "  " + AnsiStyle.subtle(ready),
                AnsiStyle.cupLayer("   \\      /", 2) + "  " + AnsiStyle.subtle(state),
                AnsiStyle.cupLayer("    `----' ", 3) + "  " + AnsiStyle.subtle(capabilities),
                "",
                "Tips for getting started:",
                "1. Type " + AnsiStyle.emphasis("/") + " for commands and Tab completion",
                "2. Ask coding questions, edit code or run commands",
                "3. Attach context with " + AnsiStyle.emphasis("@path") + " or " + AnsiStyle.emphasis("@image:")
        ));
        if (info.note() != null && !info.note().isBlank()) {
            lines.add("");
            lines.add(AnsiStyle.subtle(info.note().replace('\n', ' ')));
        }
        return lines;
    }

    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = com.cafecli.config.ConfigHome.configDirUnder(userHome).resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 " + configFile + " 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new McpConfigBootstrapResult(false, "");
    }

    static MemorySaveRequest parseMemorySave(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.regionMatches(true, 0, "--global ", 0, 9)) {
            return new MemorySaveRequest(value.substring(9).trim(), "global");
        }
        if (value.equalsIgnoreCase("--global")) {
            return new MemorySaveRequest("", "global");
        }
        if (value.regionMatches(true, 0, "--project ", 0, 10)) {
            return new MemorySaveRequest(value.substring(10).trim(), "project");
        }
        if (value.equalsIgnoreCase("--project")) {
            return new MemorySaveRequest("", "project");
        }
        return new MemorySaveRequest(value, "project");
    }

    static String formatMemoryEntries(String title, List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder(title).append("：\n");
        if (entries == null || entries.isEmpty()) {
            return sb.append("📭 没有匹配的长期记忆。").toString();
        }
        for (MemoryEntry entry : entries) {
            String scope = LongTermMemory.scopeOf(entry);
            String project = entry.getMetadata().get("project");
            sb.append("- ")
                    .append(entry.getId())
                    .append(" [").append(scope).append("]");
            if ("project".equals(scope) && project != null && !project.isBlank()) {
                sb.append(" ").append(shortenPath(project));
            }
            sb.append(" · ").append(entry.getTimestamp()).append("\n")
                    .append("  ").append(entry.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String shortenPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(path);
            int count = p.getNameCount();
            if (count <= 3) {
                return path;
            }
            return "..." + File.separator + p.subpath(count - 3, count);
        } catch (Exception e) {
            return path;
        }
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }

    record ModelSelection(String provider, String model, boolean explicitModel) {
    }

    record ProviderConfigUpdate(String provider, String apiKey, String baseUrl, String model,
                                boolean setDefault, String error) {
        static ProviderConfigUpdate error(String error) {
            return new ProviderConfigUpdate(null, null, null, null, false, error);
        }
    }

    record MemorySaveRequest(String fact, String scope) {
    }
}
