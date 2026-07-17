package io.github.weidongxu.agentframework.tool;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface Tool {
    String getName();

    String getDescription();

    Map<String, Object> getParametersSchema();

    default ApprovalMode getApprovalMode() {
        return ApprovalMode.NEVER;
    }

    CompletionStage<String> invoke(Map<String, Object> arguments);

    /**
     * Invokes the tool with per-invocation {@link ToolContext} (e.g. the ambient agent session).
     * Defaults to the context-free {@link #invoke(Map)}; session-aware tools override this.
     */
    default CompletionStage<String> invoke(Map<String, Object> arguments, ToolContext context) {
        return invoke(arguments);
    }
}
