package com.cafecli.harness.workspace;

import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.tool.WorkspaceTools;
import com.cafecli.harness.config.WorkspaceConfig;
import java.nio.file.Path;

/** File-tool state is created afresh for every attached agent. */
public final class WorkspaceCapability implements Capability {
    private volatile WorkspaceTools workspace;
    private volatile Path projectRoot;
    private final java.util.function.Supplier<Path> auditDirectory;

    public WorkspaceCapability(Path projectRoot) {
        this(projectRoot, WorkspaceConfig.defaults());
    }

    public WorkspaceCapability(Path projectRoot, WorkspaceConfig config) {
        this(new WorkspaceTools(projectRoot, config.editFileDisabled(), config.commandTimeoutSeconds()));
    }

    public WorkspaceCapability(WorkspaceTools workspace) {
        this.workspace = workspace;
        this.projectRoot = workspace.projectRoot();
        this.auditDirectory = () -> projectRoot.resolve(".cafecli/audit");
    }

    public WorkspaceCapability(Path projectRoot, WorkspaceConfig config, Path auditDirectory) {
        this.workspace=new WorkspaceTools(projectRoot,config.editFileDisabled(),config.commandTimeoutSeconds());
        this.projectRoot=projectRoot;
        this.auditDirectory=() -> auditDirectory;
    }
    @Override public void attachSession(com.cafecli.harness.SessionRegistrar session) {
        session.onProjectChange(project -> { projectRoot=project; workspace=workspace.atRoot(project); });
        session.command(com.cafecli.harness.SessionCommand.control("/policy","查看安全策略状态",(context,args) -> {
            var bytes=new java.io.ByteArrayOutputStream();
            com.cafecli.policy.PolicyCommands.status(new java.io.PrintStream(bytes,false,java.nio.charset.StandardCharsets.UTF_8),
                    projectRoot.toString(),auditDirectory.get());
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        }));
    }
    @Override
    public void attachAgent(AgentScope agent) {
        WorkspaceTools captured=workspace;
        java.util.function.Supplier<WorkspaceTools> execution=com.cafecli.harness.AgentSession.MAIN_AGENT_ID.equals(agent.agentId())
                ? () -> workspace : () -> captured;
        captured.tools().forEach(tool -> agent.tool(LeafToolAdapter.workspace(tool,execution)));
    }
}
