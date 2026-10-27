package com.cafecli.harness.trace;

import com.cafecli.ai.Model;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.UserContent;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Compatible trace records from immutable owner observations; source entries remain owned by the agent. */
public final class TraceCapability implements Capability, AutoCloseable {
    private final WriterOpener opener;
    private final Object writerLock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "cafecli-trace-writer");
        thread.setDaemon(true);
        return thread;
    });
    private boolean attached;
    private boolean closing;
    private volatile IOException failure;
    // The writer alone reads and changes this state.
    private long started;
    private boolean running;
    private long inputTokens;
    private long outputTokens;
    private long cachedTokens;
    private Snapshot completed;

    public TraceCapability(Path file) {
        Objects.requireNonNull(file);
        this.opener = () -> Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    public TraceCapability(WriterOpener opener) { this.opener = Objects.requireNonNull(opener); }

    @FunctionalInterface
    public interface WriterOpener { Writer open() throws IOException; }

    @Override public void attachSession(SessionRegistrar session) {
        synchronized (writerLock) {
            if (attached) throw new IllegalStateException("trace already belongs to a session");
            attached = true;
        }
        session.onClose(this);
        session.eventListener(event -> {
            if (!AgentSession.MAIN_AGENT_ID.equals(event.agentId())) return;
            if (event instanceof AgentEvent.RunStart || event instanceof AgentEvent.MessageEnd) {
                long observed = System.nanoTime();
                enqueue(() -> observe(event, observed));
            }
        });
    }

    @Override public void attachAgent(AgentScope agent) {
        if (!AgentSession.MAIN_AGENT_ID.equals(agent.agentId())) return;
        agent.runEnd(() -> {
            var view = new View(agent.cachedSystemPrompt(), agent.messages(), agent.model(), System.nanoTime());
            enqueue(() -> {
                if (!running) return;
                completed = new Snapshot(view, inputTokens, outputTokens, cachedTokens,
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0, view.finished() - started)));
                running = false;
            });
        });
    }

    private void observe(AgentEvent event, long observed) {
        if (event instanceof AgentEvent.RunStart) {
            started = observed;
            running = true;
            inputTokens = outputTokens = cachedTokens = 0;
        } else if (running && event instanceof AgentEvent.MessageEnd end
                && end.message().stopReason() != StopReason.ERROR && end.message().stopReason() != StopReason.ABORTED) {
            inputTokens += end.message().usage().inputTokens();
            outputTokens += end.message().usage().outputTokens();
            cachedTokens += end.message().usage().cachedInputTokens();
        }
    }

    private void enqueue(Runnable observation) {
        synchronized (writerLock) {
            if (!closing) writer.execute(observation);
        }
    }

    /** Opens lazily, after a run; a blocked or unreadable submission cannot truncate a pre-existing trace. */
    private void write() {
        if (completed == null) return;
        try (Writer out = opener.open()) {
            var json = new ObjectMapper();
            line(out, json, Map.of("type", "system", "text", completed.view().systemPrompt()));
            for (Message message : completed.view().messages()) line(out, json, record(message));
            var result = new LinkedHashMap<String, Object>();
            result.put("type", "result");
            result.put("provider", completed.view().model().provider());
            result.put("model", completed.view().model().id());
            result.put("wall_ms", completed.wallMillis());
            var tokens = new LinkedHashMap<String, Object>();
            tokens.put("in", completed.inputTokens());
            tokens.put("out", completed.outputTokens());
            tokens.put("cache", completed.cachedTokens());
            result.put("tokens", tokens);
            result.put("turns", completed.view().messages().stream().filter(AssistantMessage.class::isInstance).count());
            line(out, json, result);
        } catch (IOException e) { failure = e; }
    }

    private static void line(Writer out, ObjectMapper json, Map<String, Object> record) throws IOException {
        out.write(json.writeValueAsString(record));
        out.write('\n');
    }

    private static Map<String, Object> record(Message message) {
        var record = new LinkedHashMap<String, Object>();
        switch (message) {
            case UserMessage user -> {
                record.put("type", "user");
                record.put("text", userText(user.content()));
                long images = user.content().stream().filter(ImageBlock.class::isInstance).count();
                if (images != 0) record.put("image_parts", images);
            }
            case ToolResultMessage tool -> {
                record.put("type", "tool_result");
                record.put("tool_call_id", tool.toolCallId());
                record.put("content", text(tool.content()));
            }
            case AssistantMessage assistant -> {
                record.put("type", "assistant");
                if (!assistant.text().isBlank()) record.put("text", assistant.text());
                String reasoning = assistant.content().stream().filter(ThinkingBlock.class::isInstance)
                        .map(ThinkingBlock.class::cast).map(ThinkingBlock::thinking)
                        .collect(java.util.stream.Collectors.joining());
                if (!reasoning.isBlank()) record.put("reasoning", reasoning);
                if (!assistant.toolCalls().isEmpty()) record.put("tool_calls", assistant.toolCalls().stream().map(call -> {
                    var fields = new LinkedHashMap<String, Object>();
                    fields.put("id", call.id()); fields.put("name", call.name()); fields.put("arguments", call.arguments());
                    return fields;
                }).toList());
            }
        }
        return record;
    }

    private static String userText(List<UserContent> content) {
        if (content.size() == 1 && content.getFirst() instanceof TextBlock text) return text.text();
        var parts = new java.util.ArrayList<String>();
        for (UserContent block : content) if (block instanceof TextBlock text && !text.text().isBlank()) parts.add(text.text());
        long images = content.stream().filter(ImageBlock.class::isInstance).count();
        if (images != 0) parts.add("[已附加 " + images + " 张图片]");
        return String.join("\n\n", parts);
    }

    private static String text(List<UserContent> content) {
        return content.stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::text).collect(java.util.stream.Collectors.joining());
    }

    /** Drains accepted observations and file output even when a preceding resource interrupted the closing thread. */
    @Override public void close() {
        synchronized (writerLock) {
            if (!closing) {
                closing = true;
                writer.execute(this::write);
                writer.shutdown();
            }
        }
        boolean interrupted = false;
        try {
            while (!writer.isTerminated()) {
                try { writer.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (failure != null) throw new WriteFailure(failure);
    }

    public static final class WriteFailure extends RuntimeException {
        private WriteFailure(IOException cause) { super(cause.getMessage(), cause); }
    }

    private record View(String systemPrompt, List<Message> messages, Model model, long finished) {
        private View { messages = List.copyOf(messages); }
    }
    private record Snapshot(View view, long inputTokens, long outputTokens, long cachedTokens, long wallMillis) { }
}
