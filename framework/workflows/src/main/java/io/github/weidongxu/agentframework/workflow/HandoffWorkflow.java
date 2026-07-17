package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

public final class HandoffWorkflow implements Workflow {
    private final String stateKey;
    private final Map<String, WorkflowParticipant> participants;
    private final String initialParticipant;
    private final int maxHandoffs;

    private HandoffWorkflow(Builder builder) {
        if (builder.participants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Handoff workflow requires participants");
        }
        if (!builder.participants.containsKey(
                builder.initialParticipant)) {
            throw new IllegalArgumentException(
                    "Initial participant is not registered");
        }
        this.stateKey = "workflow.handoff."
                + (builder.id == null
                        ? UUID.randomUUID()
                        : builder.id);
        this.participants = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.participants));
        this.initialParticipant = builder.initialParticipant;
        this.maxHandoffs = builder.maxHandoffs;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public WorkflowRun start(
            List<ChatMessage> messages,
            WorkflowSession session) {
        Objects.requireNonNull(session, "session");
        AtomicReference<CompletableFuture<?>> activeFuture =
                new AtomicReference<>();
        Object restored = session.get(stateKey + ".active");
        String active = restored instanceof String
                && participants.containsKey(restored)
                ? (String) restored
                : initialParticipant;
        Object restoredPrevious =
                session.get(stateKey + ".previous");
        String previous = restoredPrevious instanceof String
                ? (String) restoredPrevious
                : null;
        CompletableFuture<WorkflowRunResult> result = runParticipant(
                active,
                previous,
                0,
                new ArrayList<>(
                        WorkflowSupport.immutableMessages(messages)),
                session,
                new LinkedHashMap<>(),
                new ArrayList<>(),
                activeFuture).toCompletableFuture();
        return new WorkflowRun(
                UUID.randomUUID().toString(),
                result,
                () -> {
                    CompletableFuture<?> running = activeFuture.get();
                    if (running != null) {
                        running.cancel(true);
                    }
                });
    }

    private CompletionStage<WorkflowRunResult> runParticipant(
            String active,
            String previous,
            int handoffs,
            List<ChatMessage> conversation,
            WorkflowSession session,
            Map<String, AgentResponse> responses,
            List<WorkflowEvent> events,
            AtomicReference<CompletableFuture<?>> activeFuture) {
        if (handoffs > maxHandoffs) {
            return WorkflowSupport.failed(new IllegalStateException(
                    "Handoff workflow exceeded " + maxHandoffs
                            + " handoffs"));
        }
        WorkflowParticipant participant = participants.get(active);
        AtomicReference<String> requested = new AtomicReference<>();
        ChatOptions.Builder chatOptions = ChatOptions.builder();
        handoffTools(active, requested).forEach(chatOptions::tool);
        events.add(WorkflowSupport.event("agent.started", active));
        return WorkflowSupport.run(
                        participant,
                        conversation,
                        session,
                        AgentRunOptions.builder()
                                .chatOptions(chatOptions.build())
                                .build(),
                        activeFuture)
                .thenCompose(response -> {
                    responses.put(active, response);
                    conversation.addAll(response.getMessages());
                    if (previous != null) {
                        session.put(stateKey + ".previous", previous);
                    }
                    session.put(stateKey + ".active", active);
                    events.add(WorkflowSupport.event(
                            "agent.completed",
                            active));
                    String next = requested.get();
                    if (next == null) {
                        events.add(WorkflowSupport.event(
                                "workflow.completed",
                                null));
                        return CompletableFuture.completedFuture(
                                new WorkflowRunResult(
                                        conversation,
                                        responses,
                                        events));
                    }
                    events.add(WorkflowSupport.event(
                            "agent.handoff",
                            next));
                    return runParticipant(
                            next,
                            active,
                            handoffs + 1,
                            conversation,
                            session,
                            responses,
                            events,
                            activeFuture);
                });
    }

    private List<Tool> handoffTools(
            String active,
            AtomicReference<String> requested) {
        List<Tool> tools = new ArrayList<>();
        participants.forEach((name, participant) -> {
            if (!name.equals(active)) {
                tools.add(new FunctionTool(
                        "transfer_to_" + name,
                        "Transfer the conversation to " + name,
                        Map.of(
                                "type", "object",
                                "properties", Collections.emptyMap()),
                        arguments -> {
                            String existing = requested.get();
                            if (existing == null
                                    && requested.compareAndSet(null, name)) {
                                return CompletableFuture.completedFuture(
                                        "Transferred to " + name);
                            }
                            if (name.equals(requested.get())) {
                                return CompletableFuture.completedFuture(
                                        "Transferred to " + name);
                            }
                            return WorkflowSupport.failed(
                                    new IllegalStateException(
                                            "Only one handoff is allowed per turn"));
                        }));
            }
        });
        return tools;
    }

    public static final class Builder {
        private final Map<String, WorkflowParticipant> participants =
                new LinkedHashMap<>();
        private String initialParticipant;
        private String id;
        private int maxHandoffs = 8;

        public Builder id(String id) {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id cannot be blank");
            }
            this.id = id;
            return this;
        }

        public Builder participant(String name, Agent agent) {
            validateName(name);
            if (participants.putIfAbsent(
                    name,
                    new WorkflowParticipant(name, agent)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate participant: " + name);
            }
            if (initialParticipant == null) {
                initialParticipant = name;
            }
            return this;
        }

        public Builder initialParticipant(String name) {
            this.initialParticipant = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder maxHandoffs(int maxHandoffs) {
            if (maxHandoffs < 0) {
                throw new IllegalArgumentException(
                        "maxHandoffs cannot be negative");
            }
            this.maxHandoffs = maxHandoffs;
            return this;
        }

        public HandoffWorkflow build() {
            return new HandoffWorkflow(this);
        }

        private static void validateName(String name) {
            Objects.requireNonNull(name, "name");
            if (!name.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException(
                        "Participant names must match [A-Za-z0-9_-]+");
            }
        }
    }
}
