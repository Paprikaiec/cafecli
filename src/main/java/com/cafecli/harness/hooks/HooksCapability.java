package com.cafecli.harness.hooks;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.IterationDecision;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.BoundaryInbox;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.Submission;
import com.cafecli.harness.ToolExecution;
import com.cafecli.harness.config.HooksConfig;
import com.cafecli.harness.permission.AuditCapability;
import com.cafecli.harness.permission.AuditCapability.HookObservation;
import com.cafecli.harness.permission.PermissionRoute;
import com.cafecli.hook.FinishGateTenant;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookDispatcher;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookTenant;
import com.cafecli.hook.HookTrustConfirmer;
import com.cafecli.hook.HookTrustStore;
import com.cafecli.hook.HooksConfigLoader;
import com.cafecli.hook.ReadLedgerTenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Lifecycle hooks composed at harness seams. Each attached agent owns its dispatcher, ledger and run summary. */
public final class HooksCapability implements Capability {
    public record Registration(HookEvent event, HookTenant tenant) {
        public Registration { Objects.requireNonNull(event); Objects.requireNonNull(tenant); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(HooksCapability.class);
    private final HooksConfig config;
    private List<Registration> registrations;
    private final AuditCapability audit;
    private final HookTrustConfirmer confirmer;
    private final Consumer<String> status;
    private final boolean loadConfigured;
    private String sessionId;
    private volatile HookDispatcher mainDispatcher;

    /** Explicit tenants for embedded sessions. Stateful built-in tenants are always created per agent. */
    public HooksCapability(HooksConfig config, List<Registration> registrations, AuditCapability audit) {
        this(config, registrations, audit, null, ignored -> {}, false);
    }

    /** Configured shell hooks; null confirmer is v1's headless trust policy. */
    public HooksCapability(HooksConfig config, AuditCapability audit, HookTrustConfirmer confirmer, Consumer<String> status) {
        this(config, List.of(), audit, confirmer, status, true);
    }

    private HooksCapability(HooksConfig config, List<Registration> registrations, AuditCapability audit,
                            HookTrustConfirmer confirmer, Consumer<String> status, boolean loadConfigured) {
        this.config = Objects.requireNonNull(config);
        this.registrations = List.copyOf(registrations);
        this.audit = audit;
        this.confirmer = confirmer;
        this.status = Objects.requireNonNull(status);
        this.loadConfigured = loadConfigured;
    }

    @Override public void attachSession(SessionRegistrar session) {
        sessionId = session.sessionId();
        session.command(com.cafecli.harness.SessionCommand.control("/hooks","查看已加载的生命周期 Hook",(context,args) ->
                com.cafecli.hook.HookCommands.format(mainDispatcher == null ? List.of() : mainDispatcher.listing(),event -> switch (event) {
                    case STOP -> config.stop(); case PRE_TOOL_USE -> config.preToolUse();
                    case POST_TOOL_USE -> config.postToolUse(); case USER_PROMPT_SUBMIT -> config.userPromptSubmit();
                }) + "\n"));
        if (loadConfigured) {
            try {
                registrations = new HooksConfigLoader(config.homes().user().resolve("hooks.json"),
                        config.homes().project().resolve("hooks.json"), config.projectRoot(), status,
                        Path.of(config.userHome()), config.properties(), config.environment())
                        .withProjectTrustObserver(new HookTrustStore(config.homes().user().resolve("hook-trust.json")),
                                confirmer, (project, decision) -> observe(HookObservation.Kind.TRUST, project, decision))
                        .load().stream().map(entry -> new Registration(entry.event(), entry.hook())).toList();
            } catch (Exception e) {
                LOG.warn("加载 hooks.json 失败，已跳过 shell hook: {}", e.toString());
            }
        }
        HookDispatcher submit = new HookDispatcher();
        registrations.forEach(entry -> submit.register(entry.event(), entry.tenant()));
        session.onSubmit((prompt, cancellation) -> {
            if (!config.userPromptSubmit()) return Submission.proceed(prompt);
            HookDecision decision = submit.dispatch(HookEvent.USER_PROMPT_SUBMIT,
                    HookPayload.forUserPromptSubmit(null, null, config.projectRoot().toString(), prompt), cancellation::isCancelled);
            if (decision.isBlock()) {
                observe(HookObservation.Kind.BLOCK, "UserPromptSubmit", decision.reason());
                return Submission.block(decision.reason());
            }
            String context = decision.additionalContext();
            return Submission.proceed(context == null || context.isBlank() ? prompt
                    : prompt + "\n\n[Hook 上下文 / UserPromptSubmit]\n" + context.trim());
        });
    }

    @Override public void attachAgent(AgentScope agent) {
        State state = new State(agent);
        if (AgentSession.MAIN_AGENT_ID.equals(agent.agentId())) mainDispatcher=state.dispatcher;
        agent.messageKind(StopBlock.KIND);
        agent.messageKind(HookContext.KIND);
        agent.runStartWriter((input, token) -> {
            state.start(token);
            return List.of();
        });
        agent.permissionRoute(state::before);
        agent.afterToolCall(state::after);
        agent.afterAuxiliaryToolCall(completion -> state.post(completion.call(), completion.result(), completion.cancellation()));
        agent.iterationEnd(state::end);
    }

    private final class State {
        private final HookDispatcher dispatcher = new HookDispatcher();
        private final String taskId;
        private final String sessionId;
        private final BoundaryInbox inbox;
        private final List<HookPayload.TurnTool> summary = new ArrayList<>();
        /** Completion observations retain effective arguments; iteration end folds them in model source order. */
        private final Map<String, HookPayload.TurnTool> completed = new HashMap<>();
        private CancellationToken cancellation;
        private int blocks;

        State(AgentScope agent) {
            sessionId = Objects.requireNonNull(HooksCapability.this.sessionId, "session hooks must run first");
            taskId = AgentSession.MAIN_AGENT_ID.equals(agent.agentId()) ? null : agent.agentId();
            inbox = agent.inbox();
            if (config.finishGate()) dispatcher.register(HookEvent.STOP,
                    new FinishGateTenant(Pattern.compile(config.verifyRegex()), config.blockCap()));
            if (config.readLedger()) {
                var ledger = new ReadLedgerTenant();
                dispatcher.register(HookEvent.PRE_TOOL_USE, ledger);
                dispatcher.register(HookEvent.POST_TOOL_USE, ledger);
            }
            registrations.forEach(entry -> dispatcher.register(entry.event(), entry.tenant()));
        }

        void start(CancellationToken token) {
            cancellation = token;
            summary.clear();
            completed.clear();
            blocks = 0;
        }

        PermissionRoute before(ToolCall call, CancellationToken token) {
            if (!config.preToolUse()) return PermissionRoute.CONTINUE;
            HookDecision decision = dispatcher.dispatch(HookEvent.PRE_TOOL_USE,
                    HookPayload.forPreToolUse(sessionId, taskId, config.projectRoot().toString(), call.name(), parse(call.arguments())),
                    token::isCancelled);
            return switch (decision.outcome()) {
                case ALLOW -> PermissionRoute.ALLOW;
                case ASK -> PermissionRoute.ASK;
                case BLOCK -> {
                    String reason = decision.reason() == null ? "" : decision.reason();
                    observe(HookObservation.Kind.BLOCK, "PreToolUse/" + call.name() + (taskId == null ? "" : " [" + taskId + "]"), reason);
                    yield PermissionRoute.complete(new ToolResult(List.of(new TextBlock(
                            "[Hook 拦截 / PreToolUse]\n" + reason)), null, ToolResult.Status.BLOCKED));
                }
                default -> PermissionRoute.CONTINUE;
            };
        }

        ToolResult after(ToolCall call, ToolResult result) {
            if (config.stop()) completed.put(call.id(), summarize(call, result));
            post(call, result, cancellation);
            return result;
        }

        void post(ToolCall call, ToolResult result, CancellationToken token) {
            ToolExecution execution = ToolExecution.from(result);
            // v1 dispatches on every normal invoker return, including returned business ERROR, but not throws.
            if (config.postToolUse() && execution != null && execution.kind() == ToolExecution.Kind.RETURNED) {
                String text = result.details() == null ? "" : result.details().path("text").asText("");
                HookDecision decision = dispatcher.dispatch(HookEvent.POST_TOOL_USE,
                        HookPayload.forPostToolUse(sessionId, taskId, config.projectRoot().toString(), call.name(), parse(call.arguments()), text),
                        token::isCancelled);
                if (decision.additionalContext() != null && !decision.additionalContext().isBlank()) {
                    inbox.post(new HookContext(decision.additionalContext().trim()));
                }
            }
        }

        IterationDecision end(AssistantMessage message, List<ToolResultEntry> results) {
            if (config.stop()) {
                for (ToolCall call : message.toolCalls()) {
                    HookPayload.TurnTool observation = completed.remove(call.id());
                    if (observation != null) summary.add(observation);
                }
            }
            if (!message.toolCalls().isEmpty()) return IterationDecision.proceed();
            if (config.stop()) {
                HookDecision decision = dispatcher.dispatch(HookEvent.STOP,
                        HookPayload.forStop(null, taskId, config.projectRoot().toString(), summary, blocks), cancellation::isCancelled);
                if (decision.isBlock()) {
                    blocks++;
                    observe(HookObservation.Kind.BLOCK, "Stop/finish-gate", decision.reason());
                    return IterationDecision.proceed(new StopBlock(decision.reason()));
                }
            }
            return IterationDecision.end();
        }
    }

    private static HookPayload.TurnTool summarize(ToolCall call, ToolResult result) {
        JsonNode input = parse(call.arguments());
        String key = "execute_command".equals(call.name()) ? "command"
                : "create_project".equals(call.name()) && input != null && input.hasNonNull("name") ? "name" : "path";
        String target = input == null ? "" : input.path(key).asText("");
        boolean success = result.status() == ToolResult.Status.OK;
        // The existing command leaf carries exit code only in its output data; OK alone means it ran, not exit 0.
        if ("execute_command".equals(call.name())) success &= result.content().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .anyMatch(text -> text.text().contains("(exit code: 0)"));
        return new HookPayload.TurnTool(call.name(), target, success);
    }

    private void observe(HookObservation.Kind kind, String identity, String reason) {
        if (audit != null) audit.observeHook(new HookObservation(kind, identity, reason));
    }

    private static JsonNode parse(String input) {
        try { return JSON.readTree(input); } catch (Exception e) { return null; }
    }
}
