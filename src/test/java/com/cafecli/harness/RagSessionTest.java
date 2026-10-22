package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.rag.RagCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RagSessionTest {
    @TempDir Path root;
    @Test void semanticSearchIsClosedByProfileAndKeepsTheUnindexedLeafResult() {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("search", "search_code", "{\"query\":\"login implementation\"}").build(),
                FakeResponse.text("done"), FakeResponse.text("closed")));
        var model = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
        try (var session = AgentSession.builder().provider(provider).model(model).config(config)
                .capabilities(List.of(new RagCapability(root, config.rag()))).build()) {
            session.submit("find login");
            assertEquals(List.of("search_code"), provider.requests().getFirst().context().tools().stream()
                    .map(com.cafecli.ai.ToolDefinition::name).toList());
            var result = (ToolResultEntry) session.log().get(2);
            assertEquals(ToolResult.Status.ERROR, result.status());
            assertEquals(List.of(new TextBlock("代码库尚未索引，请先使用 /index 命令索引当前项目。")), result.message().content());
        }
        try (var session = AgentSession.builder().provider(provider).model(model).config(config)
                .capabilities(List.of()).build()) {
            session.submit("closed");
            assertTrue(provider.requests().getLast().context().tools().isEmpty());
        }
    }
}
