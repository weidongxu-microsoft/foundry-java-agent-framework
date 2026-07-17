package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChatMiddlewareContext {
    private final ChatClient client;
    private List<ChatMessage> messages;
    private ChatOptions options;
    private final boolean streaming;
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public ChatMiddlewareContext(
            ChatClient client,
            List<ChatMessage> messages,
            ChatOptions options,
            boolean streaming) {
        this.client = Objects.requireNonNull(client, "client");
        setMessages(messages);
        this.options = Objects.requireNonNull(options, "options");
        this.streaming = streaming;
    }

    public ChatClient getClient() {
        return client;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<? extends ChatMessage> messages) {
        this.messages = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(messages, "messages")));
    }

    public ChatOptions getOptions() {
        return options;
    }

    public void setOptions(ChatOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public boolean isStreaming() {
        return streaming;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
