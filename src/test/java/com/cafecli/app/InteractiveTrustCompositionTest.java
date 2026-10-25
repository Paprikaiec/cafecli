package com.cafecli.app;

import com.cafecli.harness.SubmitResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.permission.ApprovalResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveTrustCompositionTest {
    @TempDir Path root;
    @Test void interactiveProjectHooksRequireActualCommandTrustAndChangedBytesRequireNewTrust() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path hooks = Files.createDirectories(root.resolve(".cafecli")).resolve("hooks.json");
        Files.writeString(root.resolve("script.json"),"{\"responses\":[{\"text\":\"untrusted hook skipped\"}]}");
        String command = "printf trusted-project >&2; exit 2";
        write(hooks,command);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider","fake","cafecli.fake.script","script.json",
                "cafecli.hook.user_prompt_submit.enabled","true"),Map.of(),home,root));
        var asks = new AtomicInteger();
        com.cafecli.hook.HookTrustConfirmer accept = (project,commands) -> {
            assertEquals(root,project); assertEquals(List.of(command),commands); asks.incrementAndGet(); return true;
        };
        try (var session = Composition.interactiveSession(config,ignored -> {},ignored -> {},(request,token) -> ApprovalResult.approve(),accept)) {
            assertEquals("trusted-project",assertInstanceOf(SubmitResult.Blocked.class,session.submit("hello")).reason());
            assertFalse(session.approvalEnabled(),"interactive HITL starts disabled as v1");
            session.setApprovalEnabled(true); assertTrue(session.status().approvalEnabled());
            session.setApprovalEnabled(false); assertFalse(session.status().approvalEnabled());
        }
        try (var session = Composition.interactiveSession(config,ignored -> {},ignored -> {},(request,token) -> ApprovalResult.approve(),
                (project,commands) -> { fail("fingerprint already trusted"); return false; })) {
            assertInstanceOf(SubmitResult.Blocked.class,session.submit("hello"));
        }
        String changed = "printf changed-project >&2; exit 2";
        write(hooks,changed);
        try (var session = Composition.interactiveSession(config,ignored -> {},ignored -> {},(request,token) -> ApprovalResult.approve(),
                (project,commands) -> { assertEquals(List.of(changed),commands); asks.incrementAndGet(); return false; })) {
            assertInstanceOf(SubmitResult.Queued.class,session.submit("hello")); session.awaitIdle();
        }
        assertEquals(2,asks.get());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"no","ctrlC","eof"})
    void explicitDeclineRetainsUserHooksButAnInterruptedProjectPromptSkipsTheWholeShellHookLoad(String answer) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path userHooks = Files.createDirectories(home.resolve(".cafecli")).resolve("hooks.json");
        Path projectHooks = Files.createDirectories(root.resolve(".cafecli")).resolve("hooks.json");
        Path userMarker = root.resolve("user-hook-ran");
        Path projectMarker = root.resolve("project-hook-ran");
        write(userHooks,"printf user >> '" + userMarker + "'");
        write(projectHooks,"printf project >> '" + projectMarker + "'");
        Files.writeString(root.resolve("script.json"),"{\"responses\":[{\"text\":\"normal answer\"}]}");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider","fake","cafecli.fake.script","script.json",
                "cafecli.hook.user_prompt_submit.enabled","true"),Map.of(),home,root));
        com.cafecli.hook.HookTrustConfirmer confirmer = (project,commands) -> {
            if (answer.equals("ctrlC")) throw new org.jline.reader.UserInterruptException("");
            if (answer.equals("eof")) throw new org.jline.reader.EndOfFileException();
            return false;
        };
        try (var session = Composition.interactiveSession(config,ignored -> {},ignored -> {},(request,token) -> ApprovalResult.approve(),confirmer)) {
            assertInstanceOf(SubmitResult.Queued.class,session.submit("run")); session.awaitIdle();
            assertEquals(answer.equals("no"),Files.exists(userMarker),"only an explicit decline retains already parsed user hooks");
            assertFalse(Files.exists(projectMarker));
            assertFalse(Files.exists(home.resolve(".cafecli/hook-trust.json")),"none of these responses pins project trust");
        }
    }
    private static void write(Path hooks,String command) throws Exception {
        Files.writeString(hooks,new ObjectMapper().writeValueAsString(Map.of("UserPromptSubmit",List.of(
                Map.of("hooks",List.of(Map.of("type","command","command",command)))))));
    }
}
