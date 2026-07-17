package io.github.weidongxu.agentframework.agentserver.responses;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;

/**
 * Host-side conversation/thread history store for the Responses server.
 *
 * <p>The hosted agent owns its own conversation state — mirroring the Microsoft Agent Framework
 * {@code IConversationStorage} (.NET) / local session store (Python). An inbound
 * {@code conversation} / {@code previous_response_id} id on {@code POST /responses} is used ONLY as
 * a key into this store to load prior turns; it is never forwarded to the upstream model. This is
 * what lets a gateway-supplied {@code conv_*} id (e.g. from the Foundry Playground) thread history
 * without the backing model ever having created that conversation.</p>
 */
public interface ConversationStore {

    /**
     * Loads the accumulated history for a session key.
     *
     * @param sessionKey a {@code conv_*} or {@code resp_*} id supplied by the caller
     * @return the accumulated messages for this key, or {@code null} if the key is unknown
     */
    List<ChatMessage> load(String sessionKey);

    /**
     * Snapshots the full accumulated history (prior turns + latest input + latest output) under a
     * session key. Called once per key per turn so both the conversation id and the newly issued
     * response id resolve to the same up-to-date history on the next turn.
     *
     * @param sessionKey the key to persist under
     * @param history    the full accumulated message history for the thread
     */
    void save(String sessionKey, List<ChatMessage> history);
}
