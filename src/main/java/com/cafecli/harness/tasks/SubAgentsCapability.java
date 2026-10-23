package com.cafecli.harness.tasks;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentTool;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.AgentFactory;
import com.cafecli.harness.AgentProfile;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.BoundaryInbox;
import com.cafecli.harness.Capability;
import com.cafecli.harness.RunOutcomeText;
import com.cafecli.harness.SessionCommand;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.config.TaskConfig;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.TaskSpawner;
import com.cafecli.tool.TaskTools;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Session-owned task lifetimes; each attaching agent owns only its current Attached invocations. */
public final class SubAgentsCapability implements Capability {
    private final AgentFactory factory;
    private final AgentProfile childProfile;
    private final TaskConfig config;
    private final AtomicInteger nextId = new AtomicInteger();
    private final Object lock = new Object();
    private final Map<String, Child> children = new LinkedHashMap<>();
    private final Set<Execution> activeExecutions = new LinkedHashSet<>();
    private final List<Throwable> cleanupFailures = new ArrayList<>();
    private enum Status { RUNNING, COMPLETED, CANCELLED, FAILED, REJECTED }
    private record Summary(String description, Status status, String preview, boolean legacyFailure) {}
    private final Map<String, Summary> registry = new LinkedHashMap<>();
    private final Map<String, State> owners = new HashMap<>();
    private final com.cafecli.task.IdleTaskReaper reaper;
    private final com.cafecli.task.AdmissionControl admission;
    private boolean closed;

    public SubAgentsCapability(AgentFactory factory, AgentProfile childProfile) {
        this(factory, childProfile, TaskConfig.DEFAULT);
    }

    public SubAgentsCapability(AgentFactory factory, AgentProfile childProfile, TaskConfig config) {
        this.factory = Objects.requireNonNull(factory);
        this.childProfile = Objects.requireNonNull(childProfile);
        this.config = Objects.requireNonNull(config);
        this.reaper = new com.cafecli.task.IdleTaskReaper(config.maxIdle());
        this.admission = new com.cafecli.task.AdmissionControl(config.maxConcurrency(), config.maxDepth());
    }

    @Override public void attachSession(SessionRegistrar session) {
        session.onClose(this::close);
        session.command(SessionCommand.control("/task", "查看与管理会话内任务", (ignored, input) -> command(input)));
    }

    @Override public void attachAgent(AgentScope scope) {
        var state = new State(scope);
        synchronized (lock) {
            State previous = owners.get(scope.agentId());
            if (previous == null) owners.put(scope.agentId(), state);
            else { state.previous = previous; previous.replacement = state; }
        }
        scope.messageKind(TaskNotification.KIND);
        scope.runStart(state::start);
        scope.runEnd(state::finish);
        scope.onClose(state::close);
        for (var definition : new TaskTools(() -> null).tools()) {
            scope.tool(new AgentTool() {
                @Override public String name() { return definition.name(); }
                @Override public String description() { return definition.description(); }
                @Override public JsonNode parameters() { return definition.parameters().deepCopy(); }
                @Override public ToolResult execute(String id, JsonNode input, CancellationToken token, Consumer<ToolResult> progress) {
                    TaskSpawner spawner = new TaskSpawner() {
                        @Override public String spawn(String description, String prompt) { return state.spawn(description, prompt, token, false); }
                        @Override public String spawnBackground(String description, String prompt) { return state.spawn(description, prompt, token, config.interactive()); }
                        @Override public String continueTask(String taskId, String message) { return state.continueTask(taskId, message, token); }
                        @Override public String taskOutput(String taskId) { return SubAgentsCapability.this.taskOutput(taskId); }
                    };
                    var leaf = new TaskTools(() -> spawner).tools().stream().filter(tool -> tool.name().equals(name())).findFirst().orElseThrow();
                    return new LeafToolAdapter(leaf).execute(id, input, token, progress);
                }
            });
        }
    }

    private static final class Run {
        final CancellationToken parent;
        final List<Execution> executions = new ArrayList<>();
        Run(CancellationToken parent) { this.parent = parent; }
    }

