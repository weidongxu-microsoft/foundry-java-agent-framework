package io.github.weidongxu.agentframework.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Hosted file-search tool. The host searches one or more vector stores and grounds
 * the response with matching files. Parity with .NET {@code HostedFileSearchTool}
 * and Python {@code SupportsFileSearchTool}.
 */
public final class HostedFileSearchTool extends HostedTool {

    private final List<String> vectorStoreIds;
    private final Integer maxResults;

    public HostedFileSearchTool(List<String> vectorStoreIds) {
        this(vectorStoreIds, null);
    }

    /**
     * @param vectorStoreIds the vector stores to search; must be non-empty.
     * @param maxResults     an optional cap on results, or {@code null} for the host default.
     */
    public HostedFileSearchTool(List<String> vectorStoreIds, Integer maxResults) {
        super("file_search");
        Objects.requireNonNull(vectorStoreIds, "vectorStoreIds");
        if (vectorStoreIds.isEmpty()) {
            throw new IllegalArgumentException("vectorStoreIds cannot be empty");
        }
        if (maxResults != null && maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive");
        }
        this.vectorStoreIds = Collections.unmodifiableList(new ArrayList<>(vectorStoreIds));
        this.maxResults = maxResults;
    }

    public List<String> getVectorStoreIds() {
        return vectorStoreIds;
    }

    /** @return the result cap, or {@code null} for the host default. */
    public Integer getMaxResults() {
        return maxResults;
    }
}
