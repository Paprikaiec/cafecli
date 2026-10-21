package com.cafecli.harness.context;

import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** A typed custom kind: its JSON id, Jackson-serializable class, and pure model presentation. */
public record MessageKind<T extends CustomEntry>(String id, Class<T> type,
                                                Function<T, Optional<UserMessage>> presentation) {
    public MessageKind {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("kind id must not be blank");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(presentation, "presentation");
    }

    public static <T extends CustomEntry> MessageKind<T> tagged(String id, Class<T> type, String tag,
                                                               Function<T, String> text) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(text, "text");
        return new MessageKind<>(id, type, entry -> Optional.of(UserMessage.text(tag + "\n" + text.apply(entry))));
    }

    public static <T extends CustomEntry> MessageKind<T> hidden(String id, Class<T> type) {
        return new MessageKind<>(id, type, entry -> Optional.empty());
    }

    Optional<UserMessage> present(CustomEntry entry) {
        return Objects.requireNonNull(presentation.apply(type.cast(entry)), "presentation");
    }
}
