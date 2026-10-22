package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentEvent;
import com.cafecli.harness.config.*;
import com.cafecli.prompt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentResourceLifecycleTest {
    private static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
    @TempDir Path root;

    @Test void failedAgentAssemblyClosesAllPartialResourcesInReverseOrderAndPreservesTheFailure() {
        var order = new ArrayList<Integer>();
        var primary = new IllegalArgumentException("attach failed");
        var cleanup = new AssertionError("cleanup failed");
        Capability broken = new Capability() { @Override public void attachAgent(AgentScope agent) {
            agent.onClose(() -> order.add(1));
            agent.onClose(() -> { order.add(2); throw cleanup; });
            agent.onClose(() -> order.add(3));
            throw primary;
        }};
        assertSame(primary, assertThrows(IllegalArgumentException.class,
                () -> factory(List.of(broken)).create("failed", profile(List.of(broken)))));
        assertEquals(List.of(3, 2, 1), order);
        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
    }

    @Test void closingARunningAgentIsRejectedAndIdleCloseReleasesResourcesExactlyOnce() {
        AtomicInteger closed = new AtomicInteger();
        Capability resource = new Capability() { @Override public void attachAgent(AgentScope agent) {
            agent.onClose(closed::incrementAndGet);
        }};
        Agent agent = factory(List.of(resource)).create("one", profile(List.of(resource)));
        agent.subscribe(event -> { if (event instanceof AgentEvent.RunStart) assertThrows(IllegalStateException.class, agent::close); });
        agent.run(UserMessage.text("go"), new CancellationToken());
        assertEquals(0, closed.get());
        agent.close(); agent.close();
        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, () -> agent.run(UserMessage.text("again"), new CancellationToken()));
    }

    @Test void clearFailureBeforeInstallationClosesReplacementAndKeepsOldAgentUsable() {
        var primary = new IllegalStateException("clear failed");
        var cleanup = new AssertionError("replacement cleanup failed");
        var attached = new AtomicInteger();
        var closed = new ArrayList<Integer>();
        Capability resource = new Capability() {
            @Override public void attachSession(SessionRegistrar session) { session.onClear(() -> { throw primary; }); }
            @Override public void attachAgent(AgentScope agent) {
                int generation = attached.incrementAndGet();
                agent.onClose(() -> { closed.add(generation); if (generation == 2) throw cleanup; });
            }
        };
        try (var session = session(resource)) {
            session.submit("before");
            assertSame(primary, assertThrows(IllegalStateException.class, session::clear));
            assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
            assertEquals(List.of(2), closed);
            session.submit("after");
            assertEquals(4, session.log().size());
        }
        assertEquals(List.of(2, 1), closed);
    }

    @Test void oldAgentCleanupFailureLeavesTheFreshAgentInstalledAndUsable() {
        var attached = new AtomicInteger();
        var failure = new AssertionError("old cleanup failed");
        Capability resource = new Capability() { @Override public void attachAgent(AgentScope agent) {
            if (attached.incrementAndGet() == 1) agent.onClose(() -> { throw failure; });
        }};
        try (var session = session(resource)) {
            session.submit("before");
            assertSame(failure, assertThrows(AssertionError.class, session::clear));
            session.submit("after");
            assertEquals(2, session.log().size());
        }
    }

    private AgentFactory factory(List<Capability> capabilities) {
        return new AgentFactory(FakeProvider.responding(request -> FakeResponse.text("done")),
                new PromptAssembler(new PromptRepository(root.resolve("user"), root.resolve("project"))),
                capabilities, new SessionEvents(), Duration.ofSeconds(90));
    }
    private AgentProfile profile(List<Capability> capabilities) { return new AgentProfile(MODEL, PromptMode.AGENT, capabilities, 5); }
    private AgentSession session(Capability capability) {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root.resolve("project")));
        return AgentSession.builder().config(config).provider(FakeProvider.responding(request -> FakeResponse.text("done")))
                .model(MODEL).capabilities(List.of(capability)).build();
    }
}
