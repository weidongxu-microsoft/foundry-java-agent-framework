package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A {@link MessageAIContextProvider} that injects a fixed, caller-supplied list of {@link ChatMessage}s
 * on every invocation — useful for static reminders, standing instructions expressed as messages, or
 * few-shot examples. Mirrors the common concrete use of MAF's {@code MessageAIContextProvider}.
 */
public final class StaticMessageAIContextProvider extends MessageAIContextProvider {
    private final List<ChatMessage> messages;

    public StaticMessageAIContextProvider(ChatMessage... messages) {
        this(Arrays.asList(Objects.requireNonNull(messages, "messages")));
    }

    public StaticMessageAIContextProvider(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    protected CompletionStage<List<ChatMessage>> provideMessages(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(messages);
    }
}
