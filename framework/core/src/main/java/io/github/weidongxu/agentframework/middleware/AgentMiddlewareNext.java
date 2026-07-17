package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.agent.AgentResponse;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AgentMiddlewareNext {
    CompletionStage<AgentResponse> invoke(AgentMiddlewareContext context);
}
