package com.cafecli.app;

import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.lsp.LspDiagnostics;
import com.cafecli.harness.skills.SkillBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillsLspCompositionTest {
    @TempDir Path root;

    @Test void productionCompositionAttachesTheExistingSkillLeafAndPostWriteDiagnostics() throws Exception {
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[{"toolCalls":[
                  {"id":"load","name":"load_skill","arguments":{"name":"web-access"}},
                  {"id":"write","name":"write_file","arguments":{"path":"Broken.java","content":"class Broken {"}}
                ]},{"text":"done"},{"text":"used"}]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                "cafecli.fake.script", script.toString()), Map.of(), root.resolve("home"), root));
        try (var session = Composition.headlessSession(config)) {
            assertTrue(session.dispatch("/skill show web-access").orElse("").contains("📖 Skill: web-access"));
            session.submit("load and write");
            assertTrue(session.log().stream().filter(ToolResultEntry.class::isInstance)
                    .map(ToolResultEntry.class::cast).allMatch(result -> result.status() == ToolResult.Status.OK));
            assertEquals(1, session.log().stream().filter(LspDiagnostics.class::isInstance).count());
            assertEquals(0, session.log().stream().filter(SkillBody.class::isInstance).count());
            session.submit("apply the skill");
            assertEquals(1, session.log().stream().filter(SkillBody.class::isInstance).count());
        }
        assertTrue(Files.isRegularFile(config.skills().builtinCache().resolve("web-access/SKILL.md")));
    }
}
