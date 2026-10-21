package com.cafecli.tool;

import com.cafecli.policy.PathGuard;
import java.nio.file.Path;

/** Leaf-side access to the existing file tools without constructing the v1 registry. */
public final class WorkspaceTools {
    private WorkspaceTools() {}

    public static Tool readFile(Path projectRoot) {
        PathGuard guard = new PathGuard(projectRoot.toString());
        ToolEnv env = new ToolEnv(() -> projectRoot.toString(), () -> guard, () -> (command, line) -> {},
                () -> (path, changes) -> {}, () -> null);
        return new FileTools(env).tools().stream().filter(tool -> tool.name().equals("read_file")).findFirst().orElseThrow();
    }
}
