package io.github.weidongxu.agentframework.agent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public abstract class AIContextProvider {
    public List<String> getStateKeys() {
        return Collections.singletonList(getClass().getName());
    }

    public CompletionStage<AIContext> invoking(AgentInvokingContext context) {
        Objects.requireNonNull(context, "context");
        CompletionStage<AIContext> provided =
                Objects.requireNonNull(provide(context), "provide returned null");
        return provided.thenApply(additional ->
                context.getAIContext().merge(Objects.requireNonNull(
                        additional,
                        "provide completed with null")));
    }

    public CompletionStage<Void> invoked(AgentInvokedContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.isSuccessful()) {
            return CompletableFuture.completedFuture(null);
        }
        return Objects.requireNonNull(store(context), "store returned null");
    }

    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(AIContext.empty());
    }

    protected CompletionStage<Void> store(AgentInvokedContext context) {
        return CompletableFuture.completedFuture(null);
    }
}
