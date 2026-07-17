package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChatHistoryProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getAndSetMessagesRoundTripAcrossContentTypes(@TempDir Path dir) {
        FileChatHistoryProvider provider = new FileChatHistoryProvider(dir, objectMapper);
        AgentSession session = new AgentSession();
        provider.setMessages(session, List.of(
                ChatMessage.user("call the tool"),
                ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(new FunctionCallContent("call_1", "lookup", "{\"q\":\"x\"}"))
                        .build(),
                ChatMessage.builder(ChatRole.TOOL)
                        .addContent(new FunctionResultContent("call_1", "result", false))
                        .build()));

        List<ChatMessage> loaded = provider.getMessages(session);
        assertEquals(3, loaded.size());
        assertEquals("call the tool", loaded.get(0).getText());
        FunctionCallContent call = (FunctionCallContent) loaded.get(1).getContents().get(0);
        assertEquals("lookup", call.getName());
        FunctionResultContent result = (FunctionResultContent) loaded.get(2).getContents().get(0);
        assertEquals("result", result.getResult());
    }

    @Test
    void persistsAcrossProviderInstances(@TempDir Path dir) {
        AgentSession session = new AgentSession("sess-abc", null, java.util.Map.of());
        new FileChatHistoryProvider(dir, objectMapper)
                .setMessages(session, List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello")));

        // A fresh instance (simulating a restarted pod) reads the durable history by session id.
        List<ChatMessage> loaded =
                new FileChatHistoryProvider(dir, objectMapper).getMessages(session);
        assertEquals(2, loaded.size());
        assertEquals("hi", loaded.get(0).getText());
        assertEquals("hello", loaded.get(1).getText());
    }

    @Test
    void invokedAppendsRequestAndResponseAndInvokingReplays(@TempDir Path dir) throws Exception {
        FileChatHistoryProvider provider = new FileChatHistoryProvider(dir, objectMapper);
        Agent agent = new StubAgent();
        AgentSession session = new AgentSession();

        // First turn.
        provider.invoked(new AgentInvokedContext(
                agent, session,
                List.of(ChatMessage.user("q1")),
                List.of(ChatMessage.assistant("a1")),
                null)).toCompletableFuture().get();
        // Second turn appends rather than replaces.
        provider.invoked(new AgentInvokedContext(
                agent, session,
                List.of(ChatMessage.user("q2")),
                List.of(ChatMessage.assistant("a2")),
                null)).toCompletableFuture().get();

        List<ChatMessage> replayed = provider.invoking(new AgentInvokingContext(
                agent, session, AIContext.builder()
                        .message(ChatMessage.user("q3")).build()))
                .toCompletableFuture().get();

        // Stored history (q1,a1,q2,a2) is prepended to the new request message (q3).
        assertEquals(5, replayed.size());
        assertEquals("q1", replayed.get(0).getText());
        assertEquals("a2", replayed.get(3).getText());
        assertEquals("q3", replayed.get(4).getText());
    }

    @Test
    void failedInvocationDoesNotPersist(@TempDir Path dir) throws Exception {
        FileChatHistoryProvider provider = new FileChatHistoryProvider(dir, objectMapper);
        Agent agent = new StubAgent();
        AgentSession session = new AgentSession();

        provider.invoked(new AgentInvokedContext(
                agent, session,
                List.of(ChatMessage.user("q1")),
                List.of(),
                new IllegalStateException("boom"))).toCompletableFuture().get();

        assertTrue(provider.getMessages(session).isEmpty());
    }

    @Test
    void nullSessionYieldsEmptyHistory(@TempDir Path dir) {
        FileChatHistoryProvider provider = new FileChatHistoryProvider(dir, objectMapper);
        assertTrue(provider.getMessages(null).isEmpty());
    }

    /** Minimal {@link Agent} whose run methods are never exercised by these tests. */
    private static final class StubAgent implements Agent {
        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
