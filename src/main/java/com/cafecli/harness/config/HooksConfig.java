package com.cafecli.harness.config;

import com.cafecli.hook.FinishGateTenant;
import java.nio.file.Path;
import java.util.Map;

/** Hook switches, paths and explicit shell interpolation sources, captured once by the config loader. */
public record HooksConfig(boolean preToolUse, boolean postToolUse, boolean stop, boolean userPromptSubmit,
                          boolean readLedger, boolean finishGate, String verifyRegex, int blockCap,
                          Map<String, String> properties, Map<String, String> environment, String userHome,
                          Path projectRoot, ConfigHomes homes) {
    public HooksConfig {
        properties = Map.copyOf(properties);
        environment = Map.copyOf(environment);
    }
    /** These captured sources may contain credentials; omit them from diagnostics. */
    @Override public String toString() {
        return "HooksConfig[preToolUse=" + preToolUse + ", postToolUse=" + postToolUse + ", stop=" + stop
                + ", userPromptSubmit=" + userPromptSubmit + ", readLedger=" + readLedger + ", finishGate=" + finishGate + "]";
    }
    public static HooksConfig disabled(Path projectRoot, ConfigHomes homes) {
        return new HooksConfig(false, false, false, false, false, false,
                FinishGateTenant.DEFAULT_VERIFY_REGEX, 2, Map.of(), Map.of(), "", projectRoot, homes);
    }
}
