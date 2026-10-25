package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistrySessionTest {
    @TempDir Path root;

    @Test void legacyGrammarRejectsExtraArgumentsAndTabsBeforeExecutingAnything() {
        var calls = new AtomicInteger();
        Capability commands = new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                for (String name : List.of("/clear", "/skill", "/mcp", "/memory", "/hitl", "/context"))
                    registry.command(new SessionCommand(name, name, (session, arguments) -> {
                        calls.incrementAndGet(); return arguments;
                    }));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session = session(provider, commands)) {
            for (String input : List.of("/clear extra", "/skill list extra", "/skill\tlist", "/skill show",
                    "/mcp restart", "/mcp\trestart server", "/memory clear extra", "/memory\tlist",
                    "/hitl  on", "/context extra"))
                assertEquals(java.util.Optional.empty(), session.dispatch(input), input);
            assertEquals(0, calls.get());
            assertTrue(provider.requests().isEmpty());
        }
    }

    @Test void hitlCommandChangesTheSamePermissionModeAsTheFacade() {
        var permissions = new com.cafecli.harness.permission.PermissionCapability((request,token) ->
                com.cafecli.harness.permission.ApprovalResult.approve(),false);
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("unused"))), permissions)) {
            assertFalse(session.approvalEnabled());
            assertTrue(session.dispatch("/hitl on").isPresent());
            assertTrue(permissions.isEnabled());
            assertTrue(session.status().approvalEnabled());
            session.setApprovalEnabled(false);
            assertTrue(session.dispatch("/hitl").orElseThrow().contains("关闭"));
            assertTrue(session.dispatch("/hitl  on").isEmpty());
        }
    }

    @Test void localCompletionUsesTheCapturedStartupDirectoryAndKeepsTypedRelativeValues() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("folder"));
        java.nio.file.Files.writeString(root.resolve("folder/note.txt"),"fixture");
        try (var session=session(FakeProvider.scripted(List.of(FakeResponse.text("unused"))),new SessionCapability())) {
            assertTrue(session.complete(new AgentSession.CompletionQuery("@folder/no","@folder/no",10))
                    .stream().anyMatch(item -> item.value().equals("@folder/note.txt")));
            assertTrue(session.complete(new AgentSession.CompletionQuery("@image:folder/no","@image:folder/no",16))
                    .stream().anyMatch(item -> item.value().equals("@image:folder/note.txt")));
        }
    }

    private AgentSession session(FakeProvider provider, Capability capability) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        return AgentSession.builder().config(config).provider(provider)
                .model(new Model("fake","fake","fake",128000,8192, Set.of()))
                .capabilities(List.of(capability)).build();
    }
}
