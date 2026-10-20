package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.agent.task.AgentSpawner;
import com.cafecli.browser.BrowserAuditMetadata;
import com.cafecli.browser.BrowserCheckResult;
import com.cafecli.browser.BrowserConnector;
import com.cafecli.browser.BrowserGuard;
import com.cafecli.context.ContextProfile;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookDispatcher;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookProperties;
import com.cafecli.lsp.LspDiagnosticReport;
import com.cafecli.lsp.LspManager;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.mcp.McpResultPersister;
import com.cafecli.mcp.McpToolSink;
import com.cafecli.policy.AuditLog;
import com.cafecli.policy.PathGuard;
import com.cafecli.policy.PolicyException;
import com.cafecli.runtime.CancellationContext;
import com.cafecli.snapshot.SnapshotService;
import com.cafecli.skill.SkillContextBuffer;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.web.HtmlExtractor;
import com.cafecli.web.NetworkPolicy;
import com.cafecli.web.SearchProvider;
import com.cafecli.web.SearchProviderFactory;
import com.cafecli.web.WebFetcher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.Locale;

/**
 * 工具注册表：持有全部可用工具（内置 + MCP 动态工具），并提供唯一的执行路径——
 * 单次 {@link #executeToolOutput} / 并行批次 {@link #executeTools}，统一处理取消、审计、Hook 与 MCP 大结果落盘。
 */
