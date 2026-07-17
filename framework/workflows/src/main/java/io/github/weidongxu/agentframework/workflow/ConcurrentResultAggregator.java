package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;

@FunctionalInterface
public interface ConcurrentResultAggregator {
    List<ChatMessage> aggregate(List<AgentResponse> responses);
}