    private final class State {
        final String id;
        final BoundaryInbox inbox;
        final AgentScope scope;
        State previous;
        State replacement;
        Run current;
        boolean unavailable;
        State(AgentScope scope) { this.id = scope.agentId(); this.inbox = scope.inbox(); this.scope = scope; }

        void start(CancellationToken token) {
            synchronized (lock) {
                if (closed || unavailable || current != null) throw new IllegalStateException("sub-agent scope is unavailable");
                current = new Run(token);
            }
        }

        boolean admits(CancellationToken parent) {
            return !closed && !unavailable && current != null && current.parent == parent && !parent.isCancelled();
        }

        String spawn(String description, String prompt, CancellationToken parent, boolean detached) {
            Execution execution;
            synchronized (lock) {
                // The explicit parent token identifies the generation; abandoned workers cannot enter later runs.
                if (!admits(parent)) return "子任务已取消";
                var child = new Child("task_" + nextId.incrementAndGet(), description, id);
                children.put(child.id, child);
                execution = launch(child, prompt, parent, detached);
            }
            if (detached) return status(execution.child.id, "background")
                    + " 已在后台启动，完成后会把结论推送回来（task_id=" + execution.child.id + "）";
            return awaitForeground(execution, parent, true);
        }

        String awaitForeground(Execution execution, CancellationToken parent, boolean autoBackground) {
            try {
                if (autoBackground && config.interactive() && config.autoBackgroundMillis() > 0) {
                    try { execution.thread.join(config.autoBackgroundMillis()); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        execution.await();
                        return execution.result;
                    }
                    synchronized (lock) {
                        if (!execution.finished && !parent.isCancelled()) {
                            execution.detached = true;
                            if (execution.link != null) execution.link.close();
                            return status(execution.child.id, "background") + " 运行超过 " + config.autoBackgroundMillis()
                                    + "ms 已自动转入后台（task_id=" + execution.child.id + "），完成后推送结果。";
                        }
                    }
                }
                execution.await();
                return execution.result;
            } finally { if (execution.waitingOwner != null) execution.waitingOwner.endChildWait(); }
        }

        String continueTask(String taskId, String message, CancellationToken parent) {
            Execution execution;
            synchronized (lock) {
                if (!admits(parent)) return "子任务已取消";
                Child child = children.get(taskId);
                if (child == null) return expired(taskId);
                if (child.execution != null) return "任务 " + taskId + " 仍在运行，请等待完成后再用 send_message 续派。";
                execution = launch(child, message, parent, false);
            }
            return awaitForeground(execution, parent, false);
        }

        Execution launch(Child child, String prompt, CancellationToken parent, boolean detached) {
            var execution = new Execution(child, prompt, detached);
            child.execution = execution;
            registry.put(child.id, new Summary(child.description, Status.RUNNING, "", false));
            reaper.onRunning(child.id);
            if (!detached) {
                Child parentChild = children.get(id);
                execution.waitingOwner = parentChild == null ? null : parentChild.execution;
                if (execution.waitingOwner != null) execution.waitingOwner.beginChildWait();
            }
            current.executions.add(execution);
            activeExecutions.add(execution);
            execution.thread = Thread.ofVirtual().name("cafecli-" + child.id).unstarted(execution::run);
            if (!detached) execution.link = parent.onCancel(execution::cancelIfAttached);
            execution.thread.start();
            return execution;
        }

        void finish() {
            List<Execution> attached;
            synchronized (lock) {
                if (current == null) return;
                attached = current.executions.stream().filter(execution -> !execution.detached).toList();
                current = null;
            }
            stopAndJoin(attached);
        }

        void close() {
            try { finish(); }
            finally {
                synchronized (lock) {
                    unavailable = true;
                    if (owners.get(id) == this) {
                        if (replacement != null && !replacement.unavailable) {
                            // A successful clear closes the old agent only after the replacement is fully bound.
                            State next = replacement;
                            next.previous = null;
                            owners.put(id, next);
                            scope.takeInbox(TaskNotification.class::isInstance).forEach(next.inbox::post);
                        } else owners.remove(id, this);
                    } else if (previous != null && previous.replacement == this) {
                        // Partial or rejected replacement: the existing ready target never stopped owning delivery.
                        previous.replacement = null;
                    }
                }
            }
        }
    }

