package io.github.weidongxu.agentframework.middleware;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface FunctionMiddlewareNext {
    CompletionStage<String> invoke(FunctionInvocationContext context);
}
