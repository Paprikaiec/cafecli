package com.cafecli.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    void filtersSecretsAndImagePayloads() {
        InputHistory history = new InputHistory();

        history.add("帮我读取 pom.xml");
        history.add("GLM_API_KEY=secret");
        history.add("Authorization: Bearer abc");
        history.add("/config provider freellmapi --api-key sk-secret --model auto");
        history.add("@image:data:image/png;base64," + "A".repeat(260));

        assertEquals(1, history.size());
        assertEquals("帮我读取 pom.xml", history.get(history.last()));
    }

}
