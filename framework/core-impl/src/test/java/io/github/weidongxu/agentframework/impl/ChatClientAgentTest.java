package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AgentResponse;
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
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.AgentStreamingMiddlewareNext;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatClientAgentTest {
    @Test
    void delegatesRunAndMapsResponse() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        FunctionTool tool = new FunctionTool(
                "echo",
                "Echo input",
                Collections.singletonMap("type", "object"),
                arguments -> CompletableFuture.completedFuture(arguments.toString()));
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .id("agent-1")
                .name("demo")
                .instructions("Be concise.")
                .tool(tool)
                .build();

        AgentRunOptions runOptions = AgentRunOptions.builder()
                .chatOptions(ChatOptions.builder()
                        .modelId("model-1")
                        .temperature(0.2)
                        .additionalProperty("provider-option", true)
                        .build())
                .continuationToken("next-1")
                .additionalProperty("trace-id", "trace-1")
                .build();

        AgentResponse response = agent.run(
                        Collections.singletonList(ChatMessage.user("hello")),
                        null,
                        runOptions)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("hello", client.messages.get(0).getText());
        assertEquals("Be concise.", client.options.getInstructions());
        assertEquals("model-1", client.options.getModelId());
        assertEquals(0.2, client.options.getTemperature());
        assertEquals(Collections.singletonList(tool), client.options.getTools());
        assertEquals("next-1", client.options.getContinuationToken());
        assertEquals("trace-1", client.options.getAdditionalProperties().get("trace-id"));
        assertEquals("answer", response.getText());
        assertEquals("response-1", response.getResponseId());
        assertEquals("conversation-1", response.getConversationId());
        assertEquals("agent-1", response.getAgentId());
        assertEquals("next-2", response.getContinuationToken());
    }

    @Test
    void mapsStreamingUpdatesAndPreservesDemand() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client).id("agent-1").build();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        agent.runStreaming("hello").subscribe(subscriber);

        assertNotNull(subscriber.subscription);
        assertEquals(0, client.requested.get());
        subscriber.subscription.request(1);
        AgentResponseUpdate update = subscriber.result.get(5, TimeUnit.SECONDS);

        assertEquals(1, client.requested.get());
        assertEquals("partial", update.getText());
        assertEquals("agent-1", update.getAgentId());
        assertTrue(subscriber.completed.get());
    }

    @Test
    void streamingSnapshotsServiceStateAtSubscription() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client).build();
        AgentSession session = new AgentSession();
        Flow.Publisher<AgentResponseUpdate> publisher = agent.runStreaming(
                Collections.singletonList(ChatMessage.user("hello")),
                session,
                null);
        session.setServiceSessionId(
                Collections.singletonMap(
                        "conversation_id",
                        "conversation-latest"));
        CollectingSubscriber subscriber = new CollectingSubscriber();

        publisher.subscribe(subscriber);
        subscriber.subscription.request(1);
        subscriber.result.get(5, TimeUnit.SECONDS);

        assertEquals("conversation-latest", client.options.getConversationId());
    }

    @Test
    void persistsAndReusesServiceManagedConversation() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client).build();
        AgentSession session = new AgentSession();

        agent.run(
                        Collections.singletonList(ChatMessage.user("first")),
                        session,
                        null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(
                "conversation-1",
                ((java.util.Map<?, ?>) session.getServiceSessionId())
                        .get("conversation_id"));

        agent.run(
                        Collections.singletonList(ChatMessage.user("second")),
                        session,
                        null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("conversation-1", client.options.getConversationId());
    }

    @Test
    void explicitContinuationOverridesStoredConversation() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client).build();
        AgentSession session = new AgentSession();
        session.setServiceSessionId(
                Collections.singletonMap("conversation_id", "conversation-1"));

        agent.run(
                        Collections.singletonList(ChatMessage.user("next")),
                        session,
                        AgentRunOptions.builder()
                                .continuationToken("response-previous")
                                .build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("response-previous", client.options.getContinuationToken());
        assertEquals(null, client.options.getConversationId());
    }

    @Test
    void storedServiceStateSupersedesDefaultConversation() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .chatOptions(ChatOptions.builder()
                        .conversationId("default-conversation")
                        .build())
                .build();
        AgentSession session = new AgentSession();
        session.setServiceSessionId(
                Collections.singletonMap(
                        "previous_response_id",
                        "restored-response"));

        agent.run(
                        Collections.singletonList(ChatMessage.user("next")),
                        session,
                        null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("restored-response", client.options.getContinuationToken());
        assertEquals(null, client.options.getConversationId());
    }

    @Test
    void concurrentServiceSessionProgressionDetectsConflict() throws Exception {
        ConcurrentChatClient client = new ConcurrentChatClient();
        ChatClientAgent agent = ChatClientAgent.builder(client).build();
        AgentSession session = new AgentSession();

        CompletableFuture<AgentResponse> first = agent.run(
                        Collections.singletonList(ChatMessage.user("first")),
                        session,
                        null)
                .toCompletableFuture();
        CompletableFuture<AgentResponse> second = agent.run(
                        Collections.singletonList(ChatMessage.user("second")),
                        session,
                        null)
                .toCompletableFuture();
        client.responses.get(0).complete(ChatResponse.builder()
                .message(ChatMessage.assistant("one"))
                .conversationId("conversation-1")
                .build());
        first.get(5, TimeUnit.SECONDS);
        client.responses.get(1).complete(ChatResponse.builder()
                .message(ChatMessage.assistant("two"))
                .conversationId("conversation-2")
                .build());

        Exception failure = assertThrows(
                Exception.class,
                () -> second.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof ConcurrentModificationException);
        assertEquals(
                "conversation-1",
                ((java.util.Map<?, ?>) session.getServiceSessionId())
                        .get("conversation_id"));
    }

    @Test
    void agentMiddlewareRunsInOrderAndCanMutateContext() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        List<String> events = new ArrayList<>();
        AgentMiddleware first = new AgentMiddleware() {
            @Override
            public CompletionStage<AgentResponse> invoke(
                    AgentMiddlewareContext context,
                    AgentMiddlewareNext next) {
                events.add("first-before");
                context.getMetadata().put("trace", "shared");
                context.setMessages(Collections.singletonList(
                        ChatMessage.user("changed")));
                return next.invoke(context).thenApply(response -> {
                    events.add("first-after");
                    return response;
                });
            }
        };
        AgentMiddleware second = new AgentMiddleware() {
            @Override
            public CompletionStage<AgentResponse> invoke(
                    AgentMiddlewareContext context,
                    AgentMiddlewareNext next) {
                events.add("second-before-" + context.getMetadata().get("trace"));
                return next.invoke(context).thenApply(response -> {
                    events.add("second-after");
                    return response;
                });
            }
        };
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .middleware(first)
                .middleware(second)
                .build();

        agent.run("original").toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("changed", client.messages.get(0).getText());
        assertEquals(
                List.of(
                        "first-before",
                        "second-before-shared",
                        "second-after",
                        "first-after"),
                events);
    }

    @Test
    void agentMiddlewareCanShortCircuitStreaming() throws Exception {
        CapturingChatClient client = new CapturingChatClient();
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public Flow.Publisher<AgentResponseUpdate> invokeStreaming(
                    AgentMiddlewareContext context,
                    AgentStreamingMiddlewareNext next) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean done;

                    @Override
                    public void request(long count) {
                        if (!done) {
                            done = true;
                            subscriber.onNext(AgentResponseUpdate.builder()
                                    .role(ChatRole.ASSISTANT)
                                    .content(new io.github.weidongxu.agentframework.chat.TextContent(
                                            "cached"))
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
        };
        ChatClientAgent agent = ChatClientAgent.builder(client)
                .middleware(middleware)
                .build();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        agent.runStreaming("hello").subscribe(subscriber);
        subscriber.subscription.request(1);
        AgentResponseUpdate update = subscriber.result.get(5, TimeUnit.SECONDS);

        assertEquals("cached", update.getText());
        assertEquals(0, client.requested.get());
    }

    @Test
    void rejectsDuplicateToolNames() {
        FunctionTool first = tool("duplicate");
        FunctionTool second = tool("duplicate");

        assertThrows(
                IllegalArgumentException.class,
                () -> ChatClientAgent.builder(new CapturingChatClient())
                        .tool(first)
                        .tool(second)
                        .build());
    }

    @Test
    void mappingFailureCancelsUpstreamAndSignalsOneError() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        Flow.Publisher<String> source = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
                subscriber.onNext("bad");
                subscriber.onNext("late");
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        MappingPublisher<String, String> publisher =
                new MappingPublisher<>(source, value -> {
                    throw new IllegalStateException("mapping failed");
                });

        publisher.subscribe(new Flow.Subscriber<String>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(String item) {
                throw new AssertionError("No item expected");
            }

            @Override
            public void onError(Throwable throwable) {
                errors.incrementAndGet();
            }

            @Override
            public void onComplete() {
                completions.incrementAndGet();
            }
        });

        assertTrue(cancelled.get());
        assertEquals(1, errors.get());
        assertEquals(0, completions.get());
    }

    private static FunctionTool tool(String name) {
        return new FunctionTool(
                name,
                name,
                Collections.emptyMap(),
                arguments -> CompletableFuture.completedFuture(""));
    }

    private static final class CapturingChatClient implements ChatClient {
        private List<ChatMessage> messages;
        private ChatOptions options;
        private final AtomicLong requested = new AtomicLong();

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            this.messages = messages;
            this.options = options;
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .message(ChatMessage.assistant("answer"))
                    .responseId("response-1")
                    .conversationId("conversation-1")
                    .continuationToken("next-2")
                    .finishReason(FinishReason.STOP)
                    .additionalProperty("provider", "fake")
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            this.messages = messages;
            this.options = options;
            ChatResponseUpdate update = ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .text("partial")
                    .responseId("response-1")
                    .build();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    requested.addAndGet(count);
                    if (done) {
                        return;
                    }
                    if (count <= 0) {
                        done = true;
                        subscriber.onError(new IllegalArgumentException("count must be positive"));
                        return;
                    }
                    done = true;
                    subscriber.onNext(update);
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }

    private static final class ConcurrentChatClient implements ChatClient {
        private final List<CompletableFuture<ChatResponse>> responses =
                new ArrayList<>();

        @Override
        public synchronized CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            CompletableFuture<ChatResponse> response = new CompletableFuture<>();
            responses.add(response);
            return response;
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
        private final CompletableFuture<AgentResponseUpdate> result = new CompletableFuture<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(AgentResponseUpdate item) {
            result.complete(item);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed.set(true);
        }
    }
}
