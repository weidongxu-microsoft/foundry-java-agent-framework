package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.middleware.ChatMiddleware;
import io.github.weidongxu.agentframework.middleware.ChatMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.ChatMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.ChatStreamingMiddlewareNext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiddlewareChatClientTest {
    @Test
    void runsBufferedMiddlewareInOrder() throws Exception {
        RecordingClient inner = new RecordingClient();
        List<String> events = new ArrayList<>();
        ChatMiddleware first = new ChatMiddleware() {
            @Override
            public CompletionStage<ChatResponse> invoke(
                    ChatMiddlewareContext context,
                    ChatMiddlewareNext next) {
                events.add("first-before");
                context.getMetadata().put("id", "shared");
                context.setMessages(Collections.singletonList(
                        ChatMessage.user("changed")));
                return next.invoke(context).thenApply(response -> {
                    events.add("first-after");
                    return response;
                });
            }
        };
        ChatMiddleware second = new ChatMiddleware() {
            @Override
            public CompletionStage<ChatResponse> invoke(
                    ChatMiddlewareContext context,
                    ChatMiddlewareNext next) {
                events.add("second-before-" + context.getMetadata().get("id"));
                return next.invoke(context).thenApply(response -> {
                    events.add("second-after");
                    return response;
                });
            }
        };
        MiddlewareChatClient client =
                new MiddlewareChatClient(inner, List.of(first, second));

        client.getResponse(
                        Collections.singletonList(ChatMessage.user("original")),
                        ChatOptions.builder().build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("changed", inner.messages.get(0).getText());
        assertEquals(
                List.of(
                        "first-before",
                        "second-before-shared",
                        "second-after",
                        "first-after"),
                events);
    }

    @Test
    void streamingMiddlewareIsCreatedPerSubscriptionAndCanShortCircuit() {
        RecordingClient inner = new RecordingClient();
        AtomicInteger invocations = new AtomicInteger();
        ChatMiddleware middleware = new ChatMiddleware() {
            @Override
            public Flow.Publisher<ChatResponseUpdate> invokeStreaming(
                    ChatMiddlewareContext context,
                    ChatStreamingMiddlewareNext next) {
                int invocation = invocations.incrementAndGet();
                return single(ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .text("cached-" + invocation)
                        .build());
            }
        };
        MiddlewareChatClient client =
                new MiddlewareChatClient(inner, Collections.singletonList(middleware));
        Flow.Publisher<ChatResponseUpdate> publisher = client.getStreamingResponse(
                Collections.singletonList(ChatMessage.user("hello")),
                ChatOptions.builder().build());
        TestSubscriber first = new TestSubscriber();
        TestSubscriber second = new TestSubscriber();

        publisher.subscribe(first);
        publisher.subscribe(second);
        first.subscription.request(1);
        second.subscription.request(1);

        assertEquals("cached-1", first.update.getText());
        assertEquals("cached-2", second.update.getText());
        assertEquals(0, inner.streamingCalls.get());
        assertTrue(first.completed);
        assertTrue(second.completed);
    }

    private static Flow.Publisher<ChatResponseUpdate> single(
            ChatResponseUpdate update) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(long count) {
                if (!done) {
                    done = true;
                    subscriber.onNext(update);
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }

    private static final class RecordingClient implements ChatClient {
        private List<ChatMessage> messages;
        private final AtomicInteger streamingCalls = new AtomicInteger();

        @Override
        public CompletionStage<ChatResponse> getResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            this.messages = messages;
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .message(ChatMessage.assistant("ok"))
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
                List<ChatMessage> messages,
                ChatOptions options) {
            streamingCalls.incrementAndGet();
            return single(ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .text("inner")
                    .build());
        }
    }

    private static final class TestSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private Flow.Subscription subscription;
        private ChatResponseUpdate update;
        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
            update = item;
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}
