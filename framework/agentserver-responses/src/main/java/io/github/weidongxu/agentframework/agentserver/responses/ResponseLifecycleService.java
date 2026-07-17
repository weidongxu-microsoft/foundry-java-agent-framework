package io.github.weidongxu.agentframework.agentserver.responses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral implementation of the response-lifecycle operations, backed by a
 * {@link ResponseStore}. The web host (e.g. {@code agentserver-spring}) maps HTTP routes onto these
 * methods and serializes the returned {@link Result}. This is the Java counterpart of the AgentServer
 * SDK persistence layer that auto-serves the lifecycle routes.
 *
 * <ul>
 *   <li>{@link #get(String)} — {@code GET /responses/{id}}</li>
 *   <li>{@link #cancel(String)} — {@code POST /responses/{id}/cancel} (only valid for
 *       {@code background:true}; otherwise 400)</li>
 *   <li>{@link #delete(String)} — {@code DELETE /responses/{id}}</li>
 *   <li>{@link #listInputItems(String, Integer, String, String)} — {@code GET /responses/{id}/input_items}</li>
 * </ul>
 */
public final class ResponseLifecycleService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final List<String> TERMINAL_STATUSES =
            List.of("completed", "cancelled", "failed", "incomplete");

    private final ResponseStore store;

    public ResponseLifecycleService(ResponseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** {@code GET /responses/{id}} — fetch the stored response envelope. */
    public Result get(String id) {
        Optional<StoredResponse> found = store.get(id);
        return found.map(response -> Result.ok(response.getEnvelope()))
                .orElseGet(() -> notFound(id));
    }

    /**
     * {@code POST /responses/{id}/cancel} — cancel a background response. Returns 400 for a
     * non-background response (matching the Responses contract), 404 when unknown, else the updated
     * envelope with {@code status: cancelled}.
     */
    public Result cancel(String id) {
        Optional<StoredResponse> found = store.get(id);
        if (!found.isPresent()) {
            return notFound(id);
        }
        StoredResponse response = found.get();
        if (!response.isBackground()) {
            return error(400, "invalid_request_error", "invalid_request",
                    "Only responses created with background:true can be cancelled.");
        }
        if (TERMINAL_STATUSES.contains(response.getStatus())
                && !"cancelled".equals(response.getStatus())) {
            // Already terminal (completed/failed/incomplete) — cannot cancel.
            return error(400, "invalid_request_error", "invalid_request",
                    "Response '" + id + "' is already in terminal status '"
                            + response.getStatus() + "' and cannot be cancelled.");
        }
        StoredResponse cancelled = response.withStatus("cancelled");
        store.save(cancelled);
        return Result.ok(cancelled.getEnvelope());
    }

    /** {@code DELETE /responses/{id}} — delete a stored response. */
    public Result delete(String id) {
        if (store.delete(id)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("object", "response.deleted");
            body.put("deleted", true);
            return Result.ok(body);
        }
        return notFound(id);
    }

    /**
     * {@code GET /responses/{id}/input_items} — paginated list of the response's input items.
     *
     * @param limit max items to return (1..100, default 20)
     * @param after return items after this item id (cursor); {@code null} to start at the beginning
     * @param order {@code "asc"} (default) or {@code "desc"}
     */
    public Result listInputItems(String id, Integer limit, String after, String order) {
        Optional<StoredResponse> found = store.get(id);
        if (!found.isPresent()) {
            return notFound(id);
        }
        List<Map<String, Object>> items = new ArrayList<>(found.get().getInputItems());
        if ("desc".equalsIgnoreCase(order)) {
            Collections.reverse(items);
        }
        if (after != null && !after.isEmpty()) {
            int cursor = indexOfId(items, after);
            items = cursor < 0 ? new ArrayList<>() : new ArrayList<>(items.subList(cursor + 1, items.size()));
        }
        int effectiveLimit = clampLimit(limit);
        boolean hasMore = items.size() > effectiveLimit;
        List<Map<String, Object>> page = hasMore
                ? new ArrayList<>(items.subList(0, effectiveLimit))
                : items;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", page);
        body.put("first_id", page.isEmpty() ? null : idOf(page.get(0)));
        body.put("last_id", page.isEmpty() ? null : idOf(page.get(page.size() - 1)));
        body.put("has_more", hasMore);
        return Result.ok(body);
    }

    private static int indexOfId(List<Map<String, Object>> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(idOf(items.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static String idOf(Map<String, Object> item) {
        Object id = item.get("id");
        return id == null ? null : id.toString();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Result notFound(String id) {
        return error(404, "invalid_request_error", "not_found",
                "Response with id '" + id + "' not found.");
    }

    private static Result error(int status, String type, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        return new Result(status, Collections.singletonMap("error", error));
    }

    /** The outcome of a lifecycle operation: an HTTP status and a JSON-serializable body. */
    public static final class Result {
        private final int status;
        private final Object body;

        public Result(int status, Object body) {
            this.status = status;
            this.body = body;
        }

        static Result ok(Object body) {
            return new Result(200, body);
        }

        public int getStatus() {
            return status;
        }

        public Object getBody() {
            return body;
        }
    }
}
