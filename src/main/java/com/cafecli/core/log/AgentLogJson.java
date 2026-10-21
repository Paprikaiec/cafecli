package com.cafecli.core.log;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.jsontype.NamedType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An agent log as JSON, losslessly: an array of entries, each tagged with its {@code "kind"}. The built-in kinds are
 * known; custom kinds are registered by the harness (its kind registry, ticket 09, builds the map) without core
 * changing. A custom kind serializes the way Jackson serializes its class — a record by its components, or as its own
 * Jackson annotations say. Immutable and thread-safe.
 */
public final class AgentLogJson {

    private static final TypeReference<List<LogEntry>> LOG = new TypeReference<>() {
    };

    private final Set<Class<? extends CustomEntry>> customTypes;
    private final ObjectWriter writer;
    private final ObjectReader reader;

    /**
     * @param customKinds every custom kind a log may hold, by kind id
     * @throws IllegalArgumentException if an id is a built-in kind's, or one class is registered under two ids
     */
    public AgentLogJson(Map<String, Class<? extends CustomEntry>> customKinds) {
        Set<String> builtInIds = builtInIds();
        Set<Class<? extends CustomEntry>> types = new HashSet<>();
        customKinds.forEach((id, type) -> {
            if (builtInIds.contains(id)) {
                throw new IllegalArgumentException("\"" + id + "\" is the id of a built-in kind");
            }
            if (!types.add(type)) {
                throw new IllegalArgumentException(type.getName() + " is registered under more than one id");
            }
        });
        this.customTypes = Set.copyOf(types);
        ObjectMapper mapper = new ObjectMapper();
        customKinds.forEach((id, type) -> mapper.registerSubtypes(new NamedType(type, id)));
        this.writer = mapper.writerFor(LOG);
        this.reader = mapper.readerFor(LOG);
    }

    /**
     * @throws IllegalArgumentException if the log holds an entry of a custom kind that was not registered — written,
     *                                  it could not be read back
     */
    public String write(List<LogEntry> log) {
        for (LogEntry entry : log) {
            if (entry instanceof CustomEntry custom && !customTypes.contains(custom.getClass())) {
                throw new IllegalArgumentException("custom kind " + custom.getClass().getName()
                        + " is not registered, so a log holding it could not be read back");
            }
        }
        try {
            return writer.writeValueAsString(log);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the log cannot be written: " + e.getOriginalMessage(), e);
        }
    }

    /** @throws IllegalArgumentException if {@code json} is not a log of known kinds */
    public List<LogEntry> read(String json) {
        try {
            return reader.readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not an agent log of known kinds: " + e.getOriginalMessage(), e);
        }
    }

    /** The built-in kinds' ids, read from where Jackson reads them, so the two cannot disagree. */
    private static Set<String> builtInIds() {
        return Arrays.stream(LogEntry.class.getAnnotation(JsonSubTypes.class).value())
                .map(JsonSubTypes.Type::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
