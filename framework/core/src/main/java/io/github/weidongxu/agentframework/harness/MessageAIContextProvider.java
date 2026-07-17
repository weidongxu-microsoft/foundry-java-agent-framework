package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * An {@link AIContextProvider} whose sole contribution is a set of additional {@link ChatMessage}s
 * supplied each turn, mirroring the MAF {@code MessageAIContextProvider}. Subclasses override
 * {@link #provideMessages(AgentInvokingContext)} to decide which messages to inject (e.g. a fixed
 * system reminder, a retrieved snippet, or contextual conversation history).
 *
 * <p>Because our {@link AIContext#merge} adds the returned messages to the existing request context,
 * this class only needs to return the <em>additional</em> messages — the framework handles merging.
 * For a ready-to-use fixed-message injector, see {@link StaticMessageAIContextProvider}.</p>
 */
public abstract class MessageAIContextProvider extends AIContextProvider {

    @Override
    protected final CompletionStage<AIContext> provide(AgentInvokingContext context) {
        return provideMessages(context).thenApply(messages -> {
            AIContext.Builder builder = AIContext.builder();
            if (messages != null) {
                builder.messages(messages);
            }
            return builder.build();
        });
    }

    /**
     * Returns the additional messages to inject for this invocation. The default returns none.
     *
     * @param context the invoking context (agent, session, and the request context so far)
     * @return a stage yielding the messages to add; {@code null} or empty adds nothing
     */
    protected CompletionStage<List<ChatMessage>> provideMessages(AgentInvokingContext context) {
        return CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }
}
