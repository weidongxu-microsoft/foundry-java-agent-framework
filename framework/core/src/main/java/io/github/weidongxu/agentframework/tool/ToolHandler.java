package io.github.weidongxu.agentframework.tool;

import java.util.Map;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolHandler {
    CompletionStage<String> invoke(Map<String, Object> arguments);
}
