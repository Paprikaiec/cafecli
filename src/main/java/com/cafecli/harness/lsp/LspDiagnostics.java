package com.cafecli.harness.lsp;

import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;
import java.util.Optional;

/** The leaf formatter already supplies the exact tagged model text. */
public record LspDiagnostics(String text) implements CustomEntry {
    public static final MessageKind<LspDiagnostics> KIND = new MessageKind<>("lsp-diagnostics",
            LspDiagnostics.class, entry -> Optional.of(UserMessage.text(entry.text())));
}
