package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.context.ContextProfile;
import com.paicli.context.TokenUsageFormatter;
import com.paicli.hook.FinishGateTenant;
import com.paicli.hook.HookDecision;
import com.paicli.hook.HookDispatcher;
import com.paicli.hook.HookEvent;
import com.paicli.hook.HookPayload;
import com.paicli.hook.HookProperties;
import com.paicli.hook.HooksConfigLoader;
import com.paicli.hook.HookTrustConfirmer;
import com.paicli.hook.HookTrustStore;
import com.paicli.hook.ReadLedgerTenant;
import com.paicli.hook.UserPromptSubmitGate;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.policy.AuditLog;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.ExplicitMemoryHints;
import com.paicli.memory.MemoryManager;
import com.paicli.prompt.PromptAssembler;
import com.paicli.prompt.PromptContext;
import com.paicli.prompt.PromptMode;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillIndexFormatter;
import com.paicli.skill.SkillRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import com.paicli.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private Renderer renderer;
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    // 本次 run 的显式取消令牌（ADR 0005，#09）：每任务 / 每主回合各持自己的令牌，不再靠 InheritableThreadLocal
    // 继承（虚拟线程 + 跨执行器派生下继承会串错）。为 null 时回退到旧的 CancellationContext（headless/plan 路径）。
    private CancellationToken cancellationToken;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    // 生命周期 Hook 分发器（ADR 0012）：wave 1 只接 Stop 收尾门。空注册表零成本 no-op。
    private final HookDispatcher hookDispatcher = new HookDispatcher();

    /**
     * 最近一次 run 的 LLM token 用量（仅正常完成路径回填，供 headless trace 使用）。
     * 失败或取消的 run 不会更新此值；若复用同一 Agent 实例，调用方可能读到上一次成功 run 的值。
     */
    public record TokenUsage(long inputTokens, long outputTokens, long cachedInputTokens) {
        public static TokenUsage zero() { return new TokenUsage(0, 0, 0); }
    }

    private volatile TokenUsage lastRunUsage = TokenUsage.zero();

    public TokenUsage getLastRunUsage() {
        return lastRunUsage;
    }

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        registerBuiltinHookTenants();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    /**
     * 注册内建 Hook 租户（builtin，最先求值）。默认全关（safe-edit 先例）：
     * {@code -Dpaicli.hook.finish_gate.enabled} 注册收尾门（Stop）；
     * {@code -Dpaicli.hook.read_ledger.enabled} 注册读前写台账（PreToolUse + PostToolUse，同一实例两点求值）。
     * 只要注册过任何租户，就把分发器注入 toolRegistry——工具执行点（含子任务）据此在 Pre/Post 分发；
     * 没有租户时不注入，默认路径保持零成本 no-op。
     */
    private void registerBuiltinHookTenants() {
        if (FinishGateTenant.enabledBySystemProperty()) {
            hookDispatcher.register(HookEvent.STOP, FinishGateTenant.fromSystemProperties());
        }
        if (ReadLedgerTenant.enabledBySystemProperty()) {
            ReadLedgerTenant ledger = new ReadLedgerTenant();
            hookDispatcher.register(HookEvent.PRE_TOOL_USE, ledger);
            hookDispatcher.register(HookEvent.POST_TOOL_USE, ledger);
        }
        if (hookDispatcher.hasAnyTenants()) {
            toolRegistry.setHookDispatcher(hookDispatcher);
        }
    }

    /**
     * 加载并注册用户级 {@code hooks.json} 的 shell 租户（外部 Hook 租户）。
     * 由 CLI 入口（交互 / headless run）在构造后调用；单元测试不调用，避免真实 {@code ~/.paicli/hooks.json}
     * 渗入而破坏确定性。shell 租户与内建租户共用同一分发器与事件总开关（PreToolUse 需
     * {@code -Dpaicli.hook.pre_tool_use.enabled} 等；见 {@link HookProperties}）。
     * 本 wave 只激活用户级配置；项目级解析但惰性（信任待后续 issue）。
     */
    public void installConfiguredHooks() {
        installConfiguredHooks(null);
    }

    /**
     * @param trustConfirmer 交互态项目 Hook 信任确认器（主线程读终端）；headless 传 {@code null}
     *                       —— 未钉住的项目级 hooks.json 静默跳过，已钉住的运行。
     */
    public void installConfiguredHooks(HookTrustConfirmer trustConfirmer) {
        try {
            Path projectDir = Path.of(toolRegistry.getProjectPath());
            HooksConfigLoader loader = new HooksConfigLoader(
                    projectDir, msg -> renderer().stream().println(msg))
                    .withProjectTrust(new HookTrustStore(), trustConfirmer, toolRegistry.getAuditLog());
            List<HooksConfigLoader.Registration> registrations = loader.load();
            for (HooksConfigLoader.Registration registration : registrations) {
                hookDispatcher.register(registration.event(), registration.hook());
            }
            if (hookDispatcher.hasAnyTenants()) {
                toolRegistry.setHookDispatcher(hookDispatcher);
            }
            if (!registrations.isEmpty()) {
                log.info("已加载 {} 条用户级 shell hook（hooks.json）", registrations.size());
            }
        } catch (Exception e) {
            log.warn("加载 hooks.json 失败，已跳过 shell hook: {}", e.toString());
        }
    }

    /**
     * UserPromptSubmit 事件（issue 06）：由 CLI 主路径 / headless 在提交展开后、跑本 Agent 前调用。
     * BLOCK 阻断提交（原因给用户）、additionalContext 追加到实际 prompt。委派 {@link UserPromptSubmitGate}。
     */
    public UserPromptSubmitGate.Decision onUserPromptSubmit(String prompt) {
        return new UserPromptSubmitGate(hookDispatcher, toolRegistry.getProjectPath(), toolRegistry.getAuditLog())
                .decide(prompt);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.historyCompactor.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    /** 注入本次 run 的取消令牌（ADR 0005，#09）：ESC / /task cancel 取消它即停本回合；null 时回退 CancellationContext。 */
    public void setCancellationToken(CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    private boolean isRunCancelled() {
        CancellationToken token = cancellationToken;
        return (token != null && token.isCancelled()) || CancellationContext.isCancelled();
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来。
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        pruneHistoricalImagePayloads();
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（如有 skill body 注入，前置到原文之前）
        String userMessageContent = prependSkillBodies(userInput);
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath())));
        injectFreshnessWebSearchIfNeeded(userInput);
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer(renderer());
        // 本回合按顺序执行过的工具（收尾门据此判断“有写入 + 写后无验证”）；stopBlockCount 是收尾门的反循环旗标。
        List<HookPayload.TurnTool> turnTools = new ArrayList<>();
        int stopBlockCount = 0;

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底。
        while (true) {
            if (isRunCancelled()) {
                log.info("ReAct run cancelled before iteration");
                pushStatus(budget, startNanos, "idle");
                return "⏹️ 已取消当前任务。";
            }
            // 调 LLM 前评估 conversationHistory 是否接近 window 上限；超阈值就把早期消息压缩成摘要。
            // 这是与第 3 期 Memory 短期记忆压缩并行的另一道压缩——后者只压 shortTermMemory，
            // 真正决定下一轮 LLM input token 的是这里。
            injectPendingLspDiagnostics();
            injectPendingHookContext();
            maybeCompactHistory();
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                pushStatus(budget, startNanos, "idle");
                return "❌ " + description;
            }

            int iteration = budget.beginIteration();

            try {
                List<LlmClient.Tool> toolDefinitions = toolRegistry.getToolDefinitions();
                logRequestContext("react iteration=" + iteration, toolDefinitions);
                streamRenderer.beginThinking();
                // 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolDefinitions,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
                if (isRunCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    return "⏹️ 已取消当前任务。";
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前就 flush 本轮流式渲染器，避免 TerminalMarkdownRenderer
                    // 内部 pending 缓冲区（仅按换行 flush）里的文本被 HITL 提示"跨过"
                    // 造成标题和内容错位。重置后下一轮迭代的 reasoning/content 会重新打印标题。
                    streamRenderer.resetBetweenIterations();
                    renderer().appendToolCalls(response.toolCalls());

                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult toolResult : toolResults) {
                        memoryManager.addToolResult(toolResult.name(), toolResult.result());
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    recordTurnTools(turnTools, toolResults);
                    appendImageToolMessages(toolResults);
                    pushStatus(budget, startNanos, "running");

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;
                }

                // 没有工具调用，直接返回结果
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                // Stop 分发点（收尾门等 Hook 租户）：BLOCK 会把原因作为清晰标记的消息回灌并续跑，
                // 同 LSP 诊断注入的通道；阻断消耗轮数，硬轮数上限始终兜底。
                HookDecision stopDecision = dispatchStopHooks(turnTools, stopBlockCount);
                if (stopDecision.isBlock()) {
                    stopBlockCount++;
                    injectStopBlock(stopDecision.reason());
                    streamRenderer.resetBetweenIterations();
                    pushStatus(budget, startNanos, "running");
                    continue;
                }

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 记录 token 使用
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                this.lastRunUsage = new TokenUsage(
                        budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    return "";
                }
                streamRenderer.clearThinkingPanel();
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content());

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));

        // 清空短期记忆
        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
        toolRegistry.clearConversationScopedState();
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());
    }

    private void maybeCompactHistory() {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
            if (compacted) {
                renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    /**
     * 在收尾点分发 Stop hook。总开关 {@code paicli.hook.stop.enabled} 关闭、或没有注册 Stop 租户时，
     * 直接返回 CONTINUE（零成本 no-op）。
     */
    private HookDecision dispatchStopHooks(List<HookPayload.TurnTool> turnTools, int stopBlockCount) {
        if (!HookProperties.flag(HookProperties.STOP_ENABLED) || !hookDispatcher.hasTenants(HookEvent.STOP)) {
            return HookDecision.cont();
        }
        HookPayload payload = HookPayload.forStop(
                null,                         // sessionId：wave 1 主 Agent 暂不带
                null,                         // taskId：null = 主 Agent
                toolRegistry.getProjectPath(),
                turnTools,
                stopBlockCount);
        return hookDispatcher.dispatch(HookEvent.STOP, payload);
    }

    /** 把 Stop 阻断原因作为清晰标记的用户消息回灌（不是工具结果），并记一条 hook_block 审计。 */
    private void injectStopBlock(String reason) {
        String marked = "[收尾门 / Finish Gate]\n" + (reason == null ? "" : reason);
        conversationHistory.add(LlmClient.Message.user(marked));
        renderer().stream().println("⛔ 收尾门拦截：本回合有写入但写后未运行验证命令，已要求先自证再收尾。");
        try {
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.hookBlock("Stop/finish-gate", reason, 0));
        } catch (Exception e) {
            log.debug("finish-gate audit record failed", e);
        }
        log.info("Finish Gate blocked stop; injected recovery instruction");
    }

    /** 把本轮工具执行结果折成 Stop payload 的 turn 摘要（名 + 目标 + 是否成功）。 */
    private void recordTurnTools(List<HookPayload.TurnTool> turnTools, List<ToolExecutionResult> toolResults) {
        if (toolResults == null) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            turnTools.add(new HookPayload.TurnTool(
                    result.name(),
                    hookTurnTarget(result),
                    hookToolSucceeded(result)));
        }
    }

    private String hookTurnTarget(ToolExecutionResult result) {
        String name = result.name();
        if (name == null) {
            return "";
        }
        return switch (name) {
            case "execute_command" -> extractJsonArg(result.argumentsJson(), "command");
            case "write_file", "edit_file", "read_file" -> extractJsonArg(result.argumentsJson(), "path");
            case "create_project" -> {
                String projectName = extractJsonArg(result.argumentsJson(), "name");
                yield projectName.isBlank() ? extractJsonArg(result.argumentsJson(), "path") : projectName;
            }
            default -> "";
        };
    }

    /** 收尾门只关心“成功”：execute_command 看退出码 0，写类工具看是否非失败前缀。 */
    private boolean hookToolSucceeded(ToolExecutionResult result) {
        if (result == null || result.timedOut()) {
            return false;
        }
        String text = result.result();
        if (text == null) {
            return false;
        }
        if ("execute_command".equals(result.name())) {
            return text.contains("(exit code: 0)");
        }
        String trimmed = text.stripLeading();
        return !trimmed.startsWith("工具执行失败")
                && !trimmed.startsWith("❌")
                && !trimmed.startsWith("执行命令失败")
                && !trimmed.startsWith("命令执行超时");
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    /**
     * 把 PostToolUse hook 租户累积的 additionalContext 作为清晰标记的 user 消息注入下一轮请求
     * （与 LSP 诊断相同的前置注入通道）。空则无操作。
     */
    private void injectPendingHookContext() {
        String context = toolRegistry.flushPendingHookContext();
        if (context == null || context.isBlank()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user("[Hook 上下文 / PostToolUse]\n" + context));
        log.info("Injected PostToolUse hook context into ReAct conversation");
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return userInput;
        return drained + "\n用户输入：\n" + userInput;
    }

    private void injectFreshnessWebSearchIfNeeded(String userInput) {
        if (!shouldRunFreshnessWebSearch(userInput)) {
            return;
        }
        String query = buildFreshnessSearchQuery(userInput);
        if (query.isBlank()) {
            return;
        }
        try {
            String args = new ObjectMapper().createObjectNode()
                    .put("query", query)
                    .put("top_k", 5)
                    .toString();
            log.info("Auto freshness web_search preflight: query={}", query);
            renderer().stream().println(AnsiStyle.subtle("  → 检测到时效性问题，已先联网搜索 \"" + query + "\""));
            String result = toolRegistry.executeTool("web_search", args);
            conversationHistory.add(LlmClient.Message.user("""
                    ## 联网预检结果

                    用户问题包含最新/当前/2026/趋势等时效性需求。以下是本轮进入模型回答前自动执行 `web_search` 得到的结果；回答必须优先基于这些联网结果和用户提供的图片，不要声称无法实时搜索。

                    查询：%s

                    %s
                    """.formatted(query, result == null ? "" : result.trim())));
        } catch (Exception e) {
            log.warn("Auto freshness web_search preflight failed", e);
            conversationHistory.add(LlmClient.Message.user("""
                    ## 联网预检结果

                    本轮问题需要最新信息，系统已尝试自动联网搜索，但搜索执行失败：%s
                    回答时必须说明这是联网失败后的降级结果，不要声称工具不可用。
                    """.formatted(e.getMessage())));
        }
    }

    private boolean shouldRunFreshnessWebSearch(String userInput) {
        if (userInput == null || userInput.isBlank() || !toolRegistry.hasTool("web_search")) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("不要联网") || normalized.contains("不用联网")
                || normalized.contains("不要搜索") || normalized.contains("不用搜索")
                || normalized.contains("别搜索") || normalized.contains("offline")) {
            return false;
        }
        return normalized.contains("最新")
                || normalized.contains("当前")
                || normalized.contains("今天")
                || normalized.contains("今年")
                || normalized.contains("现在")
                || normalized.contains("新闻")
                || normalized.contains("趋势")
                || normalized.contains("版本")
                || normalized.contains("2026")
                || normalized.contains("latest")
                || normalized.contains("current")
                || normalized.contains("recent");
    }

    private String buildFreshnessSearchQuery(String userInput) {
        String cleaned = userInput == null ? "" : userInput
                .replaceAll("@image:(<[^>]+>|\\S+)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("ai agent") && (cleaned.contains("2026") || cleaned.contains("技术栈") || cleaned.contains("趋势"))) {
            return "2026 AI Agent 技术栈 趋势 MCP A2A Agents SDK LangGraph CrewAI AutoGen";
        }
        if (cleaned.length() > 160) {
            return cleaned.substring(0, 160);
        }
        return cleaned;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    private void storeExplicitBrowserMemoryHint(String userInput) {
        List<String> recentTexts = conversationHistory.stream()
                .map(LlmClient.Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
    }

    public String getContextStatus() {
        com.paicli.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = com.paicli.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.paicli.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            LlmClient.Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.paicli.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls, int iteration) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
            emitToolResultSummary(result);
        }
        return results;
    }

    private void emitToolResultSummary(ToolExecutionResult result) {
        if (result == null || result.name() == null) {
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            renderer().stream().println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private String webSearchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private String webFetchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (text.startsWith("抓取失败") || text.startsWith("❌")) {
            return verb + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            conversationHistory.add(LlmClient.Message.user(parts));
        }
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private final StringBuilder answerLineBuffer = new StringBuilder();
        private final StringBuilder codeBody = new StringBuilder();
        private Renderer.BlockWriter reasoningBlock;
        private Renderer.BlockWriter answerBlock;
        private String codeLanguage = "";
        private boolean inCodeBlock;
        private boolean answerHasProse;
        private boolean lastAnswerLineBlank;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;
        private boolean turnEnded;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking("Thinking");
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
            endTurnIfNeeded();
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningBlock = newReasoningBlock();
                reasoningBlock.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningBlock.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningBlock != null) {
                    reasoningBlock.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    Renderer.BlockWriter block = newReasoningBlock();
                    block.append(pendingReasoning.toString());
                    block.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentStarted = true;
                streamedOutput = true;
            }
            appendAnswerSource(delta);
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningBlock != null) {
                reasoningBlock.finish();
                reasoningBlock = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            finishAnswerSource();
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                Renderer.BlockWriter block = newReasoningBlock();
                block.append(late);
                block.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void endTurnIfNeeded() {
            if (!turnEnded && renderer != null) {
                turnEnded = true;
                renderer.endTurn();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningBlock != null) {
                reasoningBlock.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            finishAnswerSource();
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                Renderer.BlockWriter block = newReasoningBlock();
                block.append(late);
                block.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
            }
            endTurnIfNeeded();
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            Renderer.BlockWriter block = newReasoningBlock();
            block.append(pending);
            block.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private Renderer.BlockWriter newReasoningBlock() {
            return renderer != null ? renderer.beginReasoning() : legacyMarkdownBlock();
        }

        private Renderer.BlockWriter newAnswerBlock() {
            return renderer != null ? renderer.beginAnswer() : legacyMarkdownBlock();
        }

        private void appendAnswerSource(String delta) {
            answerLineBuffer.append(delta);
            int newline;
            while ((newline = answerLineBuffer.indexOf("\n")) >= 0) {
                String line = answerLineBuffer.substring(0, newline + 1);
                answerLineBuffer.delete(0, newline + 1);
                routeAnswerLine(line);
            }
        }

        private void routeAnswerLine(String line) {
            String trimmed = line.strip().trim();
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    emitCodeBlock();
                } else {
                    boolean needsBlockSpacing = answerHasProse && !lastAnswerLineBlank;
                    finishAnswerBlock();
                    if (needsBlockSpacing) {
                        out().println();
                    }
                    answerHasProse = false;
                    lastAnswerLineBlank = false;
                    inCodeBlock = true;
                    codeLanguage = trimmed.substring(3).trim();
                }
                return;
            }
            if (inCodeBlock) {
                codeBody.append(line);
                return;
            }
            if (answerBlock == null) {
                answerBlock = newAnswerBlock();
            }
            answerBlock.append(line);
            answerHasProse = true;
            lastAnswerLineBlank = line.isBlank();
        }

        private void finishAnswerSource() {
            if (answerLineBuffer.length() > 0) {
                routeAnswerLine(answerLineBuffer.toString());
                answerLineBuffer.setLength(0);
            }
            if (inCodeBlock) {
                emitCodeBlock();
            }
            finishAnswerBlock();
            answerHasProse = false;
            lastAnswerLineBlank = false;
        }

        private void finishAnswerBlock() {
            if (answerBlock != null) {
                answerBlock.finish();
                answerBlock = null;
            }
        }

        private void emitCodeBlock() {
            if (renderer != null) {
                renderer.appendCodeBlock(codeLanguage, codeBody.toString());
            } else {
                appendLegacyCodeBlock(codeLanguage, codeBody.toString());
            }
            codeBody.setLength(0);
            codeLanguage = "";
            inCodeBlock = false;
        }

        private void appendLegacyCodeBlock(String language, String code) {
            String normalizedLanguage = language == null ? "" : language.trim();
            StringBuilder markdown = new StringBuilder("```").append(normalizedLanguage).append('\n')
                    .append(code == null ? "" : code);
            if (code == null || !code.endsWith("\n")) {
                markdown.append('\n');
            }
            markdown.append("```\n");
            TerminalMarkdownRenderer markdownRenderer = new TerminalMarkdownRenderer(out());
            markdownRenderer.append(markdown.toString());
            markdownRenderer.finish();
        }

        private Renderer.BlockWriter legacyMarkdownBlock() {
            TerminalMarkdownRenderer markdownRenderer = new TerminalMarkdownRenderer(out());
            return new Renderer.BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                    markdownRenderer.append(sourceDelta);
                }

                @Override
                public void finish() {
                    markdownRenderer.finish();
                }
            };
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            publishCompletedReasoningIfNeeded();
        }

        private void publishCompletedReasoningIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            String reasoning = visibleReasoning.toString();
            if (reasoning.isBlank()) {
                return;
            }
            Renderer.BlockWriter block = renderer.beginReasoning();
            block.append(reasoning);
            block.finish();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
