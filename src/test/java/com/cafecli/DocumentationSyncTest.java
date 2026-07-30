package com.cafecli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationSyncTest {
    @Test
    void agentsToolListIncludesEditFile() throws Exception {
        String agents = Files.readString(Path.of("AGENTS.md"));

        assertTrue(agents.contains("核心内置工具 12 个"), "AGENTS.md 应同步核心工具数量");
        assertTrue(agents.contains("`write_file` / `edit_file`"), "AGENTS.md 应在 write_file 旁列出 edit_file");
    }
}
