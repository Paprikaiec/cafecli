package com.cafecli.agent;

import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookDispatcher;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookProperties;
import com.cafecli.hook.HookTenant;
import com.cafecli.llm.GLMClient;
import com.cafecli.llm.LlmClient;
import com.cafecli.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [[转向消息]] on the S-D seam: a stubbed {@link LlmClient} driving the real event loop, with no
 * terminal anywhere (issue 10).
 *
 * <p>The whole value of a resident [[输入框]] is that a user can correct a turn that has gone wrong
 * instead of watching it run out. So what is asserted here is not "the message was stored" but
 * <b>what the model is asked next</b>: the correction has to be in the conversation the very next
 * time the model is called, marked as the user's, and it must not disturb the two injections that
 * already share that channel.
 *
 * <p>The steering is submitted from <em>inside</em> the stub's {@code chat}, which is the one place a
 * test can stand and know for certain that a turn is running. That makes every assertion here
 * deterministic without a single sleep.
 */
class AgentSteeringTest {

    /** How an injected steering message identifies itself in the conversation. */
    private static final String MARKER = "[用户转向 / 回合中提交]";

    // --- 1. it reaches the model before the next call, at the iteration boundary ------------------

    @Test
    void aMessageSteeredMidTurnIsInTheConversationBeforeTheNextLlmCall(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("notes.txt"), "some notes\n", StandardCharsets.UTF_8);
        Rig rig = new Rig(dir);
        rig.onCall(1, conversation -> {
            // The turn is running and about to use a tool: this is exactly when a user reacts.
            assertTrue(rig.session.steer("stop reading, just answer"),
                    "a running turn must accept a steering message");
            return readFile("notes.txt");
        });
        rig.onCall(2, conversation -> text("ok, answering"));

        rig.run("look at the notes");

