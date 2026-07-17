package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkflowRunResult {
    private final List<ChatMessage> messages;
    private final Map<String, AgentResponse> responses;
    private final List<WorkflowEvent> events;

    public WorkflowRunResult(
            List<? extends ChatMessage> messages,
            Map<String, AgentResponse> responses,
            List<WorkflowEvent> events) {
        this.messages = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        messages,
                        "messages")));
        this.responses = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        responses,
                        "responses")));
        this.events = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(events, "events")));
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public Map<String, AgentResponse> getResponses() {
        return responses;
    }

    public List<WorkflowEvent> getEvents() {
        return events;
    }
}
