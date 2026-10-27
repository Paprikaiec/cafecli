package com.cafecli.app;

import com.cafecli.harness.AgentSession;
import com.cafecli.harness.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandProjectScopeTest {
    @TempDir Path root;
    @Test @Timeout(30) void indexMovesTheMainWorkspaceMemoryLspAndDefaultSnapshotsWhileExistingChildrenKeepTheirRoots() throws Exception {
        Path project=Files.createDirectories(root.resolve("project"));
        Path next=Files.createDirectories(root.resolve("next"));
        Files.writeString(project.resolve("old-only.txt"),"startup file");
        Path script=root.resolve("script.json");
        Files.writeString(script,"""
                {"responses":[
                  {"toolCalls":[{"id":"spawn","name":"spawn_agent","arguments":{"description":"old child","prompt":"initial child"}}]},
                  {"toolCalls":[{"id":"oldwrite","name":"write_file","arguments":{"path":"old-child.txt","content":"old"}}]},
                  {"text":"child ready"}, {"text":"main ready"},
                  {"toolCalls":[{"id":"continue","name":"send_message","arguments":{"to":"task_1","message":"continue old child"}}]},
                  {"toolCalls":[{"id":"retained","name":"write_file","arguments":{"path":"retained-child.txt","content":"captured"}}]},
                  {"text":"retained ready"},
                  {"toolCalls":[{"id":"newspawn","name":"spawn_agent","arguments":{"description":"new child","prompt":"new child"}}]},
                  {"toolCalls":[{"id":"newwrite","name":"write_file","arguments":{"path":"new-child.txt","content":"new"}}]},
                  {"text":"new ready"},
                  {"toolCalls":[{"id":"mainwrite","name":"write_file","arguments":{"path":"Broken.java","content":"class Broken {"}}]},
                  {"text":"main moved"},
                  {"toolCalls":[{"id":"read","name":"read_file","arguments":{"path":"new-child.txt"}}]},
                  {"text":"after clear"}
                ]}
                """);
        var config=ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider","fake","cafecli.fake.script",script.toString(),
                "cafecli.snapshot.dir","../snapshots","cafecli.rag.dir","../vectors"),Map.of(),root.resolve("home"),project));
        try (var session=Composition.headlessSession(config)) {
            session.dispatch("/save OLD_SCOPE_ONLY").orElseThrow();
            session.submit("make a retained child");
            assertEquals("old",Files.readString(project.resolve("old-child.txt")));
            assertTrue(session.dispatch("/index ../next").orElseThrow().contains("索引完成"));
            assertTrue(session.dispatch("/policy").orElseThrow().contains(next.toString()));
            String snapshots=session.dispatch("/snapshot status").orElseThrow();
            assertTrue(snapshots.contains("项目根: "+next));
            assertTrue(snapshots.contains(root.resolve("snapshots").toString()));
            assertFalse(snapshots.contains(next.resolve("snapshots").toString()));
            assertTrue(session.complete(new AgentSession.CompletionQuery("@old-on","@old-on",7)).stream()
                    .anyMatch(item -> item.value().equals("@old-only.txt")),"completion keeps the captured startup CWD");
            session.dispatch("/save NEW_SCOPE_ONLY").orElseThrow();
            assertFalse(session.dispatch("/memory search OLD_SCOPE_ONLY").orElseThrow().contains("  OLD_SCOPE_ONLY"));
            assertTrue(session.dispatch("/memory search NEW_SCOPE_ONLY").orElseThrow().contains("  NEW_SCOPE_ONLY"));
            session.submit("continue old and spawn new then write main");
            assertEquals("captured",Files.readString(project.resolve("retained-child.txt")));
            assertFalse(Files.exists(next.resolve("retained-child.txt")));
            assertEquals("new",Files.readString(next.resolve("new-child.txt")));
            assertFalse(Files.exists(project.resolve("new-child.txt")));
            assertTrue(Files.exists(next.resolve("Broken.java")));
            assertTrue(session.log().stream().filter(com.cafecli.harness.lsp.LspDiagnostics.class::isInstance)
                    .map(com.cafecli.harness.lsp.LspDiagnostics.class::cast).anyMatch(entry -> entry.text().contains("Broken.java")),
                    "post-edit LSP resolves the new main root");
            assertTrue(session.dispatch("/search startup query").orElseThrow().contains("代码库尚未索引"),
                    "slash search still queries the captured startup project");
            session.dispatch("/clear").orElseThrow();
            assertTrue(session.log().isEmpty());
            assertTrue(session.dispatch("/task task_1").orElseThrow().contains("continue old child"));
            session.submit("read the new main root after clear");
            assertTrue(session.log().toString().contains("new"));
            assertTrue(session.dispatch("/memory").orElseThrow().contains(next.toString()));
        }
        assertTrue(Files.exists(root.resolve("vectors/codebase.db")));
        assertFalse(Files.exists(next.resolve("vectors")));
        assertFalse(new com.cafecli.snapshot.SideGitManager(next,config.snapshots()).listSnapshots(20).isEmpty());
    }
}
