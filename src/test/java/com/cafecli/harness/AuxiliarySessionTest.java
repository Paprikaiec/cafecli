package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.ToolInvoker;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuxiliarySessionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"preflight", "completion"})
    @org.junit.jupiter.api.Timeout(10)
    void auxiliaryCallbackFailuresSettleTheInvocationAndReleaseCommandAdmission(String phase) {
        var owner = Thread.currentThread();
        Capability capability = new Capability() {
            public void attachSession(SessionRegistrar session) {
                session.command(new SessionCommand("/aux", "fixture", (context, arguments) ->
                        context.invokeCommandTool("leaf", JSON.createObjectNode()).status().name()));
            }
            public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("leaf", "fixture", JSON.createObjectNode(), args -> ToolOutput.text("done"))));
                agent.beforeToolCall((call, token) -> {
                    assertSame(owner, Thread.currentThread());
                    if (phase.equals("preflight")) throw new IllegalArgumentException("fixture preflight failure");
                    return com.cafecli.core.ToolPreflight.allow(call);
                });
                agent.afterAuxiliaryToolCall(completion -> {
                    assertSame(owner, Thread.currentThread());
                    if (phase.equals("completion")) throw new IllegalArgumentException("fixture completion failure");
                });
            }
        };
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("done"))), new SessionCapability(), capability)) {
            if (phase.equals("preflight")) assertEquals("ERROR", session.dispatch("/aux").orElseThrow());
            else assertThrows(RuntimeException.class, () -> session.dispatch("/aux"));
            assertTrue(session.log().isEmpty());
            assertTrue(session.dispatch("/clear").isPresent());
            session.submit("next run");
        }
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void sessionCloseCancelsAnAuxiliaryCommandBeforeClosingItsOwnedResources() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var resourceClosed = new java.util.concurrent.atomic.AtomicBoolean();
        Capability capability = new Capability() {
            public void attachSession(SessionRegistrar session) {
                session.onClose(() -> resourceClosed.set(true));
                session.command(new SessionCommand("/aux", "fixture", (context, arguments) ->
                        context.invokeCommandTool("leaf", JSON.createObjectNode()).status().name()));
            }
            public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("leaf", "fixture", JSON.createObjectNode(), args -> {
                    started.countDown();
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return ToolOutput.text("cancelled worker");
                })));
            }
        };
        var session = session(FakeProvider.scripted(List.of(FakeResponse.text("unused"))), capability);
        try {
            var command = new java.util.concurrent.FutureTask<>(() -> session.dispatch("/aux"));
            Thread.ofVirtual().start(command);
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            session.close();
            assertEquals("CANCELLED", command.get(3, java.util.concurrent.TimeUnit.SECONDS).orElseThrow());
            assertTrue(resourceClosed.get());
            assertThrows(IllegalStateException.class, () -> session.dispatch("/aux"));
        } finally { session.close(); }
    }

    @Test void aRunStartWriterCannotLendItsPortToALaterWriter() {
        var retained = new AtomicReference<ToolInvoker>();
        Capability capability = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.runStartWriter((input, token, invoker) -> { retained.set(invoker); return List.of(); });
                agent.runStartWriter((input, token, invoker) -> {
                    assertThrows(IllegalStateException.class, () -> retained.get().invoke("leaf", JSON.createObjectNode()));
                    return List.of();
                });
            }
        };
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("done"))), capability)) {
            session.submit("hello");
        }
    }

    @Test void cancellationDuringAuxiliaryPreflightPreventsTheLeafFromStarting() {
        var executions = new AtomicInteger();
        Capability capability = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("leaf", "fixture", JSON.createObjectNode(), args -> {
                    executions.incrementAndGet(); return ToolOutput.text("too late");
                })));
                agent.beforeToolCall((call, token) -> {
                    token.cancel();
                    return com.cafecli.core.ToolPreflight.allow(call);
                });
                agent.runStartWriter((input, token, invoker) -> {
                    invoker.invoke("leaf", JSON.createObjectNode());
                    return List.of();
                });
            }
        };
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("unused"))), capability)) {
            session.submit("hello");
            assertEquals(0, executions.get(), "cancelled preflight must not release a late leaf");
        }
    }

    @Test void aRetainedRunStartPortCannotExecuteAfterItsWriterReturns() {
        var retained = new AtomicReference<ToolInvoker>();
        var executions = new AtomicInteger();
        Capability capability = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("leaf", "fixture", JSON.createObjectNode(), args -> {
                    executions.incrementAndGet(); return ToolOutput.text("unexpected");
                })));
                agent.runStartWriter((input, token, invoker) -> { retained.set(invoker); return List.of(); });
            }
        };
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("done"))), capability)) {
            session.submit("hello");
            assertThrows(IllegalStateException.class, () -> retained.get().invoke("leaf", JSON.createObjectNode()));
            assertEquals(0, executions.get());
        }
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void aRetainedWorkerPortExpiresWhenItsParentFinishesWhileASiblingStillRuns() throws Exception {
        var retained = new AtomicReference<ToolInvoker>();
        var executions = new AtomicInteger();
        var parentEnded = new java.util.concurrent.CountDownLatch(1);
        var releaseSibling = new java.util.concurrent.CountDownLatch(1);
        Capability capability = new Capability() {
            public void attachAgent(AgentScope agent) {
                agent.tool(new com.cafecli.core.AgentTool() {
                    public String name() { return "parent"; }
                    public String description() { return "fixture"; }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return JSON.createObjectNode(); }
                    public com.cafecli.core.ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input,
                            com.cafecli.ai.CancellationToken token, java.util.function.Consumer<com.cafecli.core.ToolResult> progress) {
                        throw new AssertionError("context required");
                    }
                    public com.cafecli.core.ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input,
                            com.cafecli.ai.CancellationToken token, java.util.function.Consumer<com.cafecli.core.ToolResult> progress, ToolInvoker invoker) {
                        retained.set(invoker); return LeafToolAdapter.map(ToolOutput.text("parent done"));
                    }
                });
                agent.tool(new LeafToolAdapter(new Tool("sibling", "fixture", JSON.createObjectNode(), args -> {
                    try { assertTrue(releaseSibling.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return ToolOutput.text("done");
                })));
                agent.tool(new LeafToolAdapter(new Tool("leaf", "fixture", JSON.createObjectNode(), args -> {
                    executions.incrementAndGet(); return ToolOutput.text("unexpected");
                })));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("p", "parent", "{}")
                .toolCall("s", "sibling", "{}").build(), FakeResponse.text("done")));
        try (var session = session(provider, capability)) {
            session.subscribe(event -> {
                if (event instanceof com.cafecli.core.AgentEvent.ToolEnd end && end.result().message().toolCallId().equals("p")) parentEnded.countDown();
            });
            var run = new java.util.concurrent.FutureTask<>(() -> session.submit("hello"));
            Thread thread = Thread.ofVirtual().start(run);
            try {
                assertTrue(parentEnded.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> retained.get().invoke("leaf", JSON.createObjectNode()));
                assertEquals(0, executions.get());
            } finally { releaseSibling.countDown(); }
            run.get(5, java.util.concurrent.TimeUnit.SECONDS);
            thread.join();
        }
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void auxiliaryCommandsReleaseTheSessionLockAndCancelWithoutPublishingModelEntries() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        Capability capability = new Capability() {
            public void attachSession(SessionRegistrar session) {
                session.command(new SessionCommand("/aux", "fixture", (context, arguments) ->
                        context.invokeCommandTool("leaf", JSON.createObjectNode()).status().name()));
            }
            public void attachAgent(AgentScope agent) {
                agent.tool(new LeafToolAdapter(new Tool("leaf", "fixture", JSON.createObjectNode(), args -> {
                    started.countDown();
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return ToolOutput.text("late");
                })));
            }
        };
        try (var session = session(FakeProvider.scripted(List.of(FakeResponse.text("unused"))), new SessionCapability(), capability)) {
            var command = new java.util.concurrent.FutureTask<>(() -> session.dispatch("/aux"));
            Thread.ofVirtual().start(command);
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> session.submit("cannot steer a command"));
            session.cancel();
            assertEquals("CANCELLED", command.get(3, java.util.concurrent.TimeUnit.SECONDS).orElseThrow());
            session.awaitIdle();
            assertTrue(session.log().isEmpty());
            assertTrue(session.dispatch("/clear").isPresent());
            assertThrows(IllegalStateException.class, () -> session.invokeCommandTool("leaf", JSON.createObjectNode()));
        }
    }

    private AgentSession session(FakeProvider provider, Capability... capabilities) {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        return AgentSession.builder().provider(provider).model(MODEL).config(config).capabilities(List.of(capabilities)).build();
    }
}
