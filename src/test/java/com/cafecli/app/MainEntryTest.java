package com.cafecli.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The packaged Main reaches the sole runtime in a fresh JVM with no migration flag. */
class MainEntryTest {
    @TempDir Path root;

    @Test void defaultEntryRunsHeadlessAndDrainsItsCompatibleTrace() throws Exception {
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[{"thinking":"default entry reasoning","text":"DEFAULT_ENTRY_OK",
                "usage":{"input":7,"output":3,"cached":2}}]}
                """);
        Files.writeString(root.resolve("issue.md"), "Answer directly without tools or network access.");

        int exit = launch("-Dcafecli.provider=fake", "-Dcafecli.fake.script=" + script,
                "run", "--file", "issue.md", "--trace-file", "trace.jsonl");

        assertEquals(0, exit, Files.readString(root.resolve("stderr")));
        String stdout = Files.readString(root.resolve("stdout"));
        assertTrue(stdout.contains("default entry reasoning"), stdout);
        assertTrue(stdout.contains("DEFAULT_ENTRY_OK"), stdout);
        var json = new ObjectMapper();
        var records = Files.readAllLines(root.resolve("trace.jsonl")).stream().map(line -> {
            try { return json.readTree(line); }
            catch (Exception failure) { throw new AssertionError("invalid trace record", failure); }
        }).toList();
        assertEquals("result", records.getLast().path("type").asText());
        assertEquals("fake", records.getLast().path("provider").asText());
        assertEquals(json.readTree("{\"in\":7,\"out\":3,\"cache\":2}"), records.getLast().path("tokens"));
    }

    @Test void removedHttpRuntimeCannotBeReachedThroughTheDefaultEntry() throws Exception {
        assertEquals(2, launch(null, null, "serve", "--http", "--port", "8080"));
        assertEquals("", Files.readString(root.resolve("stdout")));
        assertTrue(Files.readString(root.resolve("stderr")).contains("已移除 `serve --http`"));
    }

    @Test void entryAndResidentReplLeaveTerminalReadsToTheirOwners() throws Exception {
        for (String file : java.util.List.of("cli/Main.java", "ui/InteractiveRepl.java")) {
            String source = Files.readString(Path.of("src/main/java/com/cafecli").resolve(file));
            assertFalse(source.contains("terminal.reader()"), file + " must not consume terminal input directly");
            assertFalse(source.contains("enterRawMode()"), file + " must not enter raw mode directly");
        }
    }

    private int launch(String provider, String script, String... arguments) throws Exception {
        Path home = Files.createDirectories(root.resolve("home/.cafecli")).getParent();
        Files.writeString(home.resolve(".cafecli/mcp.json"), "{\"mcpServers\":{}}");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        command.add("-Duser.home=" + home);
        if (provider != null) command.add(provider);
        if (script != null) command.add(script);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add("com.cafecli.cli.Main");
        command.addAll(java.util.List.of(arguments));
        var builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(root.resolve("stdout").toFile()).redirectError(root.resolve("stderr").toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("CAFECLI_") || key.startsWith("PAICLI_")
                || key.endsWith("_API_KEY") || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "default Main did not exit");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