        List<LlmClient.Message> second = rig.callMessages(2);
        assertTrue(second.stream()
                        .filter(message -> "user".equals(message.role()))
                        .anyMatch(message -> message.content().contains(MARKER)
                                && message.content().contains("stop reading, just answer")),
                "the steering message must be a clearly marked user message in the next request:\n"
                        + flatten(second));
        assertFalse(rig.callMessages(1).stream()
                        .anyMatch(message -> String.valueOf(message.content()).contains(MARKER)),
                "it cannot be in a request that was already on the wire when it was submitted");
    }

    /**
     * The shared channel keeps working. LSP diagnostics and PostToolUse hook context are injected at
     * the same boundary; a third sibling that displaced either of them would be a regression nobody
     * would notice until a diagnostic went missing.
     */
    @Test
    void steeringDoesNotDisplaceTheLspDiagnosticsOnTheSameChannel(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir);
        rig.onCall(1, conversation -> {
            assertTrue(rig.session.steer("also, keep it short"));
            return writeFile("Broken.java", "class Broken {");
        });
        rig.onCall(2, conversation -> text("seen both"));

        rig.run("write a java file with a syntax error");

        String second = flatten(rig.callMessages(2));
        assertTrue(second.contains("[LSP 诊断注入]") && second.contains("Broken.java"),
                "LSP diagnostics must still be injected at the boundary:\n" + second);
        assertTrue(second.contains(MARKER) && second.contains("also, keep it short"),
                "the steering message must be injected at the same boundary:\n" + second);
    }

    /**
     * The other sibling on the same channel, asserted in the same turn rather than by regression: a
     * PostToolUse tenant's {@code additionalContext} and a steering message are injected side by side.
     */
    @Test
    void steeringDoesNotDisplaceThePostToolUseHookContextEither(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("notes.txt"), "some notes\n", StandardCharsets.UTF_8);
        System.setProperty(HookProperties.POST_TOOL_USE_ENABLED, "true");
        try {
            HookDispatcher hooks = new HookDispatcher();
            hooks.register(HookEvent.POST_TOOL_USE, new HookTenant() {
                @Override
                public String name() {
                    return "context-adding-tenant";
                }

                @Override
                public HookDecision evaluate(HookPayload payload) {
                    return HookDecision.cont().withAdditionalContext("HOOK SAYS REMEMBER THIS");
                }
            });
            Rig rig = new Rig(dir, hooks);
            rig.onCall(1, conversation -> {
                assertTrue(rig.session.steer("and keep it short"));
                return readFile("notes.txt");
            });
            rig.onCall(2, conversation -> text("seen both"));

            rig.run("read the notes");

            String second = flatten(rig.callMessages(2));
            assertTrue(second.contains("[Hook 上下文 / PostToolUse]")
                            && second.contains("HOOK SAYS REMEMBER THIS"),
                    "the PostToolUse hook context must still be injected at the boundary:\n" + second);
            assertTrue(second.contains(MARKER) && second.contains("and keep it short"),
                    "the steering message must be injected at the same boundary:\n" + second);
        } finally {
            System.clearProperty(HookProperties.POST_TOOL_USE_ENABLED);
        }
    }

    /** Several corrections in one turn arrive in the order the user submitted them. */
    @Test
    void severalSteeringMessagesInOneTurnTakeEffectInSubmissionOrder(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("notes.txt"), "some notes\n", StandardCharsets.UTF_8);
        Rig rig = new Rig(dir);
        rig.onCall(1, conversation -> {
            assertTrue(rig.session.steer("FIRST correction"));
            assertTrue(rig.session.steer("SECOND correction"));
            return readFile("notes.txt");
        });
        rig.onCall(2, conversation -> text("done"));

        rig.run("go");

        String second = flatten(rig.callMessages(2));
        assertTrue(second.indexOf("FIRST correction") >= 0, second);
        assertTrue(second.indexOf("FIRST correction") < second.indexOf("SECOND correction"),
                "steering messages must reach the model in submission order:\n" + second);
    }

    // --- 2. the end-of-turn fallback --------------------------------------------------------------

    /**
     * The turn ends before the next iteration boundary — the model gave a final answer and asked for
     * no tools — so there is no boundary left to inject at. The message must become an ordinary
     * submission rather than disappearing: the user pressed Enter and was shown an echo, so silently
     * dropping it is worse than the swallowed keystroke this feature replaces.
     */
    @Test
    void aSteeringMessageThatNeverReachedABoundaryBecomesAnOrdinarySubmission(@TempDir Path dir)
            throws Exception {
        Rig rig = new Rig(dir);
        rig.onCall(1, conversation -> {
            assertTrue(rig.session.steer("actually, use Java 21 features"));
            return text("here is the answer");   // no tool calls: the turn ends right here
        });
        rig.onCall(2, conversation -> text("noted"));

        rig.run("write something");

        assertEquals(2, rig.calls(), "the undelivered steering must start a turn of its own");
        String second = flatten(rig.callMessages(2));
        assertTrue(second.contains("actually, use Java 21 features"),
                "the undelivered steering must be re-posted as an ordinary user submission:\n"
                        + second);
        assertTrue(rig.turnInputs.contains("actually, use Java 21 features"),
                "it must go through the event loop as a UserSubmit, not through a side door: "
                        + rig.turnInputs);
    }

    /** Between turns there is nothing to steer, so a submission takes the ordinary path unchanged. */
    @Test
    void thereIsNothingToSteerWhenNoTurnIsRunning(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir);
        rig.onCall(1, conversation -> text("hello back"));

        assertFalse(rig.session.steer("idle typing"),
                "an idle session must refuse the steering so the caller submits it normally");

        rig.run("hello");
        assertEquals(1, rig.calls());
        assertFalse(flatten(rig.callMessages(1)).contains("idle typing"),
                "a refused steering must not leak into the next turn");
    }

    // --- 3. discard on cancel ---------------------------------------------------------------------

    /**
     * ESC / Ctrl+C cancel the turn, and a correction aimed at a turn that no longer exists must go
     * with it. Otherwise the next thing the user types would arrive with a stale instruction attached
     * to it.
     */
    @Test
    void cancellingATurnDiscardsSteeringThatNeverTookEffect(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir);
        rig.onCall(1, conversation -> {
            assertTrue(rig.session.steer("this correction is abandoned"));
            rig.session.discardSteering();
            return text("finished anyway");
        });
        rig.onCall(2, conversation -> text("second turn"));

        rig.run("go");
        assertEquals(1, rig.calls(), "a discarded steering must not be re-posted as a submission");

        rig.run("a completely new request");
        assertFalse(flatten(rig.callMessages(2)).contains("this correction is abandoned"),
                "a discarded steering must not leak into the next turn:\n"
                        + flatten(rig.callMessages(2)));
    }

    // --- rig ---------------------------------------------------------------------------------------

    /** The event loop with a scripted model: no terminal, no threads of the test's own. */
    private static final class Rig {

        final AgentSession session;
        final List<String> turnInputs = new CopyOnWriteArrayList<>();
        private final ScriptedClient llm = new ScriptedClient();

        Rig(Path projectDir) {
            this(projectDir, null);
        }

        Rig(Path projectDir, HookDispatcher hooks) {
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.setProjectPath(projectDir.toString());
            if (hooks != null) {
                toolRegistry.setHookDispatcher(hooks);
            }
            Agent agent = new Agent(llm, toolRegistry);
            session = new AgentSession(agent, new TaskRegistry(), output -> { });
            session.setTurnRunner(input -> {
                turnInputs.add(input);
                return agent.run(input);
            });
            session.start();
        }

        void onCall(int call, Function<String, LlmClient.ChatResponse> response) {
            llm.script.put(call, response);
        }

        void run(String input) throws InterruptedException {
            session.postEvent(new AgentSession.UserSubmit(input));
            assertTrue(session.awaitIdle(Duration.ofSeconds(10)), "the turn must finish");
        }

        int calls() {
            return llm.messagesByCall.size();
        }

        List<LlmClient.Message> callMessages(int oneBased) {
            return llm.messagesByCall.get(oneBased - 1);
        }
    }

    private static final class ScriptedClient extends GLMClient {

        private final java.util.Map<Integer, Function<String, LlmClient.ChatResponse>> script =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final List<List<Message>> messagesByCall = new CopyOnWriteArrayList<>();

        private ScriptedClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            messagesByCall.add(List.copyOf(messages));
            Function<String, ChatResponse> scripted = script.get(messagesByCall.size());
            if (scripted == null) {
                throw new IOException("no scripted response for call " + messagesByCall.size());
            }
            return scripted.apply(flatten(messages));
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static LlmClient.ChatResponse readFile(String path) {
        return toolCall("read_file", "{\"path\":\"" + path + "\"}");
    }

    private static LlmClient.ChatResponse writeFile(String path, String content) {
        return toolCall("write_file",
                "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}");
    }

    private static LlmClient.ChatResponse toolCall(String name, String arguments) {
        return new LlmClient.ChatResponse("assistant", "", null,
                List.of(new LlmClient.ToolCall(name + "-1",
                        new LlmClient.ToolCall.Function(name, arguments))),
                100, 20);
    }

    private static String flatten(List<LlmClient.Message> messages) {
        List<String> lines = new ArrayList<>();
        for (LlmClient.Message message : messages) {
            lines.add((message.role() == null ? "" : message.role()) + ':'
                    + (message.content() == null ? "" : message.content()));
        }
        return String.join("\n", lines);
    }
}
