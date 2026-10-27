package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.RoutingProvider;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.core.log.UserEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.config.TaskConfig;
import com.cafecli.harness.tasks.SubAgentsCapability;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptMode;
import com.cafecli.prompt.PromptRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveTasksSessionTest {
    @TempDir Path root;
    static final Model MAIN = new Model("main", "main", "fake", 128000, 8192, Set.of());
    static final Model CHILD = new Model("child", "child", "fake", 128000, 8192, Set.of());

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void detachedOrAutomaticallyBackgroundedTaskPushesWhileIdleAndSurvivesClear(boolean background) throws Exception {
        var childEntered = new CountDownLatch(1);
        var releaseChild = new CountDownLatch(1);
        var pushed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var parent = FakeProvider.responding(request -> calls.getAndIncrement() == 0
                ? FakeResponse.builder().toolCall("spawn", "spawn_agent", "{\"prompt\":\"work\",\"background\":" + background + "}").build()
                : FakeResponse.text("parent reply"));
        var child = FakeProvider.responding(request -> {
            childEntered.countDown(); await(releaseChild); return FakeResponse.text("child result");
        });
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var factory = new AgentFactory(new RoutingProvider(Map.of("main", parent, "child", child)),
                new PromptAssembler(new PromptRepository(root.resolve("user-prompts"), root.resolve("project-prompts"))), Duration.ofSeconds(90));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 10),
                new TaskConfig(false, 1, 8, 5, 8, 50));
        try (var session = AgentSession.builder().config(config).factory(factory).model(MAIN).interactive(true)
                .capabilities(List.of(tasks)).build()) {
            session.subscribe(event -> {
                if (event instanceof AgentEvent.RunEnd && event.agentId().equals("main") && parent.requests().size() == 3) pushed.countDown();
            });
            try {
                session.submit("delegate"); assertTrue(childEntered.await(5, TimeUnit.SECONDS));
                session.awaitIdle();
                assertEquals(2, parent.requests().size());
                assertTrue(session.dispatch("/task").orElseThrow().contains("task_1"));
                var output = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                        .findFirst().orElseThrow().message().content().toString();
                assertTrue(output.contains(background ? "已在后台启动" : "已自动转入后台"));
                session.clear();
            } finally { releaseChild.countDown(); }
            assertTrue(pushed.await(5, TimeUnit.SECONDS)); session.awaitIdle();
            assertTrue(session.log().stream().noneMatch(UserEntry.class::isInstance));
            assertEquals(UserMessage.text("<task-notification task_id=\"task_1\">\n[task_1 · done]\n\nchild result\n</task-notification>"),
                    parent.requests().getLast().context().messages().getLast());
            assertTrue(session.dispatch("/task").orElseThrow().contains("空闲·可续派"));
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
