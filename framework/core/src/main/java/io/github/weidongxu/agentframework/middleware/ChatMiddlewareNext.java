package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.chat.ChatResponse;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ChatMiddlewareNext {
    CompletionStage<ChatResponse> invoke(ChatMiddlewareContext context);
}
