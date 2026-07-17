package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.chat.ChatMessage;

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

public final class SequentialWorkflow implements Workflow {
    private final List<WorkflowParticipant> participants;

    private SequentialWorkflow(Builder builder) {
        if (builder.participants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Sequential workflow requires participants");
        }
        this.participants = Collections.unmodifiableList(
                new ArrayList<>(builder.participants));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public WorkflowRun start(
            List<ChatMessage> messages,
            WorkflowSession session) {
        Objects.requireNonNull(session, "session");
        AtomicReference<CompletableFuture<?>> active =
                new AtomicReference<>();
        CompletableFuture<WorkflowRunResult> result =
                runStep(
                        0,
                        new ArrayList<>(
                                WorkflowSupport.immutableMessages(messages)),
                        session,
                        new LinkedHashMap<>(),
                        new ArrayList<>(),
                        active)
                        .toCompletableFuture();
        return new WorkflowRun(
                UUID.randomUUID().toString(),
                result,
                () -> {
                    CompletableFuture<?> current = active.get();
                    if (current != null) {
                        current.cancel(true);
                    }
                });
    }

    private CompletionStage<WorkflowRunResult> runStep(
            int index,
            List<ChatMessage> conversation,
            WorkflowSession session,
            Map<String, AgentResponse> responses,
            List<WorkflowEvent> events,
            AtomicReference<CompletableFuture<?>> active) {
        if (index == participants.size()) {
            events.add(WorkflowSupport.event("workflow.completed", null));
            return CompletableFuture.completedFuture(
                    new WorkflowRunResult(
                            conversation,
                            responses,
                            events));
        }
        WorkflowParticipant participant = participants.get(index);
        events.add(WorkflowSupport.event(
                "agent.started",
                participant.getName()));
        return WorkflowSupport.run(
                        participant,
                        conversation,
                        session,
                        AgentRunOptions.empty(),
                        active)
                .thenCompose(response -> {
                    responses.put(participant.getName(), response);
                    conversation.addAll(response.getMessages());
                    events.add(WorkflowSupport.event(
                            "agent.completed",
                            participant.getName()));
                    return runStep(
                            index + 1,
                            conversation,
                            session,
                            responses,
                            events,
                            active);
                });
    }

    public static final class Builder {
        private final List<WorkflowParticipant> participants =
                new ArrayList<>();
        private final Map<String, Agent> names = new LinkedHashMap<>();

        public Builder participant(String name, Agent agent) {
            WorkflowParticipant participant =
                    new WorkflowParticipant(name, agent);
            if (names.putIfAbsent(
                    participant.getName(),
                    participant.getAgent()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate participant: " + name);
            }
            participants.add(participant);
            return this;
        }

        public SequentialWorkflow build() {
            return new SequentialWorkflow(this);
        }
    }
}
