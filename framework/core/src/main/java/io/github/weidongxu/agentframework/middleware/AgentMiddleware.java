package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface AgentMiddleware {
    default CompletionStage<AgentResponse> invoke(
            AgentMiddlewareContext context,
            AgentMiddlewareNext next) {
        return next.invoke(context);
    }

    default Flow.Publisher<AgentResponseUpdate> invokeStreaming(
            AgentMiddlewareContext context,
            AgentStreamingMiddlewareNext next) {
        return next.invoke(context);
    }
}
