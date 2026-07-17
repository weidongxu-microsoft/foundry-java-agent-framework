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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ConcurrentWorkflow implements Workflow {
    private final List<WorkflowParticipant> participants;
    private final ConcurrentResultAggregator aggregator;

    private ConcurrentWorkflow(Builder builder) {
        if (builder.participants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Concurrent workflow requires participants");
        }
        this.participants = Collections.unmodifiableList(
                new ArrayList<>(builder.participants));
        this.aggregator = builder.aggregator;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public WorkflowRun start(
            List<ChatMessage> messages,
            WorkflowSession session) {
        Objects.requireNonNull(session, "session");
        List<ChatMessage> input =
                WorkflowSupport.immutableMessages(messages);
        List<CompletableFuture<AgentResponse>> futures =
                new ArrayList<>();
        List<AtomicReference<CompletableFuture<?>>> active =
                new ArrayList<>();
        List<WorkflowEvent> events =
                Collections.synchronizedList(new ArrayList<>());
        for (WorkflowParticipant participant : participants) {
            events.add(WorkflowSupport.event(
                    "agent.started",
                    participant.getName()));
            AtomicReference<CompletableFuture<?>> current =
                    new AtomicReference<>();
            active.add(current);
            futures.add(WorkflowSupport.run(
                            participant,
                            input,
                            session,
                            AgentRunOptions.empty(),
                            current)
                    .toCompletableFuture());
        }
        CompletableFuture<WorkflowRunResult> result =
                new CompletableFuture<>();
        AtomicBoolean failed = new AtomicBoolean();
        futures.forEach(future -> future.whenComplete((response, error) -> {
            if (error != null && failed.compareAndSet(false, true)) {
                futures.forEach(sibling -> {
                    if (sibling != future) {
                        sibling.cancel(true);
                    }
                });
                active.forEach(current -> {
                    CompletableFuture<?> running = current.get();
                    if (running != null) {
                        running.cancel(true);
                    }
                });
                result.completeExceptionally(error);
            }
        }));
        CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, error) -> {
                    if (error != null || result.isDone()) {
                        return;
                    }
                    List<AgentResponse> ordered = new ArrayList<>();
                    Map<String, AgentResponse> responses =
                            new LinkedHashMap<>();
                    for (int index = 0;
                            index < participants.size();
                            index++) {
                        AgentResponse response = futures.get(index).join();
                        WorkflowParticipant participant =
                                participants.get(index);
                        ordered.add(response);
                        responses.put(participant.getName(), response);
                        events.add(WorkflowSupport.event(
                                "agent.completed",
                                participant.getName()));
                    }
                    try {
                        List<ChatMessage> aggregated =
                                WorkflowSupport.immutableMessages(
                                        aggregator.aggregate(
                                                Collections.unmodifiableList(
                                                        ordered)));
                        events.add(WorkflowSupport.event(
                                "workflow.completed",
                                null));
                        result.complete(new WorkflowRunResult(
                                aggregated,
                                responses,
                                new ArrayList<>(events)));
                    } catch (Throwable aggregationError) {
                        result.completeExceptionally(aggregationError);
                    }
                });
        return new WorkflowRun(
                UUID.randomUUID().toString(),
                result,
                () -> {
                    active.forEach(current -> {
                        CompletableFuture<?> running = current.get();
                        if (running != null) {
                            running.cancel(true);
                        }
                    });
                    futures.forEach(future -> future.cancel(true));
                });
    }

    public static final class Builder {
        private final List<WorkflowParticipant> participants =
                new ArrayList<>();
        private final Map<String, Agent> names = new LinkedHashMap<>();
        private ConcurrentResultAggregator aggregator = responses -> {
            List<ChatMessage> messages = new ArrayList<>();
            responses.forEach(response ->
                    messages.addAll(response.getMessages()));
            return messages;
        };

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

        public Builder aggregator(
                ConcurrentResultAggregator aggregator) {
            this.aggregator = Objects.requireNonNull(
                    aggregator,
                    "aggregator");
            return this;
        }

        public ConcurrentWorkflow build() {
            return new ConcurrentWorkflow(this);
        }
    }
}
