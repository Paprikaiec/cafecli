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
    private volatile SnapshotService service;
    private final java.util.List<SnapshotService> owned=new java.util.ArrayList<>();
    private final java.util.function.Function<java.nio.file.Path,SnapshotService> projectFactory;
    private final Consumer<String> warning;

    public SnapshotCapability(SnapshotService service, Consumer<String> warning) {
        this(service,warning,null);
    }
    public SnapshotCapability(SnapshotService service,Consumer<String> warning,
                              java.util.function.Function<java.nio.file.Path,SnapshotService> projectFactory) {
        this.service = Objects.requireNonNull(service);
        this.projectFactory=projectFactory;
        owned.add(service);
        this.warning = Objects.requireNonNull(warning);
    }

    @Override public void attachSession(SessionRegistrar session) {
        session.onProjectChange(project -> {
            if (projectFactory==null) return;
            try { drain(service); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("snapshot drain failed",failure); }
            var replacement=projectFactory.apply(project);
            try { service.close(); }
            catch (RuntimeException | Error failure) {
                try { replacement.close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup!=failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
            owned.remove(service);
            owned.add(replacement);
            service=replacement;
            replacement.hijackWarning().ifPresent(warning);
        });
        session.command(new SessionCommand("/snapshot", "查看与管理 Side-Git 快照", (context, input) -> new SnapshotCommands(service).snapshot(input)));
        session.command(new SessionCommand("/restore", "恢复 pre-turn 快照", (context, input) -> new SnapshotCommands(service).restore(input)));
        service.hijackWarning().ifPresent(warning);
    }

    @Override public void attachAgent(AgentScope agent) {
        SnapshotService captured=service;
        java.util.function.Supplier<SnapshotService> current=AgentSession.MAIN_AGENT_ID.equals(agent.agentId()) ? () -> service : () -> captured;
        var definition = SnapshotTools.revertTurn(current);
        agent.tool(new LeafToolAdapter(definition));
        if (!AgentSession.MAIN_AGENT_ID.equals(agent.agentId())) return;
        var turn = new Turn();
        agent.runStart((input, token) -> turn.start(input));
        agent.runEnd(turn::finish);
    }

    private final class Turn {
        private String id;
        private String summary;
        private SnapshotService runService;

        void start(UserMessage input) {
            runService=service;
            id = "react-" + Instant.now().toEpochMilli();
            String text = input.content().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                    .map(TextBlock::text).collect(Collectors.joining("\n")).replaceAll("\\s+", " ").trim();
            if (text.length() > 120) text = text.substring(0, 120) + "...";
            summary = "mode=react\ninput=" + text;
            runService.snapshotBeforeTurn(id, summary);
        }

        void finish() {
            if (id == null) return;
            String completed = id;
            id = null;
            runService.snapshotAfterTurnAsync(completed, summary);
        }
    }

    /** Accepted post-turn writes belong to their old project even when the main project changes. */
    private static void drain(SnapshotService service) throws Exception {
        boolean interrupted=false;
        try {
            for (;;) {
                try { service.awaitIdle(); return; }
                catch (InterruptedException ended) { interrupted=true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override public void close() throws Exception {
        Throwable failure=null;
        for (SnapshotService ownedService : owned) {
            try { drain(ownedService); }
            catch (Throwable problem) {
                if (failure==null) failure=problem;
                else if (failure!=problem) failure.addSuppressed(problem);
            }
            try { ownedService.close(); }
            catch (Throwable problem) {
                if (failure==null) failure=problem;
                else if (failure!=problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
