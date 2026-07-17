package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface FoundryMemoryClient {
    CompletionStage<List<String>> search(
            String storeName,
            String scope,
            String query,
            int maxMemories,
            String callId);

    CompletionStage<Void> update(
            String storeName,
            String scope,
            List<ChatMessage> messages,
            int updateDelaySeconds,
            String callId);
}
