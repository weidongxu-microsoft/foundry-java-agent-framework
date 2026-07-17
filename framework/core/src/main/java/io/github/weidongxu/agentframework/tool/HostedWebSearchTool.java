package io.github.weidongxu.agentframework.tool;

/**
 * Hosted web-search tool. The model host performs the search and grounds the
 * response with results. Parity with .NET {@code HostedWebSearchTool} and Python
 * {@code SupportsWebSearchTool}.
 */
public final class HostedWebSearchTool extends HostedTool {

    /** How much search context the host should retrieve. */
    public enum SearchContextSize {
        LOW,
        MEDIUM,
        HIGH
    }

    private final SearchContextSize searchContextSize;

    public HostedWebSearchTool() {
        this(null);
    }

    public HostedWebSearchTool(SearchContextSize searchContextSize) {
        super("web_search");
        this.searchContextSize = searchContextSize;
    }

    /** @return the configured context size, or {@code null} for the host default. */
    public SearchContextSize getSearchContextSize() {
        return searchContextSize;
    }
}
