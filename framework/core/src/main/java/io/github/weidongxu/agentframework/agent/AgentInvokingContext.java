package io.github.weidongxu.agentframework.agent;

import java.util.Objects;

public final class AgentInvokingContext {
    private final Agent agent;
    private final AgentSession session;
    private final AIContext aiContext;

    public AgentInvokingContext(Agent agent, AgentSession session, AIContext aiContext) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.session = session;
        this.aiContext = Objects.requireNonNull(aiContext, "aiContext");
    }

    public Agent getAgent() {
        return agent;
    }

    public AgentSession getSession() {
        return session;
    }

    public AIContext getAIContext() {
        return aiContext;
    }
}
