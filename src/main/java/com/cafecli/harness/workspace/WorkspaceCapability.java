package com.cafecli.harness.workspace;

import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.tool.WorkspaceTools;
import java.nio.file.Path;

/** File-tool state is created afresh for every attached agent. */
public final class WorkspaceCapability implements Capability {
    private final Path projectRoot;

    public WorkspaceCapability(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public void attachAgent(AgentScope agent) {
        agent.tool(new LeafToolAdapter(WorkspaceTools.readFile(projectRoot)));
    }
}
