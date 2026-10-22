package com.cafecli.harness.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WebRagConfigTest {
    @TempDir Path root;
    @Test void webPreservesEnvironmentThenPropertyThenProjectAndHomeDotEnvPrecedence() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Files.writeString(root.resolve(".env"), "SEARXNG_URL=http://project.invalid\nSERPAPI_KEY=project-key\nGLM_API_KEY=project-glm\n");
        Files.writeString(home.resolve(".env"), "SEARXNG_URL=http://home.invalid\nZHIPU_SEARCH_ENGINE=home-engine\n");
        var config = ConfigLoader.load(new ConfigSources(Map.of("SEARCH_PROVIDER", "searxng", "SEARXNG_URL", "http://property.invalid"),
                Map.of("SEARCH_PROVIDER", " serpapi ", "GLM_API_KEY", " env-glm ", "SEARXNG_URL", "  "), home, root));
        assertEquals(new WebConfig("serpapi", "env-glm", "home-engine", "project-key", "http://property.invalid"), config.web());
        assertEquals(new WebConfig("serpapi", "env-glm", "home-engine", "project-key", "http://property.invalid"), config.web());
    }
    @Test void embeddingKeepsItsDifferentNonemptyUntrimmedEnvironmentAndPropertyRules() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("EMBEDDING_PROVIDER", "glm", "EMBEDDING_MODEL", "property-model"),
                Map.of("EMBEDDING_PROVIDER", "openai", "EMBEDDING_MODEL", " env-model ", "EMBEDDING_API_KEY", " "), root.resolve("home"), root));
        assertEquals(new RagConfig("openai", " env-model ", "http://localhost:11434", " "), config.rag());
    }
}
