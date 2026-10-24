package com.cafecli.harness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns only model-selection edits to the loader's file snapshot; environment credentials never enter this tree. */
public final class ModelPreferences {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final ObjectNode document;

    ModelPreferences(Path file, ObjectNode document) {
        this.file = file;
        this.document = document.deepCopy();
    }

    public void explicitModel(String provider, String model) {
        object(object(document, "providers"), provider).put("model", model);
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        if (parent.get(field) instanceof ObjectNode value) return value;
        return parent.putObject(field);
    }

    public void save(String provider) throws IOException {
        document.put("defaultProvider", provider);
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
    }
}
