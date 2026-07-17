package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface ChatMiddleware {
    default CompletionStage<ChatResponse> invoke(
            ChatMiddlewareContext context,
            ChatMiddlewareNext next) {
        return next.invoke(context);
    }

    default Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            ChatMiddlewareContext context,
            ChatStreamingMiddlewareNext next) {
        return next.invoke(context);
    }
}
