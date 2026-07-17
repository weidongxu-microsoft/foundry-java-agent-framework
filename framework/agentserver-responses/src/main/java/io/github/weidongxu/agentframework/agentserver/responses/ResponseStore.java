package io.github.weidongxu.agentframework.agentserver.responses;

import java.util.Optional;

/**
 * Persistence layer for issued {@code /responses} records, backing the response-lifecycle routes.
 *
 * <p>Mirrors the Microsoft Agent Framework AgentServer SDK's response persistence layer: in .NET
 * ({@code Azure.AI.AgentServer}) and Python ({@code azure-ai-agentserver-responses}) the SDK
 * auto-registers {@code GET /responses/{id}}, {@code POST /responses/{id}/cancel},
 * {@code DELETE /responses/{id}} and {@code GET /responses/{id}/input_items} against this store — the
 * agent handler only writes {@code POST /responses}. The default is in-memory
 * ({@link InMemoryResponseStore}); a durable backend (file/Cosmos/Redis) can implement this SPI.
 */
public interface ResponseStore {

    /** Persists (or replaces) a response record keyed by its id. */
    void save(StoredResponse response);

    /** @return the stored response for {@code id}, or empty if unknown. */
    Optional<StoredResponse> get(String id);

    /**
     * Removes the stored response.
     *
     * @return {@code true} if a record was removed, {@code false} if {@code id} was unknown.
     */
    boolean delete(String id);
}
