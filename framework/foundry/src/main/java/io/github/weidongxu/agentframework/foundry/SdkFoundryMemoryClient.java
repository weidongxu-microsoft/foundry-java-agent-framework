package io.github.weidongxu.agentframework.foundry;

import com.azure.ai.agents.BetaMemoryStoresClient;
import com.azure.ai.agents.models.MemoryItem;
import com.azure.ai.agents.models.MemorySearchItem;
import com.azure.ai.agents.models.MemorySearchOptions;
import com.azure.ai.agents.models.MemoryStoreSearchResponse;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import com.openai.models.responses.ResponseInputItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class SdkFoundryMemoryClient implements FoundryMemoryClient {
    private final BetaMemoryStoresClient client;
    private final Executor executor;

    public SdkFoundryMemoryClient(
            BetaMemoryStoresClient client,
            Executor executor) {
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<List<String>> search(
            String storeName,
            String scope,
            String query,
            int maxMemories,
            String callId) {
        requireNonBlank(storeName, "storeName");
        requireNonBlank(scope, "scope");
        requireNonBlank(query, "query");
        if (maxMemories <= 0) {
            throw new IllegalArgumentException(
                    "maxMemories must be positive");
        }
        return CompletableFuture.supplyAsync(() -> FoundryCallContext.callWith(callId, () -> {
            MemoryStoreSearchResponse response = client.searchMemories(
                    storeName,
                    scope,
                    Collections.singletonList(input(ChatRole.USER, query)),
                    null,
                    new MemorySearchOptions()
                            .setMaxMemories(maxMemories));
            if (response == null || response.getMemories() == null) {
                return Collections.emptyList();
            }
            List<String> memories = new ArrayList<>();
            for (MemorySearchItem result : response.getMemories()) {
                MemoryItem item =
                        result == null ? null : result.getMemoryItem();
                if (item != null
                        && item.getContent() != null
                        && !item.getContent().isBlank()) {
                    memories.add(item.getContent());
                }
            }
            return Collections.unmodifiableList(memories);
        }), executor);
    }

    @Override
    public CompletionStage<Void> update(
            String storeName,
            String scope,
            List<ChatMessage> messages,
            int updateDelaySeconds,
            String callId) {
        requireNonBlank(storeName, "storeName");
        requireNonBlank(scope, "scope");
        Objects.requireNonNull(messages, "messages");
        if (updateDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "updateDelaySeconds cannot be negative");
        }
        List<ResponseInputItem> input = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.getText() != null && !message.getText().isBlank()) {
                input.add(input(message.getRole(), message.getText()));
            }
        }
        if (input.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> FoundryCallContext.runWith(callId, () ->
                client.beginUpdateMemories(
                        storeName,
                        scope,
                        input,
                        null,
                        updateDelaySeconds)), executor);
    }

    private static ResponseInputItem input(ChatRole role, String text) {
        return ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                        .type(ResponseInputItem.Message.Type.MESSAGE)
                        .role(ResponseInputItem.Message.Role.of(
                                role == ChatRole.ASSISTANT
                                        ? "assistant"
                                        : "user"))
                        .addInputTextContent(text)
                        .build());
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
