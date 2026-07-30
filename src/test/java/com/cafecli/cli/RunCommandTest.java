package com.cafecli.cli;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RunCommandTest {

    @Test
    void parsesFileAndTraceFile() {
        RunCommand cmd = RunCommand.parse(
                new String[]{"run", "--file", "/tmp/issue.md", "--trace-file", "/tmp/t.jsonl"});
        assertEquals(Path.of("/tmp/issue.md"), cmd.issueFile());
        assertEquals(Path.of("/tmp/t.jsonl"), cmd.traceFile());
    }

    @Test
    void traceFileIsOptional() {
        RunCommand cmd = RunCommand.parse(new String[]{"run", "--file", "/tmp/issue.md"});
        assertEquals(Path.of("/tmp/issue.md"), cmd.issueFile());
        assertNull(cmd.traceFile());
    }

    @Test
    void missingFileThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parse(new String[]{"run", "--trace-file", "/tmp/t.jsonl"}));
    }

    @Test
    void danglingFileFlagThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parse(new String[]{"run", "--file"}));
    }

    @Test
    void strategyDefaultsToReact() {
        RunCommand cmd = RunCommand.parse(new String[]{"run", "--file", "/tmp/issue.md"});
        assertEquals("react", cmd.strategy());
    }

    @Test
    void acceptsExplicitReactStrategyInBothForms() {
        assertEquals("react", RunCommand.parse(
                new String[]{"run", "--file", "/tmp/issue.md", "--strategy=react"}).strategy());
        assertEquals("react", RunCommand.parse(
                new String[]{"run", "--file", "/tmp/issue.md", "--strategy", "react"}).strategy());
    }

    @Test
    void rejectsDeterministicOrchestrationStrategy() {
        // /team 确定性编排已退役（ADR 0004）：无人值守只允许 react 自主模式。
        assertThrows(IllegalArgumentException.class, () -> RunCommand.parse(
                new String[]{"run", "--file", "/tmp/issue.md", "--strategy=team"}));
    }
}
