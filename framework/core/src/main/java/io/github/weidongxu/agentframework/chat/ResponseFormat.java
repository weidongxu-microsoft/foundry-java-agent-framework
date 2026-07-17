package io.github.weidongxu.agentframework.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ResponseFormat {
    public enum Type {
        TEXT,
        JSON_OBJECT,
        JSON_SCHEMA
    }

    private static final ResponseFormat TEXT = new ResponseFormat(Type.TEXT, null, Collections.emptyMap());
    private static final ResponseFormat JSON_OBJECT =
            new ResponseFormat(Type.JSON_OBJECT, null, Collections.emptyMap());

    private final Type type;
    private final String name;
    private final Map<String, Object> schema;

    private ResponseFormat(Type type, String name, Map<String, Object> schema) {
        this.type = Objects.requireNonNull(type, "type");
        this.name = name;
        this.schema = Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }

    public static ResponseFormat text() {
        return TEXT;
    }

    public static ResponseFormat jsonObject() {
        return JSON_OBJECT;
    }

    public static ResponseFormat jsonSchema(String name, Map<String, Object> schema) {
        return new ResponseFormat(
                Type.JSON_SCHEMA,
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(schema, "schema"));
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }
}
