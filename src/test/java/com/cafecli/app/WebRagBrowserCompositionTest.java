package com.cafecli.app;

import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.web.Freshness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebRagBrowserCompositionTest {
    @TempDir Path root;

    @Test void productionCompositionProvidesWebRagBrowserAndFirstRequestFreshness() throws Exception {
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[{"toolCalls":[
                  {"id":"fetch","name":"web_fetch","arguments":{"url":"file:///etc/passwd"}},
                  {"id":"rag","name":"search_code","arguments":{"query":"coffee"}},
                  {"id":"browser","name":"browser_connect","arguments":{}}
                ]},{"text":"done"}]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                "cafecli.fake.script", script.toString()), Map.of(), root.resolve("home"), root));
        try (var session = Composition.headlessSession(config)) {
            session.submit("latest coffee");
            assertEquals(1, session.log().stream().filter(Freshness.class::isInstance).count());
            var results = session.log().stream().filter(ToolResultEntry.class::isInstance)
                    .map(ToolResultEntry.class::cast).toList();
            assertEquals(List.of(ToolResult.Status.DENIED, ToolResult.Status.ERROR, ToolResult.Status.OK),
                    results.stream().map(ToolResultEntry::status).toList());
            assertEquals(List.of(new com.cafecli.ai.message.TextBlock("代码库尚未索引，请先使用 /index 命令索引当前项目。")),
                    results.get(1).message().content());
            assertTrue(session.dispatch("/browser disconnect").orElseThrow().contains("未配置 chrome-devtools"));
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }
}