public class ToolRegistry implements McpToolSink {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    // 唯一一张工具表：内置工具与 MCP 动态工具同表，按 Registered.isMcp() 区分
    private final Map<String, Registered> tools = new ConcurrentHashMap<>();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    // 下面没有初始化式的字段由构造器赋值：根 registry 取默认值，子任务取自 Inherited。
    // 不在字段初始化里建——否则子任务要先建一份再丢掉（快照服务、LSP 解析器、审计日志……）。
    private String projectPath;
    private PathGuard pathGuard;
    private final AuditLog auditLog;
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;
    private ContextProfile contextProfile = ContextProfile.from(null);
    private BrowserGuard browserGuard;
    private BrowserConnector browserConnector;
    private BiConsumer<String, String> memorySaver;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private AgentSpawner agentSpawner;
    // 构造后不再改，但不能 final：下面 mcpResultBudget 初始化式里的 lambda 读它，空白 final 在那里算「可能未赋值」
    private String resultSessionId;
    private String resultScope;
    private final McpResultBudget mcpResultBudget = new McpResultBudget(
            () -> new McpResultPersister(Path.of(projectPath), resultSessionId, resultScope));
    private final ToolBatchExecutor batchExecutor;
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    private java.util.function.BiConsumer<String, String> commandOutputObserver = (c, l) -> {};
    private LspManager lspManager;
    // 拆出去的工具组读可变状态的入口：只存 supplier，执行时现取（理由见 ToolEnv）。必须先于任何工具注册建好。
    private final ToolEnv env = new ToolEnv(() -> projectPath, () -> pathGuard, () -> commandOutputObserver,
            () -> writeFileObserver, () -> lspManager);
    private SnapshotService snapshotService;
    private boolean customSnapshotService;
    private volatile String currentProvider = "";
    private volatile String currentModel = "";
    // 生命周期 Hook 分发器（ADR 0012）：由 Agent 构造并注入；子任务 registry 经 Inherited 共享父的同一个分发器
    // （ADR 0012「child registries share the parent's dispatcher」）。为 null 表示默认路径，preToolUse/postToolUse
    // 都是零成本 no-op。PreToolUse 在 executeTool*/HITL 覆写点分发（审批前），PostToolUse 在 doExecuteTool 成功路径分发。
    private HookDispatcher hookDispatcher;
    // PostToolUse 的 additionalContext 走与 per-edit LSP 诊断相同的「下一轮前置注入」通道：
    // 工具执行处入队，Agent 在每轮循环顶部（injectPendingLspDiagnostics 处）flush 成 user 消息。每 agent 私有（不随子任务共享）。
    private final List<String> pendingHookContext = Collections.synchronizedList(new ArrayList<>());

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this(commandTimeoutSeconds, toolBatchTimeoutSeconds, null);
    }

    /**
     * 唯一真正干活的构造器：{@code inherited == null} 是根 registry（JVM 工作目录 + 全新协作者），
     * 否则是子任务（接过派生者 fork 那一刻的 {@link Inherited}）。两条路都先把状态定下来再注册内置工具，
     * 不建任何事后要丢掉的对象。
     */
    private ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds, Inherited inherited) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        if (inherited == null) {
            this.projectPath = System.getProperty("user.dir");
            this.pathGuard = new PathGuard(projectPath);
            this.auditLog = new AuditLog();
            this.resultSessionId = newResultSessionId();
            this.resultScope = "main";
            this.lspManager = new LspManager(projectPath);
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
            this.customSnapshotService = false;
        } else {
            this.projectPath = inherited.projectPath();
            this.pathGuard = new PathGuard(inherited.projectPath());
            this.auditLog = inherited.auditLog();
            this.snapshotService = inherited.snapshotService();
            this.customSnapshotService = inherited.customSnapshotService();
            this.searchProvider = inherited.searchProvider();
            this.webFetcher = inherited.webFetcher();
            this.htmlExtractor = inherited.htmlExtractor();
            this.networkPolicy = inherited.networkPolicy();
            this.browserConnector = inherited.browserConnector();
            this.browserGuard = inherited.browserGuard();
            this.memorySaver = inherited.memorySaver();
            this.skillRegistry = inherited.skillRegistry();
            this.resultSessionId = inherited.resultSessionId();
            this.resultScope = inherited.resultScope();
            this.hookDispatcher = inherited.hookDispatcher();
            this.lspManager = inherited.lspManager();
            this.skillContextBuffer = new SkillContextBuffer();
        }
        this.batchExecutor = new ToolBatchExecutor(this::executeToolOutput, mcpResultBudget, toolBatchTimeoutSeconds);
        // 注册内置工具
        registerAll(new FileTools(env).tools());
        registerAll(new CommandTool(env, commandTimeoutSeconds).tools());
        registerAll(new SearchTools(env).tools());
        registerAll(new WebTools(this::searchProvider, this::webFetcher, this::htmlExtractor, this::networkPolicy,
                () -> WebTools.prefersStepSearch(currentProvider, currentModel),
                tools::containsKey,
                name -> {
                    Registered e = tools.get(name);
                    return e == null || !e.isMcp() ? null : e.mcp().inputSchema().path("properties");
                },
                this::executeToolOutput).tools());
        registerAll(new BrowserTools(() -> browserConnector).tools());
        registerAll(new SessionTools(() -> memorySaver, () -> skillRegistry, () -> skillContextBuffer,
                () -> snapshotService).tools());
        registerAll(new TaskTools(() -> agentSpawner).tools());
        if (inherited != null) {
            // MCP 条目放在内置工具之后：保持旧实现的插入顺序（ConcurrentHashMap 同桶内的遍历顺序跟插入顺序走，
            // 影响 getToolDefinitions 给模型的工具顺序）
            for (Registered registered : inherited.mcpTools()) {
                tools.put(registered.name(), registered);
            }
        }
    }

    private void registerAll(List<Tool> group) {
        for (Tool t : group) {
            // readTree 的异常不包装：原样抛给 doExecuteTool，回给模型的是原始异常消息
            tools.put(t.name(), new Registered(t.name(), t.description(), t.parameters(), null,
                    json -> {
                        ToolOutput out = t.executor().execute(mapper.readTree(json));
                        return out == null ? ToolOutput.text("") : out;
                    }));
        }
    }

    /**
     * 子任务 registry 构造（ADR 0007「默认隔离，共享靠显式注入」）：继承什么、不继承什么全写在 {@link Inherited}，
     * 由派生者的 {@link #inheritForChild()} 在 fork 那一刻取值。内置工具每任务一份、绑定本 registry 的 supplier；
     * 只有 MCP 条目按引用接管（同一份外部连接，不 per-task 重连 MCP server）。进程级[[文件互斥锁]]（ADR 0001）
     * 本就是静态、跨 registry 共享，无需搬运。签名保留给 {@code HitlToolRegistry} 的 {@code super(parent)}。
     */
    protected ToolRegistry(ToolRegistry parent) {
        this(parent.commandTimeoutSeconds, parent.toolBatchTimeoutSeconds, parent.inheritForChild());
    }

    /**
     * 子任务从派生者继承的状态（ADR 0007）：fork 那一刻的快照，按引用拷进子 registry。之后父再调 setter
     * 不会传到已派生的子任务——与逐字段拷贝的旧语义一致，刻意不是「活的共享对象」。
     * 不在此列的都是每任务私有、从默认值起步：agentSpawner / writeFileObserver / commandOutputObserver /
     * contextProfile / currentProvider / currentModel / pendingHookContext；skillContextBuffer 拿全新的空缓冲；
     * lspManager 是父的 forkSharingProcess()（解析进程共享、pending 诊断私有）。
     *
     * <p>四个 web 服务照搬父字段的当前值，<b>包括 null</b>：父还没懒建的，子任务各自懒建，不在 fork 时替父建。
     * hookDispatcher 共享同一批租户实例；读前写台账的「每 agent 作用域」靠 payload 里 taskId=resultScope 做键，
     * 不靠分发器隔离。
     */
    private record Inherited(String projectPath, AuditLog auditLog,
                             SnapshotService snapshotService, boolean customSnapshotService,
                             SearchProvider searchProvider, WebFetcher webFetcher,
                             HtmlExtractor htmlExtractor, NetworkPolicy networkPolicy,
                             BrowserConnector browserConnector, BrowserGuard browserGuard,
                             BiConsumer<String, String> memorySaver, SkillRegistry skillRegistry,
                             List<Registered> mcpTools, String resultSessionId, String resultScope,
                             HookDispatcher hookDispatcher, LspManager lspManager) {}

    /** 取本 registry 此刻要传给子任务的状态。读字段而非懒建 getter：web 服务为 null 就传 null。 */
    private Inherited inheritForChild() {
        List<Registered> mcpTools = tools.values().stream().filter(Registered::isMcp).toList();
        LspManager childLsp = lspManager == null ? new LspManager(projectPath) : lspManager.forkSharingProcess();
        return new Inherited(projectPath, auditLog, snapshotService, customSnapshotService,
                searchProvider, webFetcher, htmlExtractor, networkPolicy,
                browserConnector, browserGuard, memorySaver, skillRegistry,
                mcpTools, resultSessionId, resultScope, hookDispatcher, childLsp);
    }

    /**
     * 为一个子任务派生一份隔离的 ToolRegistry：昂贵服务共享、会话状态私有。
     * 见 ADR 0007 与 {@link #ToolRegistry(ToolRegistry)}。
     */
    public ToolRegistry createChildRegistry() {
        return new ToolRegistry(this);
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    public String getSessionId() {
        return resultSessionId;
    }

    public String getResultScope() {
        return resultScope;
    }

    public void setResultScope(String scope) {
        this.resultScope = scope == null || scope.isBlank() ? "main" : scope.trim();
    }

    public void setContextProfile(ContextProfile contextProfile) {
        if (contextProfile != null) {
            this.contextProfile = contextProfile;
        }
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public void setCurrentModel(String provider, String model) {
        this.currentProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.currentModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    public void setBrowserGuard(BrowserGuard browserGuard) {
        this.browserGuard = browserGuard;
    }

    protected BrowserGuard getBrowserGuard() {
        return browserGuard;
    }

    public void setBrowserConnector(BrowserConnector browserConnector) {
        this.browserConnector = browserConnector;
    }

    public void setScopedMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public SkillContextBuffer getSkillContextBuffer() {
        return skillContextBuffer;
    }

    /**
     * 注入派生子 Agent（任务）的接缝。注入后 {@code spawn_agent} 工具可用；
     * 未注入时调用 {@code spawn_agent} 会返回明确的「不可用」说明，使纯工具测试照常可跑。
     */
    public void setAgentSpawner(AgentSpawner agentSpawner) {
        this.agentSpawner = agentSpawner;
    }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        this.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    /**
     * 注册 execute_command 的<b>逐行</b>输出观察者：参数 (command, line)，每读到一行就调用一次，
     * 而不是等 {@code waitFor} 返回（ADR 0017 D5，这是本设计里唯一一处工具层改动）。
     *
     * <p>只有 {@code execute_command} 有这个接缝：它是唯一时长无界、用户真的分不清「在跑」与「卡住」
     * 的工具（{@code read_file} / {@code grep_code} 毫秒返回）。观察者跑在专用的
     * {@code cafecli-command-output} 线程上，<b>不得写终端</b>——渲染线程是[[活区]]唯一的写者，
     * 接线方（{@code Main}）把它接成一个渲染事件。
     *
     * <p>行按读到的原样发布（包括空行）：要显示哪几行是呈现层的决定，这个接缝只负责「什么时候」。
     * 观察者抛异常不影响命令主路径，与 {@link #setWriteFileObserver} 同例。
     */
    public void setCommandOutputObserver(java.util.function.BiConsumer<String, String> observer) {
        this.commandOutputObserver = observer == null ? (c, l) -> {} : observer;
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager == null ? new LspManager(projectPath) : lspManager;
        this.lspManager.setProjectPath(projectPath);
    }

    public LspManager getLspManager() {
        return lspManager;
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return lspManager == null ? LspDiagnosticReport.EMPTY : lspManager.flushPendingDiagnostics();
    }

    /** 注入生命周期 Hook 分发器（Agent 构造期调用；子任务经拷贝构造继承）。 */
    public void setHookDispatcher(HookDispatcher hookDispatcher) {
        this.hookDispatcher = hookDispatcher;
    }

    public HookDispatcher getHookDispatcher() {
        return hookDispatcher;
    }

    /**
     * flush 本 registry 累积的 PostToolUse additionalContext（走与 LSP 诊断相同的下一轮前置注入通道）。
     * Agent 在每轮循环顶部调用；返回 null 表示无待注入内容。
     */
    public String flushPendingHookContext() {
        if (pendingHookContext.isEmpty()) {
            return null;
        }
        String joined;
        synchronized (pendingHookContext) {
            if (pendingHookContext.isEmpty()) {
                return null;
            }
            joined = String.join("\n\n", pendingHookContext);
            pendingHookContext.clear();
        }
        return joined.isBlank() ? null : joined;
    }

    /**
     * PreToolUse 分发（工具执行前、HITL 审批之前）：返回组合后的 {@link HookDecision}；
     * 分发器为空 / 无 PRE 租户 / 事件总开关关时返回 null。默认路径零成本：先查租户再读系统属性，热路径不做 getProperty。
     * ALLOW/ASK 的路由由 {@code HitlToolRegistry} 在审批边界处理（守卫绝对）；
     * 基础执行路径（无 HITL）只认 BLOCK（见 {@link #preToolUseBlockOrNull}）。
     */
    protected HookDecision dispatchPreToolUse(String name, String argumentsJson) {
        if (hookDispatcher == null || !hookDispatcher.hasTenants(HookEvent.PRE_TOOL_USE)) {
            return null;
        }
        if (!HookProperties.flag(HookProperties.PRE_TOOL_USE_ENABLED)) {
            return null;
        }
        HookPayload payload = HookPayload.forPreToolUse(
                resultSessionId, hookTaskId(), projectPath, name, parseToolInput(argumentsJson));
        return hookDispatcher.dispatch(HookEvent.PRE_TOOL_USE, payload);
    }

    protected ToolOutput preToolUseBlockOrNull(String name, String argumentsJson) {
        HookDecision decision = dispatchPreToolUse(name, argumentsJson);
        if (decision != null && decision.isBlock()) {
            return blockedToolOutput(name, decision);
        }
        return null;  // 基础路径不路由 ALLOW/ASK（无 HITL）；CONTINUE/ALLOW/ASK 均放行
    }

    /** 记 hook_block 审计并生成回灌模型的标记结果（identity 附任务归属）。 */
    protected ToolOutput blockedToolOutput(String name, HookDecision decision) {
        String reason = decision.reason() == null ? "" : decision.reason();
        recordHookAudit(AuditLog.AuditEntry.hookBlock(hookAuditIdentity("PreToolUse", name), reason, 0));
        return ToolOutput.failure(ToolOutput.Status.BLOCKED, "[Hook 拦截 / PreToolUse]\n" + reason);
    }

    /** hook 审计标识：事件/工具（+ 子任务 scope 归属）。主 Agent（scope=main）无后缀。 */
    protected String hookAuditIdentity(String event, String name) {
        String scope = hookTaskId();
        return event + "/" + name + (scope != null ? " [" + scope + "]" : "");
    }

    protected void recordHookAudit(AuditLog.AuditEntry entry) {
        try {
            auditLog.record(entry);
        } catch (Exception e) {
            // 审计失败不影响决定本身
        }
    }

    /**
     * PostToolUse 分发（工具成功执行后，覆盖单/并行/MCP 全部调用路径——统一落在 doExecuteTool 成功返回处）。
     * 把租户聚合出的 additionalContext 入队到下一轮前置注入通道。默认路径零成本。
     */
    protected void dispatchPostToolUse(String name, String argumentsJson, String outputText) {
        if (hookDispatcher == null || !hookDispatcher.hasTenants(HookEvent.POST_TOOL_USE)) {
            return;
        }
        if (!HookProperties.flag(HookProperties.POST_TOOL_USE_ENABLED)) {
            return;
        }
        HookPayload payload = HookPayload.forPostToolUse(
                resultSessionId, hookTaskId(), projectPath, name, parseToolInput(argumentsJson), outputText);
        HookDecision decision = hookDispatcher.dispatch(HookEvent.POST_TOOL_USE, payload);
        String context = decision.additionalContext();
        if (context != null && !context.isBlank()) {
            pendingHookContext.add(context.trim());
        }
    }

    /** payload 的 taskId：主 Agent（resultScope=="main"）用 null，子任务用其 scope（task_N），供台账按 agent 作用域做键。 */
    private String hookTaskId() {
        return "main".equals(resultScope) ? null : resultScope;
    }

    private JsonNode parseToolInput(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(argumentsJson);
        } catch (Exception e) {
            return null;
        }
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(projectPath)) : snapshotService;
        this.customSnapshotService = snapshotService != null;
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    /** 测试用：暴露模型选择驱动的 provider 行为，验证模型选择每任务私有不串台。 */
    public boolean shouldPreferStepSearchForTest() {
        return WebTools.prefersStepSearch(currentProvider, currentModel);
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.cafecli.llm.LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.cafecli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 的 {@link ToolOutput}（文本 / 结构化 / 图片块）。
     */
    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        tools.put(toolName, new Registered(toolName, mcpDescription(descriptor), descriptor.inputSchema(),
                descriptor, invoker::apply));
    }

    @Override
    public synchronized void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        // 刻意保留的现状：不检查 isMcp()，传入内置工具名同样会把它移除（双表时代即如此）
        tools.remove(toolName);
    }

    public synchronized void clearConversationScopedState() {
        tools.values().removeIf(Registered::isMcp);
        deleteRecursively(resultScopeDirectory());
    }

    public void clearResultSession() {
        deleteRecursively(resultSessionDirectory());
    }

    private Path resultSessionDirectory() {
        return McpResultPersister.sessionDirectory(Path.of(projectPath), resultSessionId);
    }

    private Path resultScopeDirectory() {
        return resultSessionDirectory().resolve(resultScope);
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup: stale result files are safe to remove manually under .cafecli/tool-results.
        }
    }

    @Override
    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = McpToolDescriptor.serverPrefix(serverName);
        tools.values().removeIf(e -> e.isMcp() && e.name().startsWith(prefix));
        for (McpToolDescriptor descriptor : newTools) {
            registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
        }
    }

    /**
     * 执行工具调用，只取文本。是 {@link #executeToolOutput} 的薄包装，故意 {@code final}：
     * 拦截点只有 {@link #executeToolOutput}（HITL / 测试替身都覆写它），覆写这里会被 {@link #executeTools} 绕过。
     */
    public final String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    /**
     * 执行工具调用：PreToolUse（只认 BLOCK）→ {@link #doExecuteTool}。
     *
     * 需审计的工具（{@link MutatingTools#NAMES} 与全部 MCP 工具）在 {@link #doExecuteTool} 写一行审计：
     * - 策略拦截（PathGuard / CommandGuard / 浏览器守卫 / 文件大小上限）→ deny
     * - 普通异常 → error
     * - 其他情况 → allow（仅表示工具调用真的发生过；工具内部的业务错误以返回文本呈现给 LLM，
     *   内置工具会给它带上非 OK 的 {@link ToolOutput.Status}，但审计仍记 allow）
     */
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        ToolOutput block = preToolUseBlockOrNull(name, argumentsJson);
        if (block != null) {
            return block;
        }
        return doExecuteTool(name, argumentsJson);
    }

    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return ToolOutput.failure(ToolOutput.Status.CANCELLED, "用户取消了此次工具调用");
        }
        Registered entry = tools.get(name);
        if (entry == null) {
            return ToolOutput.failure(ToolOutput.Status.ERROR, "未知工具: " + name);
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;

        try {
            ToolOutput output;
            if (entry.isMcp()) {
                // MCP 工具：执行前过浏览器守卫（拦截即策略拒绝），执行后回写守卫状态
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                output = entry.invoker().invoke(argumentsJson);
                if (output == null) {
                    output = ToolOutput.text("");
                }
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, output.text());
                }
            } else {
                // 内置工具不过浏览器守卫（维持现状）
                output = entry.invoker().invoke(argumentsJson);
            }
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            dispatchPostToolUse(name, argumentsJson, output.text());
            return output;
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.failure(ToolOutput.Status.DENIED, "🛡️ 策略拒绝: " + e.getMessage());
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.failure(ToolOutput.Status.ERROR, "工具执行失败: " + e.getMessage());
        }
    }

    protected BrowserCheckResult checkBrowserTool(String name, String argumentsJson, boolean previewOnly) {
        if (browserGuard == null || !BrowserGuard.isChromeTool(name)) {
            return BrowserCheckResult.allow(null);
        }
        return browserGuard.check(name, argumentsJson, !previewOnly);
    }

    @Override
    public AuditLog getAuditLog() {
        return auditLog;
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        return batchExecutor.execute(invocations);
    }

    private static String newResultSessionId() {
        return "session-" + UUID.randomUUID();
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private static boolean shouldAudit(String name) {
        return MutatingTools.NAMES.contains(name) || McpToolDescriptor.isNamespaced(name);
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name() + ")";
    }

    // 记录定义
    /** 注册表条目：工具定义（名 / 描述 / schema）+ 统一执行入口。{@code mcp} 非 null 即 MCP 动态工具。 */
    private record Registered(String name, String description, JsonNode parameters,
                              McpToolDescriptor mcp, Invoker invoker) {
        boolean isMcp() { return mcp != null; }
    }

    /** 原始 JSON 参数串 → 结果。允许抛受检异常，且必须原样抛出：doExecuteTool 的 "工具执行失败: " + e.getMessage() 依赖原始异常消息。 */
    @FunctionalInterface
    private interface Invoker { ToolOutput invoke(String argumentsJson) throws Exception; }
}
