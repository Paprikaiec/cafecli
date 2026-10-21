package com.cafecli.app;

import com.cafecli.core.log.AgentLogJson;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.context.Steering;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Enumerates the real headless composition: adding a kind requires a fixture, never a second kind list. */
class MessageKindsRoundTripTest {
    @TempDir static Path root;

    record Fixture(CustomEntry entry, String json) { }

    static Stream<Arguments> registeredKinds() throws Exception {
        Path script = root.resolve("script.json");
        Files.writeString(script, "{\"responses\":[{\"text\":\"done\"}]}");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                "cafecli.fake.script", script.toString()), Map.of(), root.resolve("home"), root));
        Map<Class<? extends CustomEntry>, Fixture> fixtures = Map.of(Steering.class,
                new Fixture(new Steering("Use \"other\"\n路径"),
                        "[{\"kind\":\"steering\",\"text\":\"Use \\\"other\\\"\\n路径\"}]"));
        try (AgentSession session = Composition.headlessSession(config)) {
            AgentLogJson json = session.messageKinds().json();
            return session.messageKinds().kinds().stream().map(kind -> {
                Fixture fixture = fixtures.get(kind.type());
                assertNotNull(fixture, "registered kind needs a round-trip fixture: " + kind.id());
                return Arguments.of(kind.id(), json, fixture);
            }).toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registeredKinds")
    void everyProductionRegisteredKindRoundTrips(String id, AgentLogJson json, Fixture fixture) {
        assertEquals(fixture.json(), json.write(List.of(fixture.entry())), id);
        assertEquals(List.of(fixture.entry()), json.read(fixture.json()), id);
    }
}
