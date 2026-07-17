package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public abstract class ChatHistoryProvider {
    public List<String> getStateKeys() {
        return Collections.singletonList(getClass().getName());
    }

    public CompletionStage<List<ChatMessage>> invoking(AgentInvokingContext context) {
        Objects.requireNonNull(context, "context");
        CompletionStage<List<ChatMessage>> provided =
                Objects.requireNonNull(provide(context), "provide returned null");
        return provided.thenApply(history -> {
            List<ChatMessage> merged = new ArrayList<>(
                    Objects.requireNonNull(history, "provide completed with null"));
            merged.addAll(context.getAIContext().getMessages());
            return Collections.unmodifiableList(merged);
        });
    }

    public CompletionStage<Void> invoked(AgentInvokedContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.isSuccessful()) {
            return CompletableFuture.completedFuture(null);
        }
        return Objects.requireNonNull(store(context), "store returned null");
    }

    protected CompletionStage<List<ChatMessage>> provide(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    protected CompletionStage<Void> store(AgentInvokedContext context) {
        return CompletableFuture.completedFuture(null);
    }
}
