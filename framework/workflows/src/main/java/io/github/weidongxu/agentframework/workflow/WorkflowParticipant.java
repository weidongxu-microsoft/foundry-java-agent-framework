package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.Agent;

import java.util.Objects;

public final class WorkflowParticipant {
    private final String name;
    private final Agent agent;

    public WorkflowParticipant(String name, Agent agent) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        this.name = name;
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    public String getName() {
        return name;
    }

    public Agent getAgent() {
        return agent;
    }
}
