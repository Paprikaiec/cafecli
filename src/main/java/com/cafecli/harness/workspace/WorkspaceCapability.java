package com.cafecli.harness.workspace;

import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.tool.WorkspaceTools;
import com.cafecli.harness.config.WorkspaceConfig;
import java.nio.file.Path;

/** File-tool state is created afresh for every attached agent. */
public final class WorkspaceCapability implements Capability {
    private final WorkspaceTools workspace;

    public WorkspaceCapability(Path projectRoot) {
        this(projectRoot, WorkspaceConfig.defaults());
    }

    public WorkspaceCapability(Path projectRoot, WorkspaceConfig config) {
        this(new WorkspaceTools(projectRoot, config.editFileDisabled(), config.commandTimeoutSeconds()));
    }

    public WorkspaceCapability(WorkspaceTools workspace) {
        this.workspace = workspace;
    }

    @Override
    public void attachAgent(AgentScope agent) {
        workspace.tools().forEach(tool -> agent.tool(new LeafToolAdapter(tool, workspace)));
    }
}
