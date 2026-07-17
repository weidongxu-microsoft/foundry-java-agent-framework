package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;

import java.util.concurrent.Flow;

@FunctionalInterface
public interface ChatStreamingMiddlewareNext {
    Flow.Publisher<ChatResponseUpdate> invoke(ChatMiddlewareContext context);
}
