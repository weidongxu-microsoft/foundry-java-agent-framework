package io.github.weidongxu.agentframework.compaction;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * An {@link AIContextProvider} that reduces the message history sent to the model using a
 * {@link CompactionStrategy}. Unlike additive providers (memory, skills), it overrides
 * {@link #invoking(AgentInvokingContext)} to <em>replace</em> the message list — mirroring MAF's
 * {@code CompactionProvider}, which overrides the invoking-core hook.
 *
 * <p>Place this provider last in the chain so it compacts the fully assembled context.
 */
public final class CompactionProvider extends AIContextProvider {
    private final CompactionStrategy strategy;

    public CompactionProvider(CompactionStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /** A provider that truncates the oldest turns beyond MAF's default window. */
    public static CompactionProvider truncation() {
        return new CompactionProvider(new TruncationCompactionStrategy());
    }

    /** A provider that keeps only the most recent {@code keepLastGroups} turns. */
    public static CompactionProvider slidingWindow(int keepLastGroups) {
        return new CompactionProvider(new SlidingWindowCompactionStrategy(keepLastGroups));
    }

    @Override
    public CompletionStage<AIContext> invoking(AgentInvokingContext context) {
        Objects.requireNonNull(context, "context");
        AIContext current = context.getAIContext();
        List<ChatMessage> messages = current.getMessages();

        CompactionMessageIndex index = new CompactionMessageIndex(messages);
        strategy.compact(index);
        List<ChatMessage> included = index.getIncludedMessages();

        if (included.size() == messages.size()) {
            return CompletableFuture.completedFuture(current);
        }
        AIContext compacted = AIContext.builder()
                .instructions(current.getInstructions())
                .messages(included)
                .tools(current.getTools())
                .build();
        return CompletableFuture.completedFuture(compacted);
    }
}
