package com.cafecli.harness.snapshot;

import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.SessionCommand;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.snapshot.SnapshotService;
import com.cafecli.tool.SnapshotTools;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Session-owned snapshots, anchored only by the main agent. */
public final class SnapshotCapability implements Capability, AutoCloseable {
    private final SnapshotService service;
    private final Consumer<String> warning;

    public SnapshotCapability(SnapshotService service, Consumer<String> warning) {
        this.service = Objects.requireNonNull(service);
        this.warning = Objects.requireNonNull(warning);
    }

    @Override public void attachSession(SessionRegistrar session) {
        var commands = new SnapshotCommands(service);
        session.command(new SessionCommand("/snapshot", "查看与管理 Side-Git 快照", (context, input) -> commands.snapshot(input)));
        session.command(new SessionCommand("/restore", "恢复 pre-turn 快照", (context, input) -> commands.restore(input)));
        service.hijackWarning().ifPresent(warning);
    }

    @Override public void attachAgent(AgentScope agent) {
        var definition = SnapshotTools.revertTurn(() -> service);
        agent.tool(new LeafToolAdapter(definition));
        if (!AgentSession.MAIN_AGENT_ID.equals(agent.agentId())) return;
        var turn = new Turn();
        agent.runStart((input, token) -> turn.start(input));
        agent.runEnd(turn::finish);
    }

    private final class Turn {
        private String id;
        private String summary;

        void start(UserMessage input) {
            id = "react-" + Instant.now().toEpochMilli();
            String text = input.content().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                    .map(TextBlock::text).collect(Collectors.joining("\n")).replaceAll("\\s+", " ").trim();
            if (text.length() > 120) text = text.substring(0, 120) + "...";
            summary = "mode=react\ninput=" + text;
            service.snapshotBeforeTurn(id, summary);
        }

        void finish() {
            if (id == null) return;
            String completed = id;
            id = null;
            service.snapshotAfterTurnAsync(completed, summary);
        }
    }

    @Override public void close() throws Exception {
        boolean interrupted = false;
        Throwable failure = null;
        try {
            for (;;) {
                try { service.awaitIdle(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
        } catch (Throwable e) { failure = e; }
        try { service.close(); }
        catch (Throwable e) {
            if (failure == null) failure = e;
            else if (failure != e) failure.addSuppressed(e);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
