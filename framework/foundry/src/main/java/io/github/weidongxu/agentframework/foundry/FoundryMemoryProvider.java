package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Foundry-backed {@link AIContextProvider} that recalls relevant memories on each turn and queues
 * durable memories for server-side extraction after each turn.
 *
 * <p>Aligned with the .NET {@code FoundryMemoryProvider}: the salience decision (secrets,
 * pleasantries) lives server-side in the memory-store extractor. This provider stays thin, but
 * offers optional per-side {@link FoundryMemoryProviderOptions} message-filter hooks so callers can
 * add a client-side belt-and-suspenders layer (drop/cap messages) without subclassing.</p>
 */
public final class FoundryMemoryProvider extends AIContextProvider {

    private final FoundryMemoryClient client;
    private final String storeName;
    private final Function<AgentSession, String> scopeResolver;
    private final int maxMemories;
    private final int updateDelaySeconds;
    private final String contextPrompt;
    private final Function<List<ChatMessage>, List<ChatMessage>> searchInputMessageFilter;
    private final Function<List<ChatMessage>, List<ChatMessage>> storageRequestMessageFilter;
    private final Function<List<ChatMessage>, List<ChatMessage>> storageResponseMessageFilter;

    public FoundryMemoryProvider(FoundryMemoryClient client, String storeName) {
        this(client, storeName, new FoundryMemoryProviderOptions());
    }

    /** Back-compat convenience constructor. */
    public FoundryMemoryProvider(
            FoundryMemoryClient client,
            String storeName,
            Function<AgentSession, String> scopeResolver,
            int maxMemories,
            int updateDelaySeconds) {
        this(client, storeName, new FoundryMemoryProviderOptions()
                .setScopeResolver(scopeResolver)
                .setMaxMemories(maxMemories)
                .setUpdateDelaySeconds(updateDelaySeconds));
    }

    public FoundryMemoryProvider(
            FoundryMemoryClient client,
            String storeName,
            FoundryMemoryProviderOptions options) {
        this.client = Objects.requireNonNull(client, "client");
        this.storeName = Objects.requireNonNull(storeName, "storeName");
        if (storeName.isBlank()) {
            throw new IllegalArgumentException("storeName cannot be blank");
        }
        Objects.requireNonNull(options, "options");
        this.scopeResolver = Objects.requireNonNull(options.getScopeResolver(), "scopeResolver");
        if (options.getMaxMemories() <= 0) {
            throw new IllegalArgumentException("maxMemories must be positive");
        }
        if (options.getUpdateDelaySeconds() < 0) {
            throw new IllegalArgumentException("updateDelaySeconds cannot be negative");
        }
        this.maxMemories = options.getMaxMemories();
        this.updateDelaySeconds = options.getUpdateDelaySeconds();
        this.contextPrompt = Objects.requireNonNull(options.getContextPrompt(), "contextPrompt");
        this.searchInputMessageFilter = options.getSearchInputMessageFilter();
        this.storageRequestMessageFilter = options.getStorageRequestMessageFilter();
        this.storageResponseMessageFilter = options.getStorageResponseMessageFilter();
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        List<ChatMessage> messages = context.getAIContext().getMessages();
        if (searchInputMessageFilter != null) {
            messages = searchInputMessageFilter.apply(messages);
        }
        String query = latestUserText(messages);
        if (query == null) {
            return CompletableFuture.completedFuture(AIContext.empty());
        }
        return client.search(storeName, scope(context.getSession()), query, maxMemories,
                        callId(context.getSession()))
                .thenApply(memories -> memories == null || memories.isEmpty()
                        ? AIContext.empty()
                        : AIContext.builder()
                                .instructions(memoryInstructions(memories))
                                .build())
                .exceptionally(error -> AIContext.empty());
    }

    @Override
    protected CompletionStage<Void> store(AgentInvokedContext context) {
        List<ChatMessage> request = context.getRequestMessages();
        List<ChatMessage> response = context.getResponseMessages();
        if (storageRequestMessageFilter != null) {
            request = storageRequestMessageFilter.apply(request);
        }
        if (storageResponseMessageFilter != null) {
            response = storageResponseMessageFilter.apply(response);
        }
        List<ChatMessage> messages = new ArrayList<>();
        if (request != null) {
            messages.addAll(request);
        }
        if (response != null) {
            messages.addAll(response);
        }
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return client.update(
                        storeName,
                        scope(context.getSession()),
                        Collections.unmodifiableList(messages),
                        updateDelaySeconds,
                        callId(context.getSession()))
                .exceptionally(error -> null);
    }

    private String scope(AgentSession session) {
        String scope = scopeResolver.apply(session);
        if (scope == null || scope.isBlank()) {
            throw new IllegalStateException(
                    "Foundry memory scope could not be resolved "
                            + "(no agent session and no fallback scope configured)");
        }
        return scope;
    }

    /**
     * The platform call id stashed on the session by the hosting handler, or {@code null}. Forwarded
     * to the memory client so outbound Storage calls carry {@code x-agent-foundry-call-id}.
     */
    private static String callId(AgentSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.get(FoundryHostedContext.CALL_ID_HEADER);
        return value instanceof String && !((String) value).isBlank() ? (String) value : null;
    }

    private static String latestUserText(List<ChatMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message.getRole() == ChatRole.USER
                    && message.getText() != null
                    && !message.getText().isBlank()) {
                return message.getText();
            }
        }
        return null;
    }

    private String memoryInstructions(List<String> memories) {
        StringBuilder result = new StringBuilder(contextPrompt).append('\n');
        for (String memory : memories) {
            if (memory != null && !memory.isBlank()) {
                result.append("- ").append(memory.trim()).append('\n');
            }
        }
        return result.toString().trim();
    }
}
