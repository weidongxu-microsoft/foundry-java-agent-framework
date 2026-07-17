package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageAIContextProviderTest {

    private static final Agent STUB_AGENT = new Agent() {
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
    };

    private static AIContext provide(MessageAIContextProvider provider) throws Exception {
        return provider.invoking(new AgentInvokingContext(STUB_AGENT, new AgentSession(), AIContext.empty()))
                .toCompletableFuture().get();
    }

    @Test
    void staticProviderInjectsFixedMessages() throws Exception {
        AIContext context = provide(new StaticMessageAIContextProvider(
                ChatMessage.system("remember to be concise"),
                ChatMessage.user("prior fact")));
        List<String> texts = context.getMessages().stream()
                .map(ChatMessage::getText).collect(Collectors.toList());
        assertEquals(Arrays.asList("remember to be concise", "prior fact"), texts);
        assertEquals(ChatRole.SYSTEM, context.getMessages().get(0).getRole());
    }

    @Test
    void mergesWithExistingRequestMessages() throws Exception {
        StaticMessageAIContextProvider provider =
                new StaticMessageAIContextProvider(ChatMessage.system("added"));
        AIContext input = AIContext.builder().message(ChatMessage.user("hello")).build();
        AIContext result = provider.invoking(
                new AgentInvokingContext(STUB_AGENT, new AgentSession(), input))
                .toCompletableFuture().get();
        List<String> texts = result.getMessages().stream()
                .map(ChatMessage::getText).collect(Collectors.toList());
        assertEquals(Arrays.asList("hello", "added"), texts);
    }

    @Test
    void customSubclassCanUseContext() throws Exception {
        MessageAIContextProvider provider = new MessageAIContextProvider() {
            @Override
            protected CompletionStage<List<ChatMessage>> provideMessages(AgentInvokingContext context) {
                String id = context.getSession() != null ? context.getSession().getId() : "none";
                return java.util.concurrent.CompletableFuture.completedFuture(
                        java.util.Collections.singletonList(ChatMessage.system("session=" + id)));
            }
        };
        AIContext context = provide(provider);
        assertTrue(context.getMessages().get(0).getText().startsWith("session="));
    }
}
