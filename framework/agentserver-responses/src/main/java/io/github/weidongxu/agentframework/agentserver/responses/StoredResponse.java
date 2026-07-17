package io.github.weidongxu.agentframework.agentserver.responses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A persisted {@code /responses} record — the unit the response-lifecycle routes
 * ({@code GET /responses/{id}}, {@code POST /responses/{id}/cancel}, {@code DELETE /responses/{id}},
 * {@code GET /responses/{id}/input_items}) operate on.
 *
 * <p>Holds the full response envelope returned to the caller, the normalized input items of the
 * request, and lifecycle metadata (status, {@code background} flag). Immutable; use
 * {@link #withStatus(String)} to obtain a copy with a changed status (e.g. on cancel).
 */
public final class StoredResponse {

    private final String id;
    private final long createdAt;
    private final String status;
    private final boolean background;
    private final Map<String, Object> envelope;
    private final List<Map<String, Object>> inputItems;

    public StoredResponse(
            String id,
            long createdAt,
            String status,
            boolean background,
            Map<String, Object> envelope,
            List<Map<String, Object>> inputItems) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = createdAt;
        this.status = Objects.requireNonNull(status, "status");
        this.background = background;
        this.envelope = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(envelope, "envelope")));
        List<Map<String, Object>> copy = new ArrayList<>();
        if (inputItems != null) {
            for (Map<String, Object> item : inputItems) {
                copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(item)));
            }
        }
        this.inputItems = Collections.unmodifiableList(copy);
    }

    public String getId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }

    public boolean isBackground() {
        return background;
    }

    /** @return the full response envelope (as returned by {@code POST /responses}). */
    public Map<String, Object> getEnvelope() {
        return envelope;
    }

    /** @return the normalized input items of the originating request. */
    public List<Map<String, Object>> getInputItems() {
        return inputItems;
    }

    /** @return a copy of this record with the given status (and matching {@code status} in the envelope). */
    public StoredResponse withStatus(String newStatus) {
        Map<String, Object> updatedEnvelope = new LinkedHashMap<>(envelope);
        updatedEnvelope.put("status", newStatus);
        return new StoredResponse(id, createdAt, newStatus, background, updatedEnvelope, inputItems);
    }
}
