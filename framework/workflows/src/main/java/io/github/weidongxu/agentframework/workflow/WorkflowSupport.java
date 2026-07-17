package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

final class WorkflowSupport {
    private WorkflowSupport() {
    }

    static CompletionStage<AgentResponse> run(
            WorkflowParticipant participant,
            List<ChatMessage> messages,
            WorkflowSession session,
            AgentRunOptions options,
            AtomicReference<CompletableFuture<?>> active) {
        CompletionStage<io.github.weidongxu.agentframework.agent.AgentSession>
                sessionStage = session.agentSession(
                        participant.getName(),
                        participant.getAgent());
        active.set(sessionStage.toCompletableFuture());
        return sessionStage.thenCompose(agentSession -> {
            CompletionStage<AgentResponse> runStage =
                    participant.getAgent().run(
                            immutableMessages(messages),
                            agentSession,
                            options);
            active.set(runStage.toCompletableFuture());
            return runStage;
        });
    }

    static WorkflowEvent event(String type, String participant) {
        return new WorkflowEvent(type, participant, Instant.now());
    }

    static List<ChatMessage> immutableMessages(
            List<? extends ChatMessage> messages) {
        return Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        messages,
                        "messages")));
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }
}
