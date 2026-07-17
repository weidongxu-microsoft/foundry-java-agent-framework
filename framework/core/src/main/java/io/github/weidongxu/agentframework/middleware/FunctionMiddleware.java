package io.github.weidongxu.agentframework.middleware;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface FunctionMiddleware {
    CompletionStage<String> invoke(
            FunctionInvocationContext context,
            FunctionMiddlewareNext next);
}
