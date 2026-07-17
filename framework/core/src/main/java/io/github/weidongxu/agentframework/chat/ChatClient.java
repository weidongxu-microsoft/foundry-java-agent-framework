package io.github.weidongxu.agentframework.chat;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface ChatClient {
    CompletionStage<ChatResponse> getResponse(List<ChatMessage> messages, ChatOptions options);

    /**
     * Streams normalized updates. Providers must coalesce tool-call argument deltas and emit each
     * {@link FunctionCallContent} only when its arguments are complete.
     */
    Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options);
}
