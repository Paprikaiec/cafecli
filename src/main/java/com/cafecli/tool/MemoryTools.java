package com.cafecli.tool;

import java.util.function.BiConsumer;

/** The existing save_memory leaf without constructing the v1 registry or unrelated services. */
public final class MemoryTools {
    private MemoryTools() { }
    public static Tool saveMemory(BiConsumer<String, String> saver) {
        return new SessionTools(() -> saver, () -> null, () -> null, () -> null).tools().stream()
                .filter(tool -> "save_memory".equals(tool.name())).findFirst().orElseThrow();
    }
}
