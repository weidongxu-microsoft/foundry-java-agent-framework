package io.github.weidongxu.agentframework.agentserver.responses;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ResponseStore} — the default. Records are process-local and lost on restart,
 * matching the SDK's in-memory response provider default. Implement {@link ResponseStore} with a
 * durable backend for production replay/cancel across restarts.
 */
public final class InMemoryResponseStore implements ResponseStore {

    private final Map<String, StoredResponse> byId = new ConcurrentHashMap<>();

    @Override
    public void save(StoredResponse response) {
        if (response != null) {
            byId.put(response.getId(), response);
        }
    }

    @Override
    public Optional<StoredResponse> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean delete(String id) {
        return id != null && byId.remove(id) != null;
    }
}
