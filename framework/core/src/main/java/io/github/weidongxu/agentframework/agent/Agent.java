package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface Agent {
    default String getId() {
        return null;
    }

    default String getName() {
        return null;
    }

    default String getDescription() {
        return null;
    }

    default CompletionStage<AgentSession> createSession() {
        return CompletableFuture.completedFuture(new AgentSession());
    }

    default CompletionStage<String> serializeSession(AgentSession session) {
        try {
            return CompletableFuture.completedFuture(
                    AgentSessionCodec.standard().serialize(session));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    default CompletionStage<AgentSession> deserializeSession(String serializedSession) {
        try {
            return CompletableFuture.completedFuture(
                    AgentSessionCodec.standard().deserialize(serializedSession));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    CompletionStage<AgentResponse> run(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options);

    Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options);

    default CompletionStage<AgentResponse> run(String message) {
        return run(
                Collections.singletonList(ChatMessage.user(message)),
                null,
                AgentRunOptions.empty());
    }

    default CompletionStage<AgentResponse> run(List<ChatMessage> messages) {
        return run(messages, null, AgentRunOptions.empty());
    }

    default Flow.Publisher<AgentResponseUpdate> runStreaming(String message) {
        return runStreaming(
                Collections.singletonList(ChatMessage.user(message)),
                null,
                AgentRunOptions.empty());
    }
}
