package com.cafecli.harness.context;

import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserContent;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.AgentLogJson;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.core.log.UserEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable per-agent declarations, shared by log serialization and model presentation. */
public final class MessageKindRegistry {
    private final List<MessageKind<?>> kinds;
    private final Map<Class<? extends CustomEntry>, MessageKind<?>> byType;
    private final AgentLogJson json;

    public MessageKindRegistry(List<MessageKind<?>> kinds) {
        this.kinds = List.copyOf(kinds);
        Map<String, Class<? extends CustomEntry>> types = new LinkedHashMap<>();
        Map<Class<? extends CustomEntry>, MessageKind<?>> declarations = new LinkedHashMap<>();
        for (MessageKind<?> kind : kinds) {
            if (types.putIfAbsent(kind.id(), kind.type()) != null) {
                throw new IllegalArgumentException("duplicate kind id: " + kind.id());
            }
            if (declarations.putIfAbsent(kind.type(), kind) != null) {
                throw new IllegalArgumentException("duplicate kind class: " + kind.type().getName());
            }
        }
        this.byType = Map.copyOf(declarations);
        this.json = new AgentLogJson(types);
    }

    public List<MessageKind<?>> kinds() { return kinds; }
    public AgentLogJson json() { return json; }

    public List<Message> convertToLlm(List<LogEntry> entries) {
        List<Message> messages = new ArrayList<>();
        int userIndex = -1;
        for (LogEntry entry : entries) {
            switch (entry) {
                case UserEntry user -> {
                    userIndex = messages.size();
                    messages.add(user.message());
                }
                case AssistantEntry assistant -> messages.add(assistant.message());
                case ToolResultEntry result -> messages.add(result.message());
                case CustomEntry custom -> {
                    MessageKind<?> kind = byType.get(custom.getClass());
                    if (kind == null) throw new IllegalArgumentException("unregistered kind: " + custom.getClass().getName());
                    var presented = kind.present(custom);
                    if (kind.userPrefix() && presented.isPresent()) {
                        if (userIndex < 0) throw new IllegalArgumentException("user prefix requires a preceding user entry");
                        var user = (UserMessage) messages.get(userIndex);
                        List<UserContent> content = new ArrayList<>(presented.get().content());
                        // Join adjacent text blocks so the provider sees v1's exact single-message bytes.
                        for (UserContent block : user.content()) {
                            if (!content.isEmpty() && content.getLast() instanceof TextBlock prefix
                                    && block instanceof TextBlock text) {
                                content.set(content.size() - 1, new TextBlock(prefix.text() + text.text()));
                            } else content.add(block);
                        }
                        messages.set(userIndex, new UserMessage(content));
                    } else presented.ifPresent(messages::add);
                }
            }
        }
        return List.copyOf(messages);
    }

    /** Rejects kinds that this agent's profile did not attach. */
    public void requireRegistered(CustomEntry entry) {
        if (!byType.containsKey(entry.getClass())) {
            throw new IllegalArgumentException("unregistered kind: " + entry.getClass().getName());
        }
    }
}
