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

public final class GroupChatWorkflow implements Workflow {
    private final Map<String, WorkflowParticipant> participants;
    private final List<String> participantNames;
    private final GroupChatManager manager;
    private final GroupChatTerminationPolicy terminationPolicy;
    private final int maxRounds;

    private GroupChatWorkflow(Builder builder) {
        if (builder.participants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Group chat requires participants");
        }
        this.participants = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.participants));
        this.participantNames = Collections.unmodifiableList(
                new ArrayList<>(participants.keySet()));
        this.manager = Objects.requireNonNull(
                builder.manager,
                "manager");
        this.terminationPolicy = builder.terminationPolicy;
        this.maxRounds = builder.maxRounds;
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
        CompletableFuture<WorkflowRunResult> result = runRound(
                1,
                new ArrayList<>(
                        WorkflowSupport.immutableMessages(messages)),
                session,
                new LinkedHashMap<>(),
                new ArrayList<>(),
                active).toCompletableFuture();
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

    private CompletionStage<WorkflowRunResult> runRound(
            int round,
            List<ChatMessage> conversation,
            WorkflowSession session,
            Map<String, AgentResponse> responses,
            List<WorkflowEvent> events,
            AtomicReference<CompletableFuture<?>> active) {
        if (round > maxRounds) {
            events.add(WorkflowSupport.event(
                    "workflow.max_rounds",
                    null));
            return CompletableFuture.completedFuture(
                    new WorkflowRunResult(
                            conversation,
                            responses,
                            events));
        }
        CompletionStage<String> selection;
        try {
            selection = Objects.requireNonNull(
                    manager.selectNext(
                            WorkflowSupport.immutableMessages(conversation),
                            participantNames,
                            round),
                    "Manager returned null selection stage");
        } catch (Throwable error) {
            return WorkflowSupport.failed(error);
        }
        return selection.thenCompose(name -> {
            WorkflowParticipant participant = participants.get(name);
            if (participant == null) {
                return WorkflowSupport.failed(
                        new IllegalArgumentException(
                                "Manager selected unknown participant: "
                                        + name));
            }
            events.add(WorkflowSupport.event("agent.started", name));
            return WorkflowSupport.run(
                            participant,
                            conversation,
                            session,
                            AgentRunOptions.empty(),
                            active)
                    .thenCompose(response -> {
                        responses.put(name, response);
                        conversation.addAll(response.getMessages());
                        events.add(WorkflowSupport.event(
                                "agent.completed",
                                name));
                        GroupChatContext context =
                                new GroupChatContext(
                                        round,
                                        name,
                                        conversation,
                                        responses);
                        if (terminationPolicy.shouldTerminate(context)) {
                            events.add(WorkflowSupport.event(
                                    "workflow.completed",
                                    null));
                            return CompletableFuture.completedFuture(
                                    new WorkflowRunResult(
                                            conversation,
                                            responses,
                                            events));
                        }
                        return runRound(
                                round + 1,
                                conversation,
                                session,
                                responses,
                                events,
                                active);
                    });
        });
    }

    public static final class Builder {
        private final Map<String, WorkflowParticipant> participants =
                new LinkedHashMap<>();
        private GroupChatManager manager;
        private GroupChatTerminationPolicy terminationPolicy =
                context -> false;
        private int maxRounds = 10;

        public Builder participant(String name, Agent agent) {
            WorkflowParticipant participant =
                    new WorkflowParticipant(name, agent);
            if (participants.putIfAbsent(
                    participant.getName(),
                    participant) != null) {
                throw new IllegalArgumentException(
                        "Duplicate participant: " + name);
            }
            return this;
        }

        public Builder manager(GroupChatManager manager) {
            this.manager = Objects.requireNonNull(manager, "manager");
            return this;
        }

        public Builder terminationPolicy(
                GroupChatTerminationPolicy terminationPolicy) {
            this.terminationPolicy = Objects.requireNonNull(
                    terminationPolicy,
                    "terminationPolicy");
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            if (maxRounds < 1) {
                throw new IllegalArgumentException(
                        "maxRounds must be positive");
            }
            this.maxRounds = maxRounds;
            return this;
        }

        public GroupChatWorkflow build() {
            return new GroupChatWorkflow(this);
        }
    }
}
