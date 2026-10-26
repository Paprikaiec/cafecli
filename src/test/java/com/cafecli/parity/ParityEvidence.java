package com.cafecli.parity;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class ParityEvidence {
    static final Path DIRECTORY = Path.of("target/parity-evidence", java.util.UUID.randomUUID().toString());
    private ParityEvidence() { }

    static void save(String scenario, Path v1, Path v2, List<Integer> exceptions) throws Exception {
        Path evidence = Files.createDirectories(DIRECTORY.resolve(scenario));
        Files.copy(v1, evidence.resolve("v1.jsonl")); Files.copy(v2, evidence.resolve("v2.jsonl"));
        Files.writeString(evidence.resolve("v1-summary.json"), normalize(v1).toPrettyString());
        Files.writeString(evidence.resolve("v2-summary.json"), normalize(v2).toPrettyString());
        Files.writeString(evidence.resolve("exceptions.json"), ParityScript.JSON.writeValueAsString(exceptions));
    }

    static void result(String scenario, String arm, String answer, java.util.Map<String, String> files) throws Exception {
        Path evidence = Files.createDirectories(DIRECTORY.resolve(scenario));
        Files.writeString(evidence.resolve(arm + "-result.json"), ParityScript.JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(java.util.Map.of("answer", answer, "filesBase64", files)));
    }

    static JsonNode normalize(Path trace) throws Exception {
        Path errors = Files.createTempFile(trace.getParent(), "normalize-", ".err");
        Process process = new ProcessBuilder("python3", "src/test/resources/parity/normalize_traces.py", Path.of("").toAbsolutePath().toString(), trace.toString())
                .redirectError(errors.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "actual eval normalizer terminated");
            assertEquals(0, process.exitValue(), Files.readString(errors));
            return ParityScript.JSON.readTree(process.getInputStream());
        } finally { process.destroyForcibly(); Files.deleteIfExists(errors); }
    }
}
