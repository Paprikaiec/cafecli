package com.cafecli.harness.web;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;
/** One completed run-start search, stored once and replayed through pure presentation. */
public record Freshness(String text) implements CustomEntry {
    public static final MessageKind<Freshness> KIND = new MessageKind<>("freshness", Freshness.class, entry ->
            java.util.Optional.of(com.cafecli.ai.message.UserMessage.text(entry.text())));
}
