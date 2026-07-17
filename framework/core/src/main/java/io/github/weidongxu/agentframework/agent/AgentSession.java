package io.github.weidongxu.agentframework.agent;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSession {
    private final String id;
    private final ConcurrentHashMap<String, Object> state;
    private final AtomicReference<Object> serviceSessionId;

    public AgentSession() {
        this(UUID.randomUUID().toString(), null, Collections.emptyMap());
    }

    public AgentSession(String id, Map<String, Object> initialState) {
        this(id, null, initialState);
    }

    public AgentSession(
            String id,
            Object serviceSessionId,
            Map<String, Object> initialState) {
        this.id = requireNonBlank(id, "id");
        this.serviceSessionId = new AtomicReference<>(
                immutableServiceSessionId(serviceSessionId));
        this.state = new ConcurrentHashMap<>(
                Objects.requireNonNull(initialState, "initialState"));
    }

    public String getId() {
        return id;
    }

    public Object getServiceSessionId() {
        return serviceSessionId.get();
    }

    public void setServiceSessionId(Object serviceSessionId) {
        this.serviceSessionId.set(immutableServiceSessionId(serviceSessionId));
    }

    public boolean compareAndSetServiceSessionId(Object expected, Object updated) {
        return serviceSessionId.compareAndSet(
                expected,
                immutableServiceSessionId(updated));
    }

    public Object get(String key) {
        return state.get(Objects.requireNonNull(key, "key"));
    }

    public <T> T get(String key, Class<T> type) {
        Object value = get(key);
        return value == null ? null : type.cast(value);
    }

    public void put(String key, Object value) {
        state.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    public Object remove(String key) {
        return state.remove(Objects.requireNonNull(key, "key"));
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static Object immutableServiceSessionId(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException(
                            "serviceSessionId map keys must be strings");
                }
                copy.put((String) key, immutableServiceSessionId(item));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                copy.add(immutableServiceSessionId(item));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(
                "serviceSessionId must be JSON-compatible");
    }
}
