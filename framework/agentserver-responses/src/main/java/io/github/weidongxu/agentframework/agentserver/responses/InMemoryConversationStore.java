package io.github.weidongxu.agentframework.agentserver.responses;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ConversationStore} — the default, keeping the hosted agent self-contained with
 * no external dependency (mirrors MAF's {@code InMemoryConversationStorage} / Python
 * {@code local_responses} sample). History is process-local and lost on restart; a durable backend
 * (e.g. the file-backed {@code FileSystemConversationStore}, or Cosmos/Redis) can implement
 * {@link ConversationStore} for production.
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final Map<String, List<ChatMessage>> byKey = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> load(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        List<ChatMessage> history = byKey.get(sessionKey);
        return history == null ? null : new ArrayList<>(history);
    }

    @Override
    public void save(String sessionKey, List<ChatMessage> history) {
        if (sessionKey == null || history == null) {
            return;
        }
        byKey.put(sessionKey, new ArrayList<>(history));
    }
}
