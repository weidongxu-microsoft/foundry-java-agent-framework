package com.example.hostedagent;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-scope store for the session TODO list — the state behind {@link TodoTool}.
 *
 * <p>State is partitioned by <em>scope</em> (the same per-user memory partition the hosting
 * controller resolves from the Foundry identity header), so one container serving multiple users
 * keeps their lists isolated. Writes use <b>full-list-replace</b> semantics —
 * matching how opencode and Claude Code's {@code TodoWrite} behave — so the model always sends the
 * complete new list and the store simply swaps it in.</p>
 *
 * <p>The store is intentionally in-memory: it lives for the life of the container session. For
 * survival across idle→resume it could be persisted under the session's {@code $HOME}, but that is
 * out of scope here.</p>
 */
@Service
public class TodoService {

    private static final String DEFAULT_SCOPE = "default";

    private final Map<String, List<TodoItem>> store = new ConcurrentHashMap<>();

    private static String key(String scope) {
        return (scope == null || scope.isBlank()) ? DEFAULT_SCOPE : scope.trim();
    }

    /** Returns an immutable snapshot of the current list for the scope (empty when unset). */
    public List<TodoItem> read(String scope) {
        return store.getOrDefault(key(scope), List.of());
    }

    /** Atomically replaces the whole list for the scope and returns the stored (immutable) copy. */
    public List<TodoItem> write(String scope, List<TodoItem> items) {
        List<TodoItem> snapshot = List.copyOf(items);
        store.put(key(scope), snapshot);
        return snapshot;
    }

    /** Drops the list for the scope (used mainly by tests). */
    public void clear(String scope) {
        store.remove(key(scope));
    }
}
