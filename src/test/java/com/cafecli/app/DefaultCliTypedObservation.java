package com.cafecli.app;

import com.cafecli.core.AgentEvent;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.tool.BrowserTools;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Temporary parity supplement: compatible traces omit status, so observe real leaf/session typed outcomes too. */
public final class DefaultCliTypedObservation {
    private DefaultCliTypedObservation() { }
    public record Browser(ToolOutput.Status v1, ToolResult.Status v2, String v1Text, String v2Text) { }

    public static Browser browserDisconnect(Path root) throws Exception {
        var json = new ObjectMapper();
        var legacy = new BrowserTools(() -> null).tools().stream().filter(tool -> tool.name().equals("browser_disconnect"))
                .findFirst().orElseThrow().executor().execute(json.createObjectNode());
        Files.createDirectories(root); Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[{"toolCalls":[{"id":"browser","name":"browser_disconnect","arguments":{}}]},{"text":"done"}]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.enabled", "false"), Map.of(), root.resolve("home"), root));
        var observed = new AtomicReference<com.cafecli.core.log.ToolResultEntry>();
        try (var session = Composition.headlessSession(config)) {
            session.subscribe(event -> { if (event instanceof AgentEvent.ToolEnd end && end.result().message().toolName().equals("browser_disconnect")) observed.set(end.result()); });
            session.submit("disconnect the local fixture");
        }
        var result = java.util.Objects.requireNonNull(observed.get(), "actual composition must emit its typed browser completion");
        String text = result.message().content().stream().filter(com.cafecli.ai.message.TextBlock.class::isInstance)
                .map(com.cafecli.ai.message.TextBlock.class::cast).map(com.cafecli.ai.message.TextBlock::text).collect(java.util.stream.Collectors.joining());
        return new Browser(legacy.status(), result.status(), legacy.text(), text);
    }
}
