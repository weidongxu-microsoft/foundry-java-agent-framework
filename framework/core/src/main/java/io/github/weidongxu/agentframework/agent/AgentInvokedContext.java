package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class AgentInvokedContext {
    private final Agent agent;
    private final AgentSession session;
    private final List<ChatMessage> requestMessages;
    private final List<ChatMessage> responseMessages;
    private final Throwable invocationError;

    public AgentInvokedContext(
            Agent agent,
            AgentSession session,
            List<? extends ChatMessage> requestMessages,
            List<? extends ChatMessage> responseMessages,
            Throwable invocationError) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.session = session;
        this.requestMessages = immutableCopy(requestMessages, "requestMessages");
        this.responseMessages = immutableCopy(responseMessages, "responseMessages");
        this.invocationError = invocationError;
    }

    public Agent getAgent() {
        return agent;
    }

    public AgentSession getSession() {
        return session;
    }

    public List<ChatMessage> getRequestMessages() {
        return requestMessages;
    }

    public List<ChatMessage> getResponseMessages() {
        return responseMessages;
    }

    public Throwable getInvocationError() {
        return invocationError;
    }

    public boolean isSuccessful() {
        return invocationError == null;
    }

    private static List<ChatMessage> immutableCopy(
            List<? extends ChatMessage> messages,
            String name) {
        return Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(messages, name)));
    }
}
