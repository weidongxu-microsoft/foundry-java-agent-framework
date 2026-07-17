package io.github.weidongxu.agentframework.tool;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Function-tool body that also receives the per-invocation {@link ToolContext} (e.g. the ambient
 * {@link io.github.weidongxu.agentframework.agent.AgentSession}). Use this instead of
 * {@link ToolHandler} when the tool needs to partition state per end-user/session.
 */
@FunctionalInterface
public interface ContextualToolHandler {
    CompletionStage<String> invoke(Map<String, Object> arguments, ToolContext context);
}
