package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptMode;
import com.cafecli.prompt.PromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The factory every agent is built through, with profiles other than the main agent's — which, in 04, only a test
 * builds: Capability Closure and per-agent state. (Seam 1 covers the main agent through the session.)
 */
class AgentFactoryTest {

    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128_000, 8_192, Set.of());

    @TempDir
    Path root;

    @Test
    void aCapabilityTheProfileDoesNotAttachContributesNothingToTheAgent() {
        NotingCapability attached = new NotingCapability("attached");
        NotingCapability unattached = new NotingCapability("unattached");
        AgentFactory factory = factory(List.of(attached, unattached));

        Agent agent = factory.create("child", profile(List.of(attached)));
        agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(List.of(new Note("attached in child")), notes(agent.log()));
        assertEquals(List.of("child"), attached.scopes);
        assertEquals(List.of(), unattached.scopes);
    }

    @Test
    void everyAgentGetsAFreshScopeAndTheHooksRunInTheSessionsDeclaredOrder() {
        NotingCapability first = new NotingCapability("first");
        NotingCapability second = new NotingCapability("second");
        AgentFactory factory = factory(List.of(first, second));

        // The profile lists them the other way round: declared order wins.
        Agent one = factory.create("one", profile(List.of(second, first)));
        Agent two = factory.create("two", profile(List.of(second, first)));
        one.run(UserMessage.text("go"), new CancellationToken());
        two.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(List.of(new Note("first in one"), new Note("second in one")), notes(one.log()));
        assertEquals(List.of(new Note("first in two"), new Note("second in two")), notes(two.log()));
        assertEquals(List.of("one", "two"), first.scopes);
    }

    @Test
    void aScopeKeptPastItsHookRefusesRegistrations() {
        List<AgentScope> kept = new ArrayList<>();
        Capability keeping = new Capability() {
            @Override
            public void attachAgent(AgentScope agent) {
                kept.add(agent);
            }
        };
        factory(List.of(keeping)).create("one", profile(List.of(keeping)));

        assertThrows(IllegalStateException.class,
                () -> kept.get(0).runStartWriter((input, cancellation) -> List.of()));
    }

    @Test
    void aProfileCannotAttachACapabilityTheSessionDoesNotHave() {
        AgentFactory factory = factory(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> factory.create("one", profile(List.of(new NotingCapability("stranger")))));
    }

    @Test
    void toolsFollowDeclaredCapabilityOrderRespectClosureAndHaveFreshAgentState() {
        Capability first = countingTool("first");
        Capability second = countingTool("second");
        Capability unattached = countingTool("absent");
        FakeProvider provider = FakeProvider.responding(request -> request.context().messages().stream()
                .anyMatch(ToolResultMessage.class::isInstance) ? FakeResponse.text("done")
                : FakeResponse.builder().toolCall("call", "first", "{}").build());
        PromptAssembler prompts = new PromptAssembler(
                new PromptRepository(root.resolve("user-prompts"), root.resolve("project-prompts")));
        AgentFactory factory = new AgentFactory(provider, prompts, List.of(first, second, unattached), new SessionEvents(), java.time.Duration.ofSeconds(90));
        Agent one = factory.create("one", profile(List.of(second, first)));
        Agent two = factory.create("two", profile(List.of(first, second)));

        one.run(UserMessage.text("go"), new CancellationToken());
        two.run(UserMessage.text("go"), new CancellationToken());

        for (var request : provider.requests()) {
            assertEquals(List.of("first", "second"), request.context().tools().stream()
                    .map(com.cafecli.ai.ToolDefinition::name).toList());
        }
        assertEquals(List.of(new TextBlock("1")), ((ToolResultEntry) one.log().get(2)).message().content());
        assertEquals(List.of(new TextBlock("1")), ((ToolResultEntry) two.log().get(2)).message().content());
    }

    private static Capability countingTool(String name) {
        return new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                AtomicInteger count = new AtomicInteger();
                scope.tool(new AgentTool() {
                    @Override public String name() { return name; }
                    @Override public String description() { return name; }
                    @Override public JsonNode parameters() { return JsonNodeFactory.instance.objectNode(); }
                    @Override public ToolResult execute(String id, JsonNode input, CancellationToken token,
                                                         Consumer<ToolResult> progress) {
                        return new ToolResult(List.of(new TextBlock(Integer.toString(count.incrementAndGet()))),
                                null, ToolResult.Status.OK);
                    }
                });
            }
        };
    }

    /** Notes, at run start, which capability saw which agent; remembers the scopes it was given. */
    private static final class NotingCapability implements Capability {
        private final String name;
        private final List<String> scopes = new ArrayList<>();

        NotingCapability(String name) {
            this.name = name;
        }

        @Override
        public void attachAgent(AgentScope agent) {
            scopes.add(agent.agentId());
            agent.runStartWriter((input, cancellation) -> List.of(new Note(name + " in " + agent.agentId())));
        }
    }

    record Note(String text) implements CustomEntry {
    }

    private static List<LogEntry> notes(List<LogEntry> log) {
        return log.stream().filter(Note.class::isInstance).toList();
    }

    private AgentFactory factory(List<Capability> capabilities) {
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.text("ok"));
        PromptAssembler prompts = new PromptAssembler(
                new PromptRepository(root.resolve("user-prompts"), root.resolve("project-prompts")));
        return new AgentFactory(provider, prompts, capabilities, new SessionEvents(), java.time.Duration.ofSeconds(90));
    }

    private static AgentProfile profile(List<Capability> capabilities) {
        return new AgentProfile(MODEL, PromptMode.AGENT, capabilities, 5);
    }
}
