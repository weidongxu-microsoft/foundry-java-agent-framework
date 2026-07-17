package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.middleware.ChatMiddleware;
import io.github.weidongxu.agentframework.middleware.ChatMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.ChatMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.ChatStreamingMiddlewareNext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiddlewareChatClient extends DelegatingChatClient {
    private final List<ChatMiddleware> middleware;

    public MiddlewareChatClient(
            ChatClient delegate,
            List<? extends ChatMiddleware> middleware) {
        super(delegate);
        this.middleware = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(middleware, "middleware")));
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        ChatMiddlewareContext context =
                new ChatMiddlewareContext(this, messages, options, false);
        return invoke(context, 0);
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            ChatMiddlewareContext context =
                    new ChatMiddlewareContext(this, messages, options, true);
            Flow.Publisher<ChatResponseUpdate> publisher;
            try {
                publisher = Objects.requireNonNull(
                        invokeStreaming(context, 0),
                        "Chat middleware returned null Publisher");
            } catch (Throwable error) {
                MiddlewareChatClient.<ChatResponseUpdate>failedPublisher(error)
                        .subscribe(subscriber);
                return;
            }
            publisher.subscribe(subscriber);
        };
    }

    private CompletionStage<ChatResponse> invoke(
            ChatMiddlewareContext context,
            int index) {
        if (index == middleware.size()) {
            return getDelegate().getResponse(context.getMessages(), context.getOptions());
        }
        ChatMiddleware current = middleware.get(index);
        AtomicBoolean called = new AtomicBoolean();
        ChatMiddlewareNext next = nextContext -> {
            if (!called.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Chat middleware called next more than once"));
            }
            return invoke(nextContext, index + 1);
        };
        try {
            return Objects.requireNonNull(
                    current.invoke(context, next),
                    "Chat middleware returned null CompletionStage");
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            ChatMiddlewareContext context,
            int index) {
        if (index == middleware.size()) {
            return getDelegate().getStreamingResponse(
                    context.getMessages(),
                    context.getOptions());
        }
        ChatMiddleware current = middleware.get(index);
        AtomicBoolean called = new AtomicBoolean();
        ChatStreamingMiddlewareNext next = nextContext -> {
            if (!called.compareAndSet(false, true)) {
                return failedPublisher(
                        new IllegalStateException("Chat middleware called next more than once"));
            }
            return invokeStreaming(nextContext, index + 1);
        };
        return current.invokeStreaming(context, next);
    }

    private static <T> Flow.Publisher<T> failedPublisher(Throwable error) {
        return subscriber -> {
            AtomicBoolean done = new AtomicBoolean();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });

            if (done.compareAndSet(false, true)) {
                subscriber.onError(error);
            }
        };
    }
}
