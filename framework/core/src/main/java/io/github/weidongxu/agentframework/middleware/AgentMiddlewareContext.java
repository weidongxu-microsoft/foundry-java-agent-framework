package io.github.weidongxu.agentframework.middleware;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentMiddlewareContext {
    private final Agent agent;
    private List<ChatMessage> messages;
    private AgentSession session;
    private AgentRunOptions options;
    private final boolean streaming;
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public AgentMiddlewareContext(
            Agent agent,
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options,
            boolean streaming) {
        this.agent = Objects.requireNonNull(agent, "agent");
        setMessages(messages);
        this.session = session;
        this.options = options == null ? AgentRunOptions.empty() : options;
        this.streaming = streaming;
    }

    public Agent getAgent() {
        return agent;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<? extends ChatMessage> messages) {
        this.messages = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(messages, "messages")));
    }

    public AgentSession getSession() {
        return session;
    }

    public void setSession(AgentSession session) {
        this.session = session;
    }

    public AgentRunOptions getOptions() {
        return options;
    }

    public void setOptions(AgentRunOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public boolean isStreaming() {
        return streaming;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
