package com.cafecli.harness.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HooksConfigTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"cafecli.,true", "paicli.,1", "cafecli.,YES", "paicli.,TrUe"})
    void currentAndLegacyPropertiesRetainTheExistingTruthySpellings(String prefix, String value) {
        var properties = new HashMap<String, String>();
        for (String event : new String[]{"pre_tool_use", "post_tool_use", "stop", "user_prompt_submit", "read_ledger", "finish_gate"}) {
            properties.put(prefix + "hook." + event + ".enabled", value);
        }
        properties.put(prefix + "hook.finish_gate.verify_regex", " local-test ");
        properties.put(prefix + "hook.finish_gate.block_cap", "3");
        var hooks = load(properties);
        assertTrue(hooks.preToolUse());
        assertTrue(hooks.postToolUse());
        assertTrue(hooks.stop());
        assertTrue(hooks.userPromptSubmit());
        assertTrue(hooks.readLedger());
        assertTrue(hooks.finishGate());
        assertEquals("local-test", hooks.verifyRegex());
        assertEquals(3, hooks.blockCap());
    }

    @Test void currentPresenceWinsOverLegacyAndAllEventsAreOffByDefault() {
        var hooks = load(Map.of("paicli.hook.pre_tool_use.enabled", "true", "cafecli.hook.pre_tool_use.enabled", "",
                "paicli.hook.stop.enabled", "true", "cafecli.hook.stop.enabled", "false",
                "cafecli.hook.finish_gate.block_cap", "0"));
        assertFalse(hooks.preToolUse());
        assertFalse(hooks.postToolUse());
        assertFalse(hooks.stop());
        assertFalse(hooks.userPromptSubmit());
        assertFalse(hooks.readLedger());
        assertFalse(hooks.finishGate());
        assertEquals(2, hooks.blockCap());
    }

    private HooksConfig load(Map<String, String> properties) {
        return ConfigLoader.load(new ConfigSources(properties, Map.of(), root.resolve("home"), root)).hooks();
    }
}
