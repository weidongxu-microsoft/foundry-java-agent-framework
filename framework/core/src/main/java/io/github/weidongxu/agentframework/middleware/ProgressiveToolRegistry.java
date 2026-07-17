package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.tool.Tool;

import java.util.List;

public interface ProgressiveToolRegistry {
    List<Tool> getTools();

    /**
     * Atomically adds tools. Re-adding the same instance is a no-op; a different
     * instance with the same name is rejected.
     */
    void addTools(List<? extends Tool> tools);

    /**
     * Removes tools by name. Unknown names are ignored.
     */
    void removeTools(List<String> toolNames);
}