    private final class Child {
        final String id;
        final String description;
        final String parentId;
        final int depth;
        Agent agent;
        Execution execution;
        com.cafecli.task.BoundedMessageMirror mirror = new com.cafecli.task.BoundedMessageMirror(config.messageMirrorCap());
        boolean completed;
        java.util.function.Function<List<com.cafecli.core.log.LogEntry>, List<com.cafecli.ai.message.Message>> projectMessages;
        Child(String id, String description, String parentId) {
            this.id = id; this.description = description; this.parentId = parentId;
            Child parent = children.get(parentId);
            this.depth = parent == null ? 1 : parent.depth + 1;
        }
    }

    private String taskOutput(String taskId) {
        synchronized (lock) {
            Child child = children.get(taskId);
            if (child == null || child.agent == null || !child.completed) return expired(taskId);
            return "[" + taskId + " · 完整轨迹]" + child.mirror.snapshot().stream().map(line -> "\n" + line)
                    .collect(java.util.stream.Collectors.joining());
        }
    }

    private static String expired(String id) { return "任务 " + id + " 已过期或不存在，请用 spawn_agent 重开。"; }

    private void mirror(Child child) {
        if (child.agent == null) return;
        mirror(child, child.projectMessages.apply(child.agent.log()));
    }

    private void mirror(Child child, List<com.cafecli.ai.message.Message> messages) {
        var mirror = new com.cafecli.task.BoundedMessageMirror(config.messageMirrorCap());
        for (var message : messages) {
            if (message instanceof com.cafecli.ai.message.UserMessage user) mirror.add("user: " + text(user.content()));
            else if (message instanceof com.cafecli.ai.message.AssistantMessage assistant) {
                StringBuilder line = new StringBuilder("assistant: ").append(assistant.text());
                for (var call : assistant.toolCalls()) line.append("\n  ⚙ ").append(call.name()).append(' ').append(call.arguments());
                mirror.add(line.toString());
            } else if (message instanceof com.cafecli.ai.message.ToolResultMessage tool) mirror.add("tool: " + text(tool.content()));
        }
        synchronized (lock) { child.mirror = mirror; }
    }

    private static String text(List<? extends com.cafecli.ai.message.UserContent> content) {
        return content.stream().filter(com.cafecli.ai.message.TextBlock.class::isInstance)
                .map(com.cafecli.ai.message.TextBlock.class::cast).map(com.cafecli.ai.message.TextBlock::text)
                .collect(java.util.stream.Collectors.joining());
    }

    private final class Execution {
        final Child child;
        final String prompt;
        final CancellationToken token = new CancellationToken();
        CancellationToken.Registration link;
        Thread thread;
        boolean detached;
        boolean finished;
        String result;
        Status outcome = Status.FAILED;
        boolean legacyFailure;
        boolean permitHeld;
        boolean acceptingPermit = true;
        boolean reacquiringPermit;
        Execution waitingOwner;
        int childWaiters;
        Throwable cleanupFailure;

        Execution(Child child, String prompt, boolean detached) {
            this.child = child; this.prompt = prompt; this.detached = detached;
        }

