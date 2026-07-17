package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoundryMemoryProviderTest {
    @Test
    void recallsAndStoresUsingSessionScope() throws Exception {
        RecordingMemoryClient client = new RecordingMemoryClient();
        FoundryMemoryProvider provider =
                new FoundryMemoryProvider(client, "memory-store");
        AgentSession session = new AgentSession(
                "session-1",
                Collections.emptyMap());
        Agent agent = new StubAgent();

        AIContext context = provider.invoking(new AgentInvokingContext(
                        agent,
                        session,
                        AIContext.builder()
                                .messages(Collections.singletonList(
                                        ChatMessage.user("my preference")))
                                .build()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        provider.invoked(new AgentInvokedContext(
                        agent,
                        session,
                        Collections.singletonList(
                                ChatMessage.user("my preference")),
                        Collections.singletonList(
                                ChatMessage.assistant("noted")),
                        null))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertTrue(context.getInstructions().contains("prefers concise answers"));
        assertEquals("session-1", client.searchScope);
        assertEquals("session-1", client.updateScope);
        assertEquals(2, client.updatedMessages.size());
    }

    @Test
    void storageFiltersDropAllAndSkipEmptyUpdate() throws Exception {
        RecordingMemoryClient client = new RecordingMemoryClient();
        FoundryMemoryProvider provider = new FoundryMemoryProvider(
                client,
                "memory-store",
                new FoundryMemoryProviderOptions()
                        .setStorageRequestMessageFilter(messages -> Collections.emptyList())
                        .setStorageResponseMessageFilter(messages -> Collections.emptyList()));

        provider.invoked(new AgentInvokedContext(
                        new StubAgent(),
                        new AgentSession("session-x", Collections.emptyMap()),
                        Collections.singletonList(ChatMessage.user("hi")),
                        Collections.singletonList(ChatMessage.assistant("hello")),
                        null))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertNull(client.updatedMessages);
    }

    @Test
    void scopeResolverFallbackUsedWhenNoSession() throws Exception {
        RecordingMemoryClient client = new RecordingMemoryClient();
        FoundryMemoryProvider provider = new FoundryMemoryProvider(
                client,
                "memory-store",
                new FoundryMemoryProviderOptions()
                        .setScopeResolver(session -> session == null ? "fallback" : session.getId()));

        provider.invoked(new AgentInvokedContext(
                        new StubAgent(),
                        null,
                        Collections.singletonList(ChatMessage.user("remember this")),
                        Collections.singletonList(ChatMessage.assistant("ok")),
                        null))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("fallback", client.updateScope);
    }

    @Test
    void forwardsCallIdFromSessionStateToMemoryClient() throws Exception {
        RecordingMemoryClient client = new RecordingMemoryClient();
        FoundryMemoryProvider provider =
                new FoundryMemoryProvider(client, "memory-store");
        AgentSession session = new AgentSession(
                "session-2",
                Collections.singletonMap("x-agent-foundry-call-id", "call-abc"));
        Agent agent = new StubAgent();

        provider.invoking(new AgentInvokingContext(
                        agent,
                        session,
                        AIContext.builder()
                                .messages(Collections.singletonList(
                                        ChatMessage.user("my preference")))
                                .build()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        provider.invoked(new AgentInvokedContext(
                        agent,
                        session,
                        Collections.singletonList(ChatMessage.user("my preference")),
                        Collections.singletonList(ChatMessage.assistant("noted")),
                        null))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("call-abc", client.searchCallId);
        assertEquals("call-abc", client.updateCallId);
    }

    private static final class RecordingMemoryClient
            implements FoundryMemoryClient {
        private String searchScope;
        private String updateScope;
        private String searchCallId;
        private String updateCallId;
        private List<ChatMessage> updatedMessages;

        @Override
        public CompletionStage<List<String>> search(
                String storeName,
                String scope,
                String query,
                int maxMemories,
                String callId) {
            searchScope = scope;
            searchCallId = callId;
            return CompletableFuture.completedFuture(
                    Collections.singletonList(
                            "prefers concise answers"));
        }

        @Override
        public CompletionStage<Void> update(
                String storeName,
                String scope,
                List<ChatMessage> messages,
                int updateDelaySeconds,
                String callId) {
            updateScope = scope;
            updateCallId = callId;
            updatedMessages = new ArrayList<>(messages);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class StubAgent implements Agent {
        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
