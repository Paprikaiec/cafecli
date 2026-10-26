package com.cafecli.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ParityFilesTest {
    @TempDir Path project;

    @Test void addedDeletedAndChangedActualFilesIncludingHiddenFilesFailTheCompleteComparison() throws Exception {
        Path file = project.resolve("note.txt"); Files.writeString(file, "fixture bytes");
        Map<String, String> expected = Map.of("note.txt", "Zml4dHVyZSBieXRlcw==");
        ParityFiles.compare(expected, ParityFiles.read(project));
        Path hidden = Files.createDirectories(project.resolve(".hidden")).resolve("extra"); Files.writeString(hidden, "extra bytes");
        assertThrows(AssertionError.class, () -> ParityFiles.compare(expected, ParityFiles.read(project)));
        Files.delete(hidden); Files.delete(file);
        assertThrows(AssertionError.class, () -> ParityFiles.compare(expected, ParityFiles.read(project)));
        Files.writeString(file, "changed bytes");
        assertThrows(AssertionError.class, () -> ParityFiles.compare(expected, ParityFiles.read(project)));
    }

    @Test void ordinaryProjectMcpConfigurationCreationChangeAndDeletionCannotBeIgnored() throws Exception {
        Path config = Files.createDirectories(project.resolve(".cafecli")).resolve("mcp.json");
        Files.writeString(config, "original config");
        Map<String, String> expected = Map.of(".cafecli/mcp.json", "b3JpZ2luYWwgY29uZmln");
        ParityFiles.compare(expected, ParityFiles.read(project));
        assertThrows(AssertionError.class, () -> ParityFiles.compare(Map.of(), ParityFiles.read(project)), "unexpected config creation");
        Files.writeString(config, "changed config");
        assertThrows(AssertionError.class, () -> ParityFiles.compare(expected, ParityFiles.read(project)));
        Files.delete(config);
        assertThrows(AssertionError.class, () -> ParityFiles.compare(expected, ParityFiles.read(project)));
    }

    @Test void explicitlyOwnedEndpointConfigurationIsValidatedBeforeOmissionAndOtherDotfilesRemainVisible() throws Exception {
        byte[] known = "fixture endpoint config".getBytes(StandardCharsets.UTF_8);
        Path config = Files.createDirectories(project.resolve(".cafecli")).resolve("mcp.json"); Files.write(config, known);
        ParityFiles.compare(Map.of(), ParityFiles.read(project, known));
        Files.writeString(project.resolve(".cafecli/extra"), "unexpected hidden file");
        assertThrows(AssertionError.class, () -> ParityFiles.compare(Map.of(), ParityFiles.read(project, known)));
        Files.delete(project.resolve(".cafecli/extra")); Files.writeString(config, "modified endpoint config");
        assertThrows(AssertionError.class, () -> ParityFiles.read(project, known));
        Files.delete(config);
        assertThrows(AssertionError.class, () -> ParityFiles.read(project, known));
    }
}