        void run() {
            try {
                admission.acquire(child.depth);
                synchronized (lock) { permitHeld = true; }
                if (token.isCancelled()) { outcome = Status.CANCELLED; result = status(child.id, "done") + "\n\n子任务已取消"; }
                else {
                    if (child.agent == null) {
                        var built = factory.createWithProjection(child.id, childProfile, messages -> mirror(child, messages));
                        child.agent = built.agent();
                        child.projectMessages = built.projectMessages();
                    }
                    RunOutcome runOutcome = child.agent.run(UserMessage.text(prompt), token);
                    outcome = runOutcome instanceof RunOutcome.Completed ? Status.COMPLETED
                            : runOutcome instanceof RunOutcome.Cancelled ? Status.CANCELLED : Status.FAILED;
                    String text = RunOutcomeText.format(runOutcome);
                    if (runOutcome instanceof RunOutcome.Completed && text.isBlank()) {
                        var log = child.agent.log();
                        for (int i = log.size() - 1; i >= 0; i--) {
                            if (log.get(i) instanceof com.cafecli.core.log.AssistantEntry answer && answer.message().toolCalls().isEmpty()
                                    && !answer.message().text().isBlank()) {
                                text = answer.message().text(); break;
                            }
                        }
                    }
                    // Legacy presentation labels remain independent from the typed outcome and tool status.
                    legacyFailure = text.stripLeading().startsWith("❌");
                    result = status(child.id, legacyFailure ? "failed" : "done") + "\n\n" + text;
                }
            } catch (com.cafecli.task.AdmissionControl.AdmissionException rejected) {
                outcome = Status.REJECTED;
                legacyFailure = true;
                result = status(child.id, "rejected") + "\n\n" + rejected.getMessage();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outcome = Status.CANCELLED;
                legacyFailure = true;
                result = status(child.id, "failed") + "\n\n派生等待名额时被中断";
            } catch (Throwable failure) {
                legacyFailure = true;
                result = status(child.id, "failed") + "\n\n子任务执行失败: " + failure.getMessage();
            } finally { complete(); }
        }

        void complete() {
            Throwable failure = null;
            List<Child> evicted = new ArrayList<>();
            try {
                synchronized (lock) {
                    acceptingPermit = false;
                    if (permitHeld) { permitHeld = false; admission.release(); }
                }
                try { if (link != null) link.close(); }
                catch (RuntimeException | Error e) { failure = collect(failure, e); }
                try { mirror(child); }
                catch (RuntimeException | Error e) { failure = collect(failure, e); }
                synchronized (lock) {
                    // Agent.run's finally has returned. Keep tracking this thread through all remaining cleanup.
                    child.completed = true;
                    registry.put(child.id, new Summary(child.description, outcome, preview(result.substring(result.indexOf("\n\n") + 2)),
                            legacyFailure));
                    if (child.agent == null) children.remove(child.id, child);
                    if (!closed && child.agent != null) for (String id : reaper.onIdle(child.id, preview(result))) {
                        Child old = children.remove(id);
                        if (old != null) evicted.add(old);
                    }

                }
                for (Child old : evicted) {
                    try {
                        Execution previous;
                        synchronized (lock) { previous = old.execution; }
                        if (previous != null) previous.await();
                    }
                    catch (RuntimeException | Error e) { failure = collect(failure, e); }
                    try { if (old.agent != null) old.agent.close(); }
                    catch (RuntimeException | Error e) { failure = collect(failure, e); }
                }
            } finally {
                synchronized (lock) {
                    if (detached && !closed) {
                        State owner = owners.get(child.parentId);
                        try { if (owner != null) owner.inbox.post(new TaskNotification(child.id, result)); }
                        catch (RuntimeException | Error e) { failure = collect(failure, e); }
                    }
                    cleanupFailure = failure;
                    if (failure != null) cleanupFailures.add(failure);
                    child.execution = null;
                    finished = true;
                    activeExecutions.remove(this);
                }
            }
        }

        void beginChildWait() {
            // Called under the session task lock; parallel foreground tools suspend this one lease only once.
            childWaiters++;
            if (permitHeld) { permitHeld = false; admission.release(); }
        }

        void endChildWait() {
            synchronized (lock) {
                childWaiters--;
                if (childWaiters != 0 || !acceptingPermit || token.isCancelled() || permitHeld || reacquiringPermit) return;
                // Other completed waiters rely on this single pending owner to restore the parent's lease.
                reacquiringPermit = true;
            }
            try {
                admission.acquire(child.depth);
                synchronized (lock) {
                    reacquiringPermit = false;
                    if (childWaiters == 0 && acceptingPermit && !token.isCancelled() && !permitHeld) permitHeld = true;
                    else admission.release();
                }
            } catch (InterruptedException e) {
                synchronized (lock) { reacquiringPermit = false; }
                Thread.currentThread().interrupt();
                cancelAndInterrupt();
            }
        }

