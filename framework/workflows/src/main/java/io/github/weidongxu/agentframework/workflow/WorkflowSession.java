package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentSession;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class WorkflowSession {
    private final String id;
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Map<String, AgentSession> agentSessions =
            new LinkedHashMap<>();
    private final Map<String, CompletableFuture<AgentSession>> creating =
            new LinkedHashMap<>();

    public WorkflowSession() {
        this(UUID.randomUUID().toString());
    }

    public WorkflowSession(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public synchronized Object get(String key) {
        return state.get(Objects.requireNonNull(key, "key"));
    }

    public synchronized void put(String key, Object value) {
        state.put(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value"));
    }

    public synchronized Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    CompletionStage<AgentSession> agentSession(
            String participant,
            Agent agent) {
        CompletableFuture<AgentSession> future;
        synchronized (this) {
            AgentSession existing = agentSessions.get(participant);
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }
            future = creating.get(participant);
            if (future != null) {
                return future;
            }
            future = new CompletableFuture<>();
            creating.put(participant, future);
        }
        CompletableFuture<AgentSession> target = future;
        CompletionStage<AgentSession> creation;
        try {
            creation = Objects.requireNonNull(
                    agent.createSession(),
                    "Agent returned null session stage");
        } catch (Throwable error) {
            synchronized (this) {
                creating.remove(participant);
            }
            target.completeExceptionally(error);
            return target;
        }
        creation.whenComplete((created, error) -> {
            Throwable completionError = error;
            if (completionError == null && created == null) {
                completionError = new NullPointerException(
                        "Agent created null session");
            }
            synchronized (WorkflowSession.this) {
                creating.remove(participant);
                if (completionError == null) {
                    agentSessions.put(participant, created);
                }
            }
            if (completionError != null) {
                target.completeExceptionally(completionError);
            } else {
                target.complete(created);
            }
        });
        return target;
    }
}
