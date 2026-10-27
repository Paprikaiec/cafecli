package com.cafecli.harness.lsp;

import com.cafecli.core.ToolResult;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.ToolExecution;
import com.cafecli.harness.config.LspConfig;
import com.cafecli.lsp.LspManager;
import com.cafecli.policy.PathGuard;
import com.cafecli.policy.PolicyException;

import java.util.LinkedHashSet;

/** A shared parser with independent pending diagnostics for each agent. */
public final class LspCapability implements Capability {
    private final LspManager process;
    private volatile java.nio.file.Path projectRoot;
    private State main;
    private static final class State {
        final LspManager manager;
        PathGuard guard;
        State(LspManager manager,PathGuard guard) { this.manager=manager; this.guard=guard; }
    }

    public LspCapability(LspConfig config) {
        process = new LspManager(config.projectRoot().toString(), config.enabled(), config.maxDiagnostics());
        projectRoot=config.projectRoot();
    }

    @Override public void attachSession(com.cafecli.harness.SessionRegistrar session) {
        session.onProjectChange(project -> {
            projectRoot=project;
            process.setProjectPath(project.toString());
            if (main!=null) { main.manager.setProjectPath(project.toString()); main.guard=new PathGuard(project.toString()); }
        });
    }
    @Override public void attachAgent(AgentScope agent) {
        var state=new State(process.forkSharingProcess(),new PathGuard(projectRoot.toString()));
        if (com.cafecli.harness.AgentSession.MAIN_AGENT_ID.equals(agent.agentId())) main=state;
        var manager=state.manager;
        var inbox = agent.inbox();
        agent.messageKind(LspDiagnostics.KIND);
        agent.onClose(manager::flushPendingDiagnostics);
        agent.toolBatchResults(results -> {
            var paths = new LinkedHashSet<String>();
            for (var result : results) {
                String name = result.message().toolName();
                if (result.status() != ToolResult.Status.OK
                        || !("write_file".equals(name) || "edit_file".equals(name))) continue;
                var details = result.details();
                var execution = ToolExecution.from(new ToolResult(result.message().content(), details, result.status()));
                if (execution == null || execution.kind() != ToolExecution.Kind.RETURNED) continue;
                if (details != null && details.path("diff").path("path").isTextual()) {
                    paths.add(details.path("diff").path("path").asText());
                }
            }
            // Read the final file once per batch; the next boundary drains this tagged report.
            for (String path : paths) {
                try { manager.runPostEditLspHook(path, state.guard.resolveSafe(path)); }
                catch (PolicyException ignored) { /* A sibling may have replaced the path with an escaping link. */ }
            }
            var report = manager.flushPendingDiagnostics();
            if (!report.isEmpty()) inbox.post(new LspDiagnostics(report.promptText()));
            return results;
        });
    }
}
