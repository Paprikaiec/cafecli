package com.paicli.render.inline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasoningPreviewTest {

    @Test
    void normalizesWhitespaceAndSplitCrlfAsDeltasArrive() {
        ReasoningPreview preview = new ReasoningPreview(4096);

        preview.append("  first   line  \r");
        preview.append("\n\tsecond");
        preview.append("  line  ");

        assertEquals(List.of("first line", "second line"), preview.lines());
    }

    @Test
    void expiresPreviewTextThatFallsOutsideTheSourceCharacterBound() {
        ReasoningPreview preview = new ReasoningPreview(4096);

        preview.append("expired");
        preview.append(" ".repeat(4096));
        assertEquals(List.of(), preview.lines());

        preview.append(" current");
        assertEquals(List.of("current"), preview.lines());
    }
}
