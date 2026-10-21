package com.cafecli.tool;

import com.cafecli.policy.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Existing workspace leaves, with execution-scoped cancellation and observers and no v1 registry. */
public final class WorkspaceTools {
    private final Path projectRoot;
    private final boolean editDisabled;
    private final long commandTimeoutSeconds;
    private final BiConsumer<String, String[]> writes;

    public WorkspaceTools(Path projectRoot, boolean editDisabled, long commandTimeoutSeconds) {
        this(projectRoot, editDisabled, commandTimeoutSeconds, (path, change) -> {});
    }

    /** The write observer runs inside the leaf's file mutation lock, after persistence. */
    public WorkspaceTools(Path projectRoot, boolean editDisabled, long commandTimeoutSeconds,
                          BiConsumer<String, String[]> writes) {
        this.projectRoot = projectRoot;
        this.editDisabled = editDisabled;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.writes = writes;
    }

    public List<Tool> tools() {
        return tools(() -> false, (command, line) -> {}, (path, change) -> {});
    }

    /** Each invocation owns its observers; command-reader helper threads never consult a shared current call. */
    public ToolOutput execute(String name, JsonNode input, BooleanSupplier cancelled,
                              BiConsumer<String, String> commandOutput, BiConsumer<String, String[]> changes)
            throws Exception {
        Tool tool = tools(cancelled, commandOutput, changes).stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
        return tool.executor().execute(input);
    }

    private List<Tool> tools(BooleanSupplier cancelled, BiConsumer<String, String> commandOutput,
                             BiConsumer<String, String[]> changes) {
        PathGuard guard = new PathGuard(projectRoot.toString());
        ToolEnv env = new ToolEnv(() -> projectRoot.toString(), () -> guard, () -> commandOutput,
                () -> (path, change) -> {
                    changes.accept(path, change);
                    writes.accept(path, change);
                }, () -> null);
        List<Tool> tools = new ArrayList<>(new FileTools(env, cancelled).tools(editDisabled));
        tools.addAll(new CommandTool(env, commandTimeoutSeconds).tools());
        new SearchTools(env).tools().stream().filter(tool -> !tool.name().equals("search_code")).forEach(tools::add);
        return List.copyOf(tools);
    }

    public static Tool readFile(Path projectRoot) {
        return new WorkspaceTools(projectRoot, false, 60).tools().stream()
                .filter(tool -> tool.name().equals("read_file")).findFirst().orElseThrow();
    }
}
