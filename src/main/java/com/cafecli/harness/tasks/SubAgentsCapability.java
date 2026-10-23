package com.cafecli.harness.tasks;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentTool;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.AgentFactory;
import com.cafecli.harness.AgentProfile;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.TaskSpawner;
import com.cafecli.tool.TaskTools;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Foreground children use the same factory and fresh capability state, with result-only parent presentation. */
public final class SubAgentsCapability implements Capability {
    private final AgentFactory factory;
    private final AgentProfile childProfile;
    private final AtomicInteger nextId = new AtomicInteger();

    public SubAgentsCapability(AgentFactory factory, AgentProfile childProfile) {
        this.factory = Objects.requireNonNull(factory);
        this.childProfile = Objects.requireNonNull(childProfile);
    }

    @Override public void attachAgent(AgentScope scope) {
        var state = new State();
        scope.runStart(state::start);
        scope.runEnd(state::finish);
        scope.onClose(state::close);
        var definition = new TaskTools(() -> null).tools().getFirst();
        scope.tool(new AgentTool() {
            @Override public String name() { return definition.name(); }
            @Override public String description() { return definition.description(); }
            @Override public JsonNode parameters() { return definition.parameters().deepCopy(); }
            @Override public ToolResult execute(String id, JsonNode input, CancellationToken token, Consumer<ToolResult> progress) {
                TaskSpawner spawner = new TaskSpawner() {
                    @Override public String spawn(String description, String prompt) { return state.spawn(prompt, token); }
                    @Override public String spawnBackground(String description, String prompt) { return spawn(description, prompt); }
                    @Override public String continueTask(String taskId, String message) { throw new UnsupportedOperationException(); }
                    @Override public String taskOutput(String taskId) { throw new UnsupportedOperationException(); }
                };
                return new LeafToolAdapter(new TaskTools(() -> spawner).tools().getFirst()).execute(id, input, token, progress);
            }
        });
    }

    private static final class Run {
        final CancellationToken parent;
        final List<Child> children = new ArrayList<>();
        Run(CancellationToken parent) { this.parent = parent; }
    }

    private final class State {
        private Run current;
        private boolean closed;

        synchronized void start(CancellationToken token) {
            if (closed || current != null) throw new IllegalStateException("sub-agent scope is unavailable");
            current = new Run(token);
        }

        String spawn(String prompt, CancellationToken parent) {
            Child child;
            synchronized (this) {
                // The explicit token also identifies the generation: abandoned workers cannot enter a newer run.
                if (closed || current == null || current.parent != parent || parent.isCancelled()) {
                    return "子任务已取消";
                }
                child = new Child("task_" + nextId.incrementAndGet(), prompt);
                current.children.add(child);
                child.thread = Thread.ofVirtual().name("cafecli-" + child.id).unstarted(child::run);
                child.link = parent.onCancel(child::cancelAndInterrupt);
                child.thread.start();
            }
            child.await();
            return child.result;
        }

        void finish() {
            List<Child> children;
            synchronized (this) {
                if (current == null) return;
                children = List.copyOf(current.children);
                current = null;
            }
            Throwable failure = null;
            for (Child child : children) {
                try {
                    if (child.thread.isAlive()) {
                        child.cancelAndInterrupt();
                    }
                } catch (RuntimeException | Error e) {
                    if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e);
                }
            }
            for (Child child : children) {
                try { child.await(); }
                catch (RuntimeException | Error e) {
                    if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e);
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        void close() {
            synchronized (this) { closed = true; }
            finish();
        }
    }

    private final class Child {
        final String id;
        final String prompt;
        final CancellationToken token = new CancellationToken();
        CancellationToken.Registration link;
        Thread thread;
        String result;

        Child(String id, String prompt) { this.id = id; this.prompt = prompt; }

        void run() {
            try (var ignored = link; var child = factory.create(id, childProfile)) {
                RunOutcome outcome = child.run(UserMessage.text(prompt), token);
                String text = com.cafecli.harness.RunOutcomeText.format(outcome);
                if (outcome instanceof RunOutcome.Completed && text.isBlank()) {
                    // The v1 streamed-answer fallback selects the last nonblank assistant text.
                    var log = child.log();
                    for (int i = log.size() - 1; i >= 0; i--) {
                        if (log.get(i) instanceof com.cafecli.core.log.AssistantEntry answer && answer.message().toolCalls().isEmpty() && !answer.message().text().isBlank()) {
                            text = answer.message().text();
                            break;
                        }
                    }
                }
                // Legacy task labels are presentation only; the RunOutcome and outer leaf status remain typed.
                String label = text.stripLeading().startsWith("❌") ? "failed" : "done";
                result = "[" + id + " · " + label + "]\n\n" + text;
            } catch (Throwable failure) {
                result = "[" + id + " · failed]\n\n子任务执行失败: " + failure.getMessage();
            }
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
                        catch (RuntimeException | Error cleanup) {
                            if (failure == null) failure = cleanup;
                            else if (failure != cleanup) failure.addSuppressed(cleanup);
                        }
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }
}
