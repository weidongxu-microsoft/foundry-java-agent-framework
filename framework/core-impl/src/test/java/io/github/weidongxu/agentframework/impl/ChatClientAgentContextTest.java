package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatClientAgentContextTest {
    @Test
    void inMemoryHistoryLoadsAndStoresConversation() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .build();
        AgentSession session = new AgentSession();

        agent.run(Collections.singletonList(ChatMessage.user("first")), session, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        agent.run(Collections.singletonList(ChatMessage.user("second")), session, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, client.requests.size());
        assertEquals(3, client.requests.get(1).size());
        assertEquals("first", client.requests.get(1).get(0).getText());
        assertEquals("answer-1", client.requests.get(1).get(1).getText());
        assertEquals("second", client.requests.get(1).get(2).getText());
        assertEquals(4, history.getMessages(session).size());
    }

    @Test
    void serializedSessionRestoresInMemoryHistory() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .build();
        AgentSession session = new AgentSession();

        agent.run(Collections.singletonList(ChatMessage.user("first")), session, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        String serialized = agent.serializeSession(session)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        AgentSession restored = agent.deserializeSession(serialized)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        agent.run(Collections.singletonList(ChatMessage.user("second")), restored, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(3, client.requests.get(1).size());
        assertEquals("first", client.requests.get(1).get(0).getText());
        assertEquals("answer-1", client.requests.get(1).get(1).getText());
        assertEquals("second", client.requests.get(1).get(2).getText());
    }

    @Test
    void contextProviderAddsInstructionsMessagesAndTools() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        FunctionTool contextualTool = new FunctionTool(
                "context-tool",
                "Context tool",
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture("ok"));
        RecordingContextProvider provider =
                new RecordingContextProvider(contextualTool);
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .instructions("base")
                .aiContextProvider(provider)
                .build();

        agent.run(Collections.singletonList(ChatMessage.user("question")), null, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("base\ncontext", client.options.get(0).getInstructions());
        assertEquals(Collections.singletonList(contextualTool), client.options.get(0).getTools());
        assertEquals(2, client.requests.get(0).size());
        assertEquals("context message", client.requests.get(0).get(1).getText());
        assertNotNull(provider.invoked.get());
        assertEquals("question", provider.invoked.get().getRequestMessages().get(0).getText());
        assertEquals("answer-1", provider.invoked.get().getResponseMessages().get(0).getText());
    }

    @Test
    void streamingStoresResponseAfterCompletion() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .build();
        AgentSession session = new AgentSession();
        CompletionSubscriber subscriber = new CompletionSubscriber();

        agent.runStreaming(
                        Collections.singletonList(ChatMessage.user("stream")),
                        session,
                        null)
                .subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        assertEquals(2, history.getMessages(session).size());
        assertEquals("stream", history.getMessages(session).get(0).getText());
        assertEquals("partial", history.getMessages(session).get(1).getText());
    }

    @Test
    void failureIsReportedToOverridingProvider() {
        IllegalStateException failure = new IllegalStateException("model failed");
        RecordingContextProvider provider = new RecordingContextProvider(null) {
            @Override
            public CompletionStage<Void> invoked(AgentInvokedContext context) {
                invoked.set(context);
                return CompletableFuture.completedFuture(null);
            }
        };
        ChatClient failingClient = new RecordingChatClient() {
            @Override
            public CompletionStage<ChatResponse> getResponse(
                    List<ChatMessage> messages,
                    ChatOptions options) {
                CompletableFuture<ChatResponse> result = new CompletableFuture<>();
                result.completeExceptionally(failure);
                return result;
            }
        };
        ChatClientAgent agent = ChatClientAgent.builder(failingClient)
                .aiContextProvider(provider)
                .build();

        assertThrows(
                Exception.class,
                () -> agent.run(Collections.singletonList(ChatMessage.user("fail")), null, null)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertEquals(failure, provider.invoked.get().getInvocationError());
    }

    @Test
    void duplicateProviderStateKeysAreRejected() {
        AIContextProvider first = new KeyedContextProvider("shared");
        AIContextProvider second = new KeyedContextProvider("shared");

        assertThrows(
                IllegalArgumentException.class,
                () -> ChatClientAgent.builder(new RecordingChatClient())
                        .aiContextProvider(first)
                        .aiContextProvider(second)
                        .build());
    }

    @Test
    void preparationFailureIsReportedToStartedProvider() {
        IllegalStateException failure = new IllegalStateException("context failed");
        AtomicReference<AgentInvokedContext> invoked = new AtomicReference<>();
        AIContextProvider provider = new AIContextProvider() {
            @Override
            protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
                CompletableFuture<AIContext> result = new CompletableFuture<>();
                result.completeExceptionally(failure);
                return result;
            }

            @Override
            public CompletionStage<Void> invoked(AgentInvokedContext context) {
                invoked.set(context);
                return CompletableFuture.completedFuture(null);
            }
        };
        ChatClientAgent agent = ChatClientAgent.builder(new RecordingChatClient())
                .aiContextProvider(provider)
                .build();

        assertThrows(
                Exception.class,
                () -> agent.run(Collections.singletonList(ChatMessage.user("fail")), null, null)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertEquals(failure, invoked.get().getInvocationError());
    }

    @Test
    void explicitEmptyToolsOverrideClearsDefaults() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .tool(new FunctionTool(
                        "default-tool",
                        "Default",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("ok")))
                .build();
        AgentRunOptions options = AgentRunOptions.builder()
                .chatOptions(ChatOptions.builder().clearTools().build())
                .build();

        agent.run(Collections.singletonList(ChatMessage.user("hello")), null, options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertTrue(client.options.get(0).getTools().isEmpty());
    }

    @Test
    void nullResponseIsNotStoredAsSuccessfulHistory() {
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        AgentSession session = new AgentSession();
        RecordingChatClient client = new RecordingChatClient() {
            @Override
            public CompletionStage<ChatResponse> getResponse(
                    List<ChatMessage> messages,
                    ChatOptions options) {
                return CompletableFuture.completedFuture(null);
            }
        };
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .build();

        assertThrows(
                Exception.class,
                () -> agent.run(
                                Collections.singletonList(ChatMessage.user("hello")),
                                session,
                                null)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertTrue(history.getMessages(session).isEmpty());
    }

    @Test
    void streamingToolLoopStoresCoherentUserAndFinalAnswerHistory() throws Exception {
        ToolLoopStreamingClient inner = new ToolLoopStreamingClient();
        FunctionInvokingChatClient toolClient = new FunctionInvokingChatClient(inner);
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        ChatClientAgent agent = ChatClientAgent.builder(toolClient)
                .chatHistoryProvider(history)
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("tool result")))
                .build();
        AgentSession session = new AgentSession();
        CompletionSubscriber subscriber = new CompletionSubscriber();

        agent.runStreaming(
                        Collections.singletonList(ChatMessage.user("use a tool")),
                        session,
                        null)
                .subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        List<ChatMessage> stored = history.getMessages(session);
        assertEquals(2, stored.size());
        assertEquals("use a tool", stored.get(0).getText());
        assertEquals("final answer", stored.get(1).getText());
        assertTrue(stored.get(1).getContents().stream()
                .noneMatch(FunctionCallContent.class::isInstance));
    }

    @Test
    void perServiceHistoryWrapsEachBufferedToolLoopCall() throws Exception {
        ToolLoopChatClient inner = new ToolLoopChatClient();
        ChatClient client = new FunctionInvokingChatClient(
                new PerServiceCallHistoryChatClient(inner));
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("tool result")))
                .build();
        AgentSession session = new AgentSession();

        agent.run(
                        Collections.singletonList(ChatMessage.user("use a tool")),
                        session,
                        null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, inner.requests.size());
        assertEquals(1, inner.requests.get(0).size());
        assertEquals(3, inner.requests.get(1).size());
        assertEquals("use a tool", inner.requests.get(1).get(0).getText());
        assertEquals(4, history.getMessages(session).size());
    }

    @Test
    void perServiceHistoryWrapsEachStreamingToolLoopCall() throws Exception {
        ToolLoopStreamingClient inner = new ToolLoopStreamingClient();
        ChatClient client = new FunctionInvokingChatClient(
                new PerServiceCallHistoryChatClient(inner));
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .tool(new FunctionTool(
                        "echo",
                        "Echo",
                        Collections.emptyMap(),
                        arguments -> CompletableFuture.completedFuture("tool result")))
                .build();
        AgentSession session = new AgentSession();
        CompletionSubscriber subscriber = new CompletionSubscriber();

        agent.runStreaming(
                        Collections.singletonList(ChatMessage.user("use a tool")),
                        session,
                        null)
                .subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.completion.get(5, TimeUnit.SECONDS);

        assertEquals(2, inner.requests.size());
        assertEquals(1, inner.requests.get(0).size());
        assertEquals(3, inner.requests.get(1).size());
        assertEquals("use a tool", inner.requests.get(1).get(0).getText());
        assertEquals(4, history.getMessages(session).size());
    }

    @Test
    void serverManagedContinuationRunsContextButSkipsLocalHistory() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        AgentSession session = new AgentSession();
        history.setMessages(session, Collections.singletonList(ChatMessage.user("old")));
        RecordingContextProvider provider = new RecordingContextProvider(null);
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .aiContextProvider(provider)
                .build();
        AgentRunOptions options = AgentRunOptions.builder()
                .continuationToken("response-1")
                .build();

        agent.run(Collections.singletonList(ChatMessage.user("new")), session, options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, client.requests.get(0).size());
        assertEquals("new", client.requests.get(0).get(0).getText());
        assertEquals("context message", client.requests.get(0).get(1).getText());
        assertEquals(1, history.getMessages(session).size());
        assertNotNull(provider.invoked.get());
    }

    @Test
    void serverManagedConversationSkipsLocalHistory() throws Exception {
        RecordingChatClient client = new RecordingChatClient();
        InMemoryChatHistoryProvider history = new InMemoryChatHistoryProvider();
        AgentSession session = new AgentSession();
        history.setMessages(session, Collections.singletonList(ChatMessage.user("old")));
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatHistoryProvider(history)
                .build();
        AgentRunOptions options = AgentRunOptions.builder()
                .chatOptions(ChatOptions.builder().conversationId("conversation-1").build())
                .build();

        agent.run(Collections.singletonList(ChatMessage.user("new")), session, options)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, client.requests.get(0).size());
        assertEquals("new", client.requests.get(0).get(0).getText());
        assertEquals(1, history.getMessages(session).size());
    }

    private static class RecordingContextProvider extends AIContextProvider {
        private final FunctionTool tool;
        protected final AtomicReference<AgentInvokedContext> invoked = new AtomicReference<>();

        private RecordingContextProvider(FunctionTool tool) {
            this.tool = tool;
        }

        @Override
        protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
            AIContext.Builder additional = AIContext.builder()
                    .instructions("context")
                    .message(ChatMessage.system("context message"));
            if (tool != null) {
                additional.tool(tool);
            }
            return CompletableFuture.completedFuture(additional.build());
        }

        @Override
        protected CompletionStage<Void> store(AgentInvokedContext context) {
            invoked.set(context);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class KeyedContextProvider extends AIContextProvider {
        private final String key;

        private KeyedContextProvider(String key) {
            this.key = key;
        }

        @Override
        public List<String> getStateKeys() {
            return Collections.singletonList(key);
        }
    }

    private static class RecordingChatClient implements ChatClient {
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<ChatOptions> options = new ArrayList<>();

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
            this.options.add(options);
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .message(ChatMessage.assistant("answer-" + requests.size()))
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
            this.options.add(options);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done && count > 0) {
                        done = true;
                        subscriber.onNext(ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .messageId("message-1")
                                .text("partial")
                                .finishReason(FinishReason.STOP)
                                .build());
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }

    private static final class CompletionSubscriber
            implements Flow.Subscriber<AgentResponseUpdate> {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(AgentResponseUpdate item) {
        }

        @Override
        public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completion.complete(null);
        }
    }

    private static final class ToolLoopStreamingClient implements ChatClient {
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private int call;

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
            List<ChatResponseUpdate> turn;
            if (call++ == 0) {
                turn = List.of(
                        ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .text("checking")
                                .build(),
                        ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .content(new FunctionCallContent("call-1", "echo", "{}"))
                                .finishReason(FinishReason.TOOL_CALLS)
                                .build());
            } else {
                turn = Collections.singletonList(ChatResponseUpdate.builder()
                                .role(ChatRole.ASSISTANT)
                                .text("final answer")
                                .finishReason(FinishReason.STOP)
                                .build());
            }
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done && count > 0) {
                        done = true;
                        turn.forEach(subscriber::onNext);
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }

    private static final class ToolLoopChatClient implements ChatClient {
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            requests.add(new ArrayList<>(messages));
            if (requests.size() == 1) {
                return CompletableFuture.completedFuture(ChatResponse.builder()
                        .message(ChatMessage.builder(ChatRole.ASSISTANT)
                                .addContent(new FunctionCallContent(
                                        "call-1",
                                        "echo",
                                        "{}"))
                                .build())
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build());
            }
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .message(ChatMessage.assistant("final answer"))
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
