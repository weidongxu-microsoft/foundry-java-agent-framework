package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agent.ChatHistoryProvider;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class PerServiceCallHistoryChatClient extends DelegatingChatClient {
    public PerServiceCallHistoryChatClient(ChatClient delegate) {
        super(delegate);
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        RunContext run = requiredRunContext(options);
        List<ChatMessage> request = immutableMessages(messages);
        Object expectedServiceState = run.session.getServiceSessionId();
        boolean useHistory = !usesServiceState(options);
        CompletionStage<List<ChatMessage>> prepared = useHistory
                ? load(run, request)
                : CompletableFuture.completedFuture(request);
        return prepared.thenCompose(modelMessages -> {
            CompletionStage<ChatResponse> stage;
            try {
                stage = Objects.requireNonNull(
                        getDelegate().getResponse(modelMessages, options),
                        "ChatClient returned null CompletionStage");
            } catch (Throwable error) {
                return useHistory
                        ? notify(run, request, Collections.emptyList(), error)
                                .thenCompose(ignored -> failedFuture(error))
                        : failedFuture(error);
            }
            CompletableFuture<ChatResponse> result = new CompletableFuture<>();
            stage.whenComplete((response, error) -> {
                Throwable failure = unwrap(error);
                if (failure == null && response == null) {
                    failure = new NullPointerException(
                            "ChatClient completed with null response");
                }
                if (failure == null) {
                    try {
                        updateServiceState(run.session, expectedServiceState, response);
                    } catch (Throwable stateError) {
                        failure = stateError;
                    }
                }
                Throwable finalFailure = failure;
                List<ChatMessage> responseMessages = response == null
                        ? Collections.emptyList()
                        : response.getMessages();
                CompletionStage<Void> notified = useHistory
                        ? notify(run, request, responseMessages, finalFailure)
                        : CompletableFuture.completedFuture(null);
                notified
                        .whenComplete((ignored, notifyError) -> {
                            Throwable callbackFailure = unwrap(notifyError);
                            if (finalFailure != null) {
                                if (callbackFailure != null) {
                                    finalFailure.addSuppressed(callbackFailure);
                                }
                                result.completeExceptionally(finalFailure);
                            } else if (callbackFailure != null) {
                                result.completeExceptionally(callbackFailure);
                            } else {
                                result.complete(response);
                            }
                        });
            });
            return result;
        });
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            RunContext run;
            try {
                run = requiredRunContext(options);
            } catch (Throwable error) {
                PerServiceCallHistoryChatClient
                        .<ChatResponseUpdate>failedPublisher(error)
                        .subscribe(subscriber);
                return;
            }
            HistorySubscription subscription = new HistorySubscription(
                    subscriber,
                    run,
                    immutableMessages(messages),
                    options,
                    !usesServiceState(options));
            subscriber.onSubscribe(subscription);
            subscription.start();
        };
    }

    private CompletionStage<List<ChatMessage>> load(
            RunContext run,
            List<ChatMessage> request) {
        AIContext context = AIContext.builder().messages(request).build();
        return run.provider.invoking(
                new AgentInvokingContext(run.agent, run.session, context));
    }

    private CompletionStage<Void> notify(
            RunContext run,
            List<ChatMessage> request,
            List<ChatMessage> response,
            Throwable error) {
        return run.provider.invoked(new AgentInvokedContext(
                run.agent,
                run.session,
                request,
                response,
                error));
    }

    private static RunContext requiredRunContext(ChatOptions options) {
        Objects.requireNonNull(options, "options");
        Map<String, Object> properties = options.getAdditionalProperties();
        Object agent = properties.get(RunContextProperties.AGENT);
        Object session = properties.get(RunContextProperties.AGENT_SESSION);
        Object provider = properties.get(
                RunContextProperties.CHAT_HISTORY_PROVIDER);
        if (!(agent instanceof Agent)
                || !(session instanceof AgentSession)
                || !(provider instanceof ChatHistoryProvider)) {
            throw new IllegalStateException(
                    "PerServiceCallHistoryChatClient requires agent, session, "
                            + "and history-provider run context");
        }

        return new RunContext(
                (Agent) agent,
                (AgentSession) session,
                (ChatHistoryProvider) provider);
    }

    private static boolean usesServiceState(ChatOptions options) {
        return (options.getConversationId() != null
                        && !options.getConversationId().isBlank())
                || (options.getContinuationToken() != null
                        && !options.getContinuationToken().isBlank());
    }

    private static void updateServiceState(
            AgentSession session,
            Object expected,
            ChatResponse response) {
        Object updated = serviceState(
                response.getConversationId(),
                response.getContinuationToken());
        if (updated != null
                && !session.compareAndSetServiceSessionId(expected, updated)) {
            throw new ConcurrentModificationException(
                    "Agent session service state changed during service call");
        }
    }

    private static Object serviceState(
            String conversationId,
            String continuationToken) {
        if (conversationId != null && !conversationId.isBlank()) {
            return Collections.singletonMap("conversation_id", conversationId);
        }
        if (continuationToken != null && !continuationToken.isBlank()) {
            return Collections.singletonMap(
                    "previous_response_id",
                    continuationToken);
        }
        return null;
    }

    private static List<ChatMessage> responseMessages(
            List<ChatResponseUpdate> updates) {
        Map<String, MessageAccumulator> grouped = new LinkedHashMap<>();
        for (ChatResponseUpdate update : updates) {
            if (update.getContents().isEmpty()) {
                continue;
            }
            ChatRole role = update.getRole() == null
                    ? ChatRole.ASSISTANT
                    : update.getRole();
            String key = update.getMessageId() == null
                    ? "role:" + role
                    : "id:" + update.getMessageId();
            MessageAccumulator accumulator =
                    grouped.computeIfAbsent(
                            key,
                            ignored -> new MessageAccumulator(role));
            accumulator.contents.addAll(update.getContents());
        }
        List<ChatMessage> messages = new ArrayList<>();
        grouped.values().forEach(accumulator -> messages.add(
                ChatMessage.builder(accumulator.role)
                        .contents(accumulator.contents)
                        .build()));
        return messages;
    }

    private static ServiceState lastServiceState(
            List<ChatResponseUpdate> updates) {
        String conversationId = null;
        String continuationToken = null;
        for (ChatResponseUpdate update : updates) {
            if (update.getConversationId() != null
                    && !update.getConversationId().isBlank()) {
                conversationId = update.getConversationId();
            }
            if (update.getContinuationToken() != null
                    && !update.getContinuationToken().isBlank()) {
                continuationToken = update.getContinuationToken();
            }
        }
        return conversationId == null && continuationToken == null
                ? null
                : new ServiceState(conversationId, continuationToken);
    }

    private static List<ChatMessage> immutableMessages(
            List<? extends ChatMessage> messages) {
        return Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(messages, "messages")));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while ((result instanceof java.util.concurrent.CompletionException
                || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static <T> Flow.Publisher<T> failedPublisher(Throwable error) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onError(error);
        };
    }

    private static long addCap(long current, long increment) {
        long result = current + increment;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    private static final class RunContext {
        private final Agent agent;
        private final AgentSession session;
        private final ChatHistoryProvider provider;

        private RunContext(
                Agent agent,
                AgentSession session,
                ChatHistoryProvider provider) {
            this.agent = agent;
            this.session = session;
            this.provider = provider;
        }
    }

    private static final class MessageAccumulator {
        private final ChatRole role;
        private final List<ChatContent> contents = new ArrayList<>();

        private MessageAccumulator(ChatRole role) {
            this.role = role;
        }
    }

    private static final class ServiceState {
        private final String conversationId;
        private final String continuationToken;

        private ServiceState(
                String conversationId,
                String continuationToken) {
            this.conversationId = conversationId;
            this.continuationToken = continuationToken;
        }
    }

    private final class HistorySubscription
            implements Flow.Subscription, Flow.Subscriber<ChatResponseUpdate> {
        private final Flow.Subscriber<? super ChatResponseUpdate> downstream;
        private final RunContext run;
        private final List<ChatMessage> request;
        private final ChatOptions options;
        private final boolean useHistory;
        private final Object expectedServiceState;
        private final List<ChatResponseUpdate> updates = new ArrayList<>();
        private Flow.Subscription upstream;
        private long demand;
        private boolean started;
        private boolean done;

        private HistorySubscription(
                Flow.Subscriber<? super ChatResponseUpdate> downstream,
                RunContext run,
                List<ChatMessage> request,
                ChatOptions options,
                boolean useHistory) {
            this.downstream = downstream;
            this.run = run;
            this.request = request;
            this.options = options;
            this.useHistory = useHistory;
            this.expectedServiceState = run.session.getServiceSessionId();
        }

        private synchronized void start() {
            if (started || done) {
                return;
            }
            started = true;
            CompletionStage<List<ChatMessage>> prepared = useHistory
                    ? load(run, request)
                    : CompletableFuture.completedFuture(request);
            prepared.whenComplete((messages, error) -> {
                if (error != null) {
                    fail(unwrap(error));
                    return;
                }
                synchronized (HistorySubscription.this) {
                    if (done) {
                        return;
                    }
                }
                try {
                    getDelegate().getStreamingResponse(messages, options)
                            .subscribe(this);
                } catch (Throwable subscribeError) {
                    fail(subscribeError);
                }
            });
        }

        @Override
        public void request(long count) {
            Flow.Subscription current;
            synchronized (this) {
                if (done) {
                    return;
                }
                if (count <= 0) {
                    current = null;
                } else {
                    demand = addCap(demand, count);
                    current = upstream;
                }
            }
            if (count <= 0) {
                fail(new IllegalArgumentException("Flow demand must be positive"));
            } else if (current != null) {
                current.request(count);
            }
        }

        @Override
        public void cancel() {
            Flow.Subscription current;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                current = upstream;
                upstream = null;
            }
            if (current != null) {
                current.cancel();
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            long currentDemand;
            synchronized (this) {
                if (done || upstream != null) {
                    subscription.cancel();
                    return;
                }
                upstream = subscription;
                currentDemand = demand;
            }
            if (currentDemand > 0) {
                subscription.request(currentDemand);
            }
        }

        @Override
        public void onNext(ChatResponseUpdate update) {
            synchronized (this) {
                if (done) {
                    return;
                }
                if (demand == 0) {
                    fail(new IllegalStateException(
                            "Upstream emitted without demand"));
                    return;
                }
                if (demand != Long.MAX_VALUE) {
                    demand--;
                }
                updates.add(update);
                downstream.onNext(update);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            finish(throwable);
        }

        @Override
        public void onComplete() {
            finish(null);
        }

        private void finish(Throwable error) {
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                upstream = null;
            }
            Throwable failure = error;
            if (failure == null) {
                ServiceState serviceUpdate = lastServiceState(updates);
                if (serviceUpdate != null) {
                    try {
                        Object updated = serviceState(
                                serviceUpdate.conversationId,
                                serviceUpdate.continuationToken);
                        if (updated != null
                                && !run.session.compareAndSetServiceSessionId(
                                        expectedServiceState,
                                        updated)) {
                            throw new ConcurrentModificationException(
                                    "Agent session service state changed during service call");
                        }
                    } catch (Throwable stateError) {
                        failure = stateError;
                    }
                }
            }
            Throwable finalFailure = failure;
            CompletionStage<Void> notified = useHistory
                    ? PerServiceCallHistoryChatClient.this.notify(
                            run,
                            request,
                            responseMessages(updates),
                            finalFailure)
                    : CompletableFuture.completedFuture(null);
            notified
                    .whenComplete((ignored, notifyError) -> {
                        Throwable callbackFailure = unwrap(notifyError);
                        if (finalFailure != null) {
                            if (callbackFailure != null) {
                                finalFailure.addSuppressed(callbackFailure);
                            }
                            downstream.onError(finalFailure);
                        } else if (callbackFailure != null) {
                            downstream.onError(callbackFailure);
                        } else {
                            downstream.onComplete();
                        }
                    });
        }

        private void fail(Throwable error) {
            Flow.Subscription current;
            synchronized (this) {
                if (done) {
                    return;
                }
                current = upstream;
                upstream = null;
            }
            if (current != null) {
                current.cancel();
            }
            finish(error);
        }
    }
}
