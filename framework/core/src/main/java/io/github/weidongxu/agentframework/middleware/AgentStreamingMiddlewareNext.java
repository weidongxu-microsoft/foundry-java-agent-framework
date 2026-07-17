package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;

import java.util.concurrent.Flow;

@FunctionalInterface
public interface AgentStreamingMiddlewareNext {
    Flow.Publisher<AgentResponseUpdate> invoke(AgentMiddlewareContext context);
}
