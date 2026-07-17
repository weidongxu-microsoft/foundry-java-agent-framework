package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public abstract class DelegatingChatClient implements ChatClient {
    private final ChatClient delegate;

    protected DelegatingChatClient(ChatClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    protected final ChatClient getDelegate() {
        return delegate;
    }

    public final ChatClient getInnerClient() {
        return delegate;
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        return delegate.getResponse(messages, options);
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        return delegate.getStreamingResponse(messages, options);
    }
}
