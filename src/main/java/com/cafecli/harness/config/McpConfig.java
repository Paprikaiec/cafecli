package com.cafecli.harness.config;

import com.cafecli.mcp.config.McpConfigLoader;
import java.nio.file.Path;
import java.util.Map;

/** Explicit MCP homes and interpolation sources, including the deliberately ungated project mcp.json. */
public record McpConfig(Path projectRoot, ConfigHomes homes, Map<String, String> properties,
                        Map<String, String> environment, Path userHome) {
    public McpConfig { properties = Map.copyOf(properties); environment = Map.copyOf(environment); }
    public McpConfigLoader loader() {
        return new McpConfigLoader(homes.user().resolve("mcp.json"), homes.project().resolve("mcp.json"),
                projectRoot, environment, properties, userHome);
    }
}
