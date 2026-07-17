package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;
import java.util.function.Function;

/**
 * Options for {@link FoundryMemoryProvider}, mirroring the .NET {@code FoundryMemoryProviderOptions}.
 *
 * <p>Beyond recall/update tuning this exposes optional <b>message-filter hooks</b> so callers can
 * shape which messages feed recall search and memory extraction — e.g. drop pleasantries or
 * secret-bearing turns, or cap item size — without subclassing the provider. Filters are applied
 * per side (request vs response), exactly as in the .NET provider; a {@code null} filter means
 * "pass through".</p>
 */
public final class FoundryMemoryProviderOptions {

    private int maxMemories = 5;
    private int updateDelaySeconds = 1;
    private String contextPrompt = "Relevant memory:";
    private Function<AgentSession, String> scopeResolver =
            session -> session == null ? null : session.getId();
    private Function<List<ChatMessage>, List<ChatMessage>> searchInputMessageFilter;
    private Function<List<ChatMessage>, List<ChatMessage>> storageRequestMessageFilter;
    private Function<List<ChatMessage>, List<ChatMessage>> storageResponseMessageFilter;

    /** Maximum number of memories injected on recall (default {@code 5}). */
    public int getMaxMemories() {
        return maxMemories;
    }

    public FoundryMemoryProviderOptions setMaxMemories(int maxMemories) {
        this.maxMemories = maxMemories;
        return this;
    }

    /** Server-side extraction delay in seconds passed to {@code update} (default {@code 1}). */
    public int getUpdateDelaySeconds() {
        return updateDelaySeconds;
    }

    public FoundryMemoryProviderOptions setUpdateDelaySeconds(int updateDelaySeconds) {
        this.updateDelaySeconds = updateDelaySeconds;
        return this;
    }

    /** Prefix line prepended to injected memories (default {@code "Relevant memory:"}). */
    public String getContextPrompt() {
        return contextPrompt;
    }

    public FoundryMemoryProviderOptions setContextPrompt(String contextPrompt) {
        this.contextPrompt = contextPrompt;
        return this;
    }

    /**
     * Resolves the memory scope from the (possibly {@code null}) agent session. The default returns
     * the session id, or {@code null} when there is no session — supply a fallback resolver to serve
     * unscoped/local calls.
     */
    public Function<AgentSession, String> getScopeResolver() {
        return scopeResolver;
    }

    public FoundryMemoryProviderOptions setScopeResolver(Function<AgentSession, String> scopeResolver) {
        this.scopeResolver = scopeResolver;
        return this;
    }

    /** Optional filter applied to messages before deriving the recall query. */
    public Function<List<ChatMessage>, List<ChatMessage>> getSearchInputMessageFilter() {
        return searchInputMessageFilter;
    }

    public FoundryMemoryProviderOptions setSearchInputMessageFilter(
            Function<List<ChatMessage>, List<ChatMessage>> searchInputMessageFilter) {
        this.searchInputMessageFilter = searchInputMessageFilter;
        return this;
    }

    /** Optional filter applied to request (user/system) messages before storage. */
    public Function<List<ChatMessage>, List<ChatMessage>> getStorageRequestMessageFilter() {
        return storageRequestMessageFilter;
    }

    public FoundryMemoryProviderOptions setStorageRequestMessageFilter(
            Function<List<ChatMessage>, List<ChatMessage>> storageRequestMessageFilter) {
        this.storageRequestMessageFilter = storageRequestMessageFilter;
        return this;
    }

    /** Optional filter applied to response (assistant) messages before storage. */
    public Function<List<ChatMessage>, List<ChatMessage>> getStorageResponseMessageFilter() {
        return storageResponseMessageFilter;
    }

    public FoundryMemoryProviderOptions setStorageResponseMessageFilter(
            Function<List<ChatMessage>, List<ChatMessage>> storageResponseMessageFilter) {
        this.storageResponseMessageFilter = storageResponseMessageFilter;
        return this;
    }
}