        void cancelIfAttached() {
            synchronized (lock) { if (!detached) cancelAndInterrupt(); }
        }

        void cancelAndInterrupt() {
            try { token.cancel(); }
            finally { thread.interrupt(); }
        }

        void await() {
            boolean interrupted = false;
            Throwable failure = null;
            try {
                while (thread.isAlive()) {
                    try { thread.join(); }
                    catch (InterruptedException e) {
                        interrupted = true;
                        try { cancelAndInterrupt(); }
                        catch (RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (cleanupFailure != null) failure = collect(failure, cleanupFailure);
            rethrow(failure);
        }
    }

    private String command(String input) {
        String p = input == null ? "" : input.trim();
        if (p.isEmpty() || p.equalsIgnoreCase("list")) {
            synchronized (lock) {
                if (registry.isEmpty()) return "当前会话没有任务。主 Agent 用 spawn_agent 派生子任务后会出现在这里。";
                StringBuilder out = new StringBuilder("会话内任务（" + registry.size() + "）：\n");
                registry.forEach((id, task) -> {
                    String label = task.status == Status.RUNNING ? "运行中" : task.legacyFailure ? "失败" : "空闲·可续派";
                    out.append("  ").append(id).append("  [").append(label).append("]  ")
                            .append(task.description == null ? "" : task.description).append('\n');
                    if (!task.preview.isBlank()) out.append("      ").append(task.preview).append('\n');
                });
                return out.append("（/task <id> 看完整轨迹，/task cancel <id> 取消运行中的任务）").toString();
            }
        }
        if (p.equalsIgnoreCase("cancel")) return "用法：/task cancel <task_id>";
        if (p.regionMatches(true, 0, "cancel ", 0, 7)) {
            String id = p.substring(7).trim();
            Execution execution;
            synchronized (lock) {
                if (!registry.containsKey(id)) return "未找到任务 " + id + "（可能已完成或被回收）。用 /task 查看当前任务。";
                Child child = children.get(id);
                execution = child == null ? null : child.execution;
            }
            if (execution == null) return "任务 " + id + " 当前没有可取消的运行令牌（可能已完成）。";
            execution.cancelAndInterrupt();
            return "已请求取消任务 " + id + "（在回合边界停止）。";
        }
        return taskOutput(p);
    }

    private void close() {
        List<Child> retained;
        List<Execution> running;
        synchronized (lock) {
            closed = true;
            retained = List.copyOf(children.values());
            running = List.copyOf(activeExecutions);
        }
        Throwable failure = null;
        try { stopAndJoin(running); }
        catch (RuntimeException | Error e) { failure = e; }
        for (Child child : retained) {
            try { if (child.agent != null) child.agent.close(); }
            catch (RuntimeException | Error e) { failure = collect(failure, e); }
        }
        synchronized (lock) {
            for (Throwable cleanup : cleanupFailures) failure = collect(failure, cleanup);
            cleanupFailures.clear(); children.clear(); registry.clear(); owners.clear();
        }
        rethrow(failure);
    }

    private static void stopAndJoin(List<Execution> executions) {
        Throwable failure = null;
        for (Execution execution : executions) {
            try { if (execution.thread.isAlive()) execution.cancelAndInterrupt(); }
            catch (RuntimeException | Error e) { failure = collect(failure, e); }
        }
        for (Execution execution : executions) {
            try { execution.await(); }
            catch (RuntimeException | Error e) { failure = collect(failure, e); }
        }
        rethrow(failure);
    }

    private static String preview(String result) {
        String line = result.replace('\n', ' ').trim();
        return line.length() > 100 ? line.substring(0, 100) + "…" : line;
    }

    private static String status(String id, String label) { return "[" + id + " · " + label + "]"; }
    private static Throwable collect(Throwable failure, Throwable next) {
        if (failure == null) return next;
        if (failure != next) failure.addSuppressed(next);
        return failure;
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
