package com.cafecli.llm;

import com.cafecli.image.ImagePart;
import com.cafecli.image.ImageReferenceParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds v1's user message from raw input: {@code @image:} / {@code @clipboard} references become image parts. */
public final class UserMessages {
    private UserMessages() {}

    /**
     * No images loaded → a plain-text message. Otherwise one text part followed by the image parts, so the image
     * block goes last.
     */
    public static LlmClient.Message fromInput(String input, Path baseDir) {
        ImageReferenceParser.ParsedInput parsed = ImageReferenceParser.parse(input, baseDir);
        if (parsed.images().isEmpty()) {
            return LlmClient.Message.user(parsed.text());
        }
        List<LlmClient.ContentPart> parts = new ArrayList<>();
        parts.add(LlmClient.ContentPart.text(parsed.text()));
        for (ImagePart image : parsed.images()) {
            parts.add(LlmClient.ContentPart.image(image));
        }
        return LlmClient.Message.user(parts);
    }
}
