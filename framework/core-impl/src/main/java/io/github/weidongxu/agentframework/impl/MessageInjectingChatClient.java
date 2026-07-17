package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

public final class MessageInjectingChatClient extends DelegatingChatClient {
    public static final String DEFAULT_STATE_KEY =
            MessageInjectingChatClient.class.getName() + ".pendingMessages";
    public static final int DEFAULT_MAX_ITERATIONS = 32;

    private final String stateKey;
    private final int maxIterations;
    private final Map<AgentSession, Object> sessionLocks =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> activeRunOwners = ConcurrentHashMap.newKeySet();

    public MessageInjectingChatClient(ChatClient delegate) {
        this(delegate, DEFAULT_STATE_KEY, DEFAULT_MAX_ITERATIONS);
    }

    public MessageInjectingChatClient(
            ChatClient delegate,
            String stateKey,
            int maxIterations) {
        super(delegate);
        this.stateKey = requireNonBlank(stateKey, "stateKey");
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
    }

    public CompletionStage<Void> enqueueMessages(
            AgentSession session,
            List<? extends ChatMessage> messages) {
        Objects.requireNonNull(session, "session");
        List<ChatMessage> additions = immutableMessages(messages);
        if (additions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (lockFor(session)) {
            InjectionState state = readState(session);
            List<ChatMessage> pending = state.pending;
            List<ChatMessage> updated =
                    new ArrayList<>(pending.size() + additions.size());
            updated.addAll(pending);
            updated.addAll(additions);
            writeState(session, new InjectionState(
                    updated,
                    state.inFlight,
                    state.owner));
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletionStage<List<ChatMessage>> getPendingMessages(
            AgentSession session) {
        Objects.requireNonNull(session, "session");
        synchronized (lockFor(session)) {
            return CompletableFuture.completedFuture(
                    Collections.unmodifiableList(
                            new ArrayList<>(readState(session).pending)));
        }
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        AgentSession session = requiredSession(options);
        RunHandle run = beginRun(session, messages);
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        runBuffered(
                session,
                run,
                run.initialClaim,
                options,
                new ArrayList<>(run.initialClaim.messages),
                0)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        rollback(run);
                        result.completeExceptionally(error);
                    } else {
                        commit(run);
                        result.complete(response);
                    }
                });
        return result;
    }

    private CompletionStage<ChatResponse> runBuffered(
            AgentSession session,
            RunHandle run,
            MessageClaim claim,
            ChatOptions options,
            List<ChatMessage> statelessConversation,
            int iteration) {
        CompletionStage<ChatResponse> stage;
        try {
            stage = Objects.requireNonNull(
                    getDelegate().getResponse(claim.messages, options),
                    "ChatClient returned null CompletionStage");
        } catch (Throwable error) {
            return failedFuture(error);
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        stage.whenComplete((response, error) -> {
            if (error != null || response == null) {
                result.completeExceptionally(error == null
                        ? new NullPointerException(
                                "ChatClient completed with null response")
                        : error);
                return;
            }
            if (hasFunctionCalls(response.getMessages())) {
                result.complete(response);
                return;
            }
            boolean serverManaged = hasServerManagedState(response, options);
            List<ChatMessage> base;
            ChatOptions nextOptions;
            if (serverManaged) {
                base = Collections.emptyList();
                nextOptions = advanceOptions(
                        options,
                        response.getConversationId(),
                        response.getContinuationToken());
            } else {
                statelessConversation.addAll(response.getMessages());
                base = statelessConversation;
                nextOptions = options;
            }
            MessageClaim nextClaim = claim(run, base);
            if (!nextClaim.hasInjectedMessages()) {
                result.complete(response);
                return;
            }
            if (iteration >= maxIterations) {
                result.completeExceptionally(new IllegalStateException(
                        "Message injection exceeded " + maxIterations + " iterations"));
                return;
            }
            if (!serverManaged) {
                statelessConversation.clear();
                statelessConversation.addAll(nextClaim.messages);
            }
            runBuffered(
                    session,
                    run,
                    nextClaim,
                    nextOptions,
                    statelessConversation,
                    iteration + 1)
                    .whenComplete((nextResponse, nextError) -> {
                        if (nextError != null) {
                            result.completeExceptionally(nextError);
                        } else {
                            result.complete(nextResponse);
                        }
                    });
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            AgentSession session;
            RunHandle run;
            try {
                session = requiredSession(options);
                run = beginRun(session, messages);
            } catch (Throwable error) {
                MessageInjectingChatClient.<ChatResponseUpdate>failedPublisher(error)
                        .subscribe(subscriber);
                return;
            }
            StreamingInjectionLoop loop = new StreamingInjectionLoop(
                    subscriber,
                    run,
                    options);
            subscriber.onSubscribe(loop);
            loop.start();
        };
    }

    private Object lockFor(AgentSession session) {
        synchronized (sessionLocks) {
            return sessionLocks.computeIfAbsent(session, ignored -> new Object());
        }
    }

    private RunHandle beginRun(
            AgentSession session,
            List<? extends ChatMessage> messages) {
        List<ChatMessage> base = immutableMessages(messages);
        synchronized (lockFor(session)) {
            InjectionState state = readState(session);
            if (state.owner != null) {
                if (activeRunOwners.contains(state.owner)) {
                    throw new IllegalStateException(
                            "A message injection run is already active for this session");
                }
                List<ChatMessage> recovered = new ArrayList<>(
                        state.inFlight.size() + state.pending.size());
                recovered.addAll(state.inFlight);
                recovered.addAll(state.pending);
                state = new InjectionState(
                        recovered,
                        Collections.emptyList(),
                        null);
            }
            String owner = UUID.randomUUID().toString();
            activeRunOwners.add(owner);
            List<ChatMessage> inFlight = new ArrayList<>(state.pending);
            writeState(session, new InjectionState(
                    Collections.emptyList(),
                    inFlight,
                    owner));
            List<ChatMessage> merged =
                    new ArrayList<>(base.size() + state.pending.size());
            merged.addAll(base);
            merged.addAll(state.pending);
            return new RunHandle(
                    session,
                    owner,
                    new MessageClaim(
                    Collections.unmodifiableList(merged),
                            !state.pending.isEmpty()));
        }
    }

    private MessageClaim claim(
            RunHandle run,
            List<? extends ChatMessage> messages) {
        List<ChatMessage> base = immutableMessages(messages);
        synchronized (lockFor(run.session)) {
            InjectionState state = requiredOwnedState(run);
            List<ChatMessage> inFlight = new ArrayList<>(
                    state.inFlight.size() + state.pending.size());
            inFlight.addAll(state.inFlight);
            inFlight.addAll(state.pending);
            writeState(run.session, new InjectionState(
                    Collections.emptyList(),
                    inFlight,
                    run.owner));
            List<ChatMessage> merged =
                    new ArrayList<>(base.size() + state.pending.size());
            merged.addAll(base);
            merged.addAll(state.pending);
            return new MessageClaim(
                    Collections.unmodifiableList(merged),
                    !state.pending.isEmpty());
        }
    }

    private void commit(RunHandle run) {
        synchronized (lockFor(run.session)) {
            InjectionState state = requiredOwnedState(run);
            writeState(run.session, new InjectionState(
                    state.pending,
                    Collections.emptyList(),
                    null));
            activeRunOwners.remove(run.owner);
        }
    }

    private void rollback(RunHandle run) {
        synchronized (lockFor(run.session)) {
            InjectionState state = readState(run.session);
            if (!run.owner.equals(state.owner)) {
                activeRunOwners.remove(run.owner);
                return;
            }
            List<ChatMessage> pending = new ArrayList<>(
                    state.inFlight.size() + state.pending.size());
            pending.addAll(state.inFlight);
            pending.addAll(state.pending);
            writeState(run.session, new InjectionState(
                    pending,
                    Collections.emptyList(),
                    null));
            activeRunOwners.remove(run.owner);
        }
    }

    private InjectionState requiredOwnedState(RunHandle run) {
        InjectionState state = readState(run.session);
        if (!run.owner.equals(state.owner)) {
            throw new IllegalStateException(
                    "Message injection run no longer owns its session state");
        }
        return state;
    }

    private InjectionState readState(AgentSession session) {
        Object value = session.get(stateKey);
        if (value == null) {
            return InjectionState.empty();
        }
        if (value instanceof List<?>) {
            return new InjectionState(
                    messageList(value),
                    Collections.emptyList(),
                    null);
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                    "Message injection session state must be an object");
        }
        Map<?, ?> map = (Map<?, ?>) value;
        Object owner = map.get("owner");
        return new InjectionState(
                messageList(map.get("pending")),
                messageList(map.get("in_flight")),
                owner == null ? null : owner.toString());
    }

    private List<ChatMessage> messageList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalStateException(
                    "Message injection state entry must be a message list");
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof ChatMessage)) {
                throw new IllegalStateException(
                        "Message injection session state contains a non-message value");
            }
            messages.add((ChatMessage) item);
        }
        return messages;
    }

    private void writeState(AgentSession session, InjectionState state) {
        if (state.pending.isEmpty()
                && state.inFlight.isEmpty()
                && state.owner == null) {
            session.remove(stateKey);
            return;
        }
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("pending", Collections.unmodifiableList(
                new ArrayList<>(state.pending)));
        value.put("in_flight", Collections.unmodifiableList(
                new ArrayList<>(state.inFlight)));
        if (state.owner != null) {
            value.put("owner", state.owner);
        }
        session.put(stateKey, Collections.unmodifiableMap(value));
    }

    private static AgentSession requiredSession(ChatOptions options) {
        Objects.requireNonNull(options, "options");
        Object session = options.getAdditionalProperties()
                .get(RunContextProperties.AGENT_SESSION);
        if (!(session instanceof AgentSession)) {
            throw new IllegalStateException(
                    "MessageInjectingChatClient requires an AgentSession run context");
        }
        return (AgentSession) session;
    }

    private static List<ChatMessage> immutableMessages(
            List<? extends ChatMessage> messages) {
        return Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(messages, "messages")));
    }

    private static boolean hasFunctionCalls(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            for (ChatContent content : message.getContents()) {
                if (content instanceof FunctionCallContent) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasFunctionCallContent(List<ChatContent> contents) {
        for (ChatContent content : contents) {
            if (content instanceof FunctionCallContent) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasServerManagedState(
            ChatResponse response,
            ChatOptions options) {
        return response.getConversationId() != null
                || response.getContinuationToken() != null
                || options.getConversationId() != null
                || options.getContinuationToken() != null
                || Boolean.TRUE.equals(options.getAdditionalProperties()
                        .get(RunContextProperties.PER_SERVICE_CALL_HISTORY));
    }

    private static ChatOptions advanceOptions(
            ChatOptions options,
            String conversationId,
            String continuationToken) {
        ChatOptions.Builder next = options.toBuilder();
        if (conversationId != null) {
            next.conversationId(conversationId).continuationToken(null);
        } else if (continuationToken != null) {
            next.continuationToken(continuationToken).conversationId(null);
        }
        return next.build();
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
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

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static final class MessageClaim {
        private final List<ChatMessage> messages;
        private final boolean injectedMessages;

        private MessageClaim(
                List<ChatMessage> messages,
                boolean injectedMessages) {
            this.messages = messages;
            this.injectedMessages = injectedMessages;
        }

        private boolean hasInjectedMessages() {
            return injectedMessages;
        }
    }

    private static final class RunHandle {
        private final AgentSession session;
        private final String owner;
        private final MessageClaim initialClaim;

        private RunHandle(
                AgentSession session,
                String owner,
                MessageClaim initialClaim) {
            this.session = session;
            this.owner = owner;
            this.initialClaim = initialClaim;
        }
    }

    private static final class InjectionState {
        private final List<ChatMessage> pending;
        private final List<ChatMessage> inFlight;
        private final String owner;

        private InjectionState(
                List<ChatMessage> pending,
                List<ChatMessage> inFlight,
                String owner) {
            this.pending = Collections.unmodifiableList(new ArrayList<>(pending));
            this.inFlight = Collections.unmodifiableList(new ArrayList<>(inFlight));
            this.owner = owner;
        }

        private static InjectionState empty() {
            return new InjectionState(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null);
        }
    }

    private final class StreamingInjectionLoop
            implements Flow.Subscription, Flow.Subscriber<ChatResponseUpdate> {
        private final Flow.Subscriber<? super ChatResponseUpdate> downstream;
        private final RunHandle run;
        private final AgentSession session;
        private final List<ChatMessage> statelessConversation;
        private MessageClaim currentClaim;
        private ChatOptions options;
        private final Object signalLock = new Object();
        private Flow.Subscription upstream;
        private long demand;
        private int iteration;
        private boolean started;
        private boolean done;
        private boolean hasFunctionCalls;
        private String conversationId;
        private String continuationToken;
        private final List<ChatContent> responseContents = new ArrayList<>();

        private StreamingInjectionLoop(
                Flow.Subscriber<? super ChatResponseUpdate> downstream,
                RunHandle run,
                ChatOptions options) {
            this.downstream = downstream;
            this.run = run;
            this.session = run.session;
            this.currentClaim = run.initialClaim;
            this.options = options;
            this.statelessConversation =
                    new ArrayList<>(run.initialClaim.messages);
        }

        private synchronized void start() {
            if (!started && !done) {
                started = true;
                subscribeTurn();
            }
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
            synchronized (signalLock) {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    done = true;
                    current = upstream;
                    upstream = null;
                    currentClaim = null;
                }
            }
            if (current != null) {
                current.cancel();
            }
            rollback(run);
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
            boolean emittedWithoutDemand;
            synchronized (this) {
                if (done) {
                    return;
                }
                emittedWithoutDemand = demand == 0;
                if (!emittedWithoutDemand && demand != Long.MAX_VALUE) {
                    demand--;
                }
                hasFunctionCalls |= hasFunctionCallContent(update.getContents());
                responseContents.addAll(update.getContents());
                if (update.getConversationId() != null) {
                    conversationId = update.getConversationId();
                }
                if (update.getContinuationToken() != null) {
                    continuationToken = update.getContinuationToken();
                }
            }
            if (emittedWithoutDemand) {
                fail(new IllegalStateException("Upstream emitted without demand"));
                return;
            }
            synchronized (signalLock) {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                }
                downstream.onNext(update);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable);
        }

        @Override
        public void onComplete() {
            Throwable failure = null;
            boolean complete = false;
            boolean subscribe = false;
            synchronized (signalLock) {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    upstream = null;
                    currentClaim = null;
                    if (hasFunctionCalls) {
                        done = true;
                        complete = true;
                    } else {
                        boolean serverManaged = conversationId != null
                                || continuationToken != null
                                || options.getConversationId() != null
                                || options.getContinuationToken() != null
                                || Boolean.TRUE.equals(
                                        options.getAdditionalProperties().get(
                                                RunContextProperties
                                                        .PER_SERVICE_CALL_HISTORY));
                        ChatOptions nextOptions = serverManaged
                                ? advanceOptions(options, conversationId, continuationToken)
                                : options;
                        List<ChatMessage> base;
                        if (serverManaged) {
                            base = Collections.emptyList();
                        } else {
                            if (!responseContents.isEmpty()) {
                                statelessConversation.add(ChatMessage.builder(
                                                io.github.weidongxu.agentframework.chat.ChatRole.ASSISTANT)
                                        .contents(responseContents)
                                        .build());
                            }
                            base = statelessConversation;
                        }
                        MessageClaim nextClaim = claim(run, base);
                        if (!nextClaim.hasInjectedMessages()) {
                            done = true;
                            complete = true;
                        } else if (iteration >= maxIterations) {
                            done = true;
                            failure = new IllegalStateException(
                                    "Message injection exceeded "
                                            + maxIterations
                                            + " iterations");
                        } else {
                            currentClaim = nextClaim;
                            if (!serverManaged) {
                                statelessConversation.clear();
                                statelessConversation.addAll(nextClaim.messages);
                            }
                            subscribe = true;
                        }
                        options = nextOptions;
                        iteration++;
                        hasFunctionCalls = false;
                        conversationId = null;
                        continuationToken = null;
                        responseContents.clear();
                    }
                }
            }
            if (failure != null) {
                rollback(run);
                emitError(failure);
            } else if (complete) {
                commit(run);
                emitComplete();
            } else if (subscribe) {
                subscribeTurn();
            }
        }

        private void subscribeTurn() {
            try {
                getDelegate().getStreamingResponse(
                                currentClaim.messages,
                                options)
                        .subscribe(this);
            } catch (Throwable error) {
                fail(error);
            }
        }

        private void fail(Throwable error) {
            Flow.Subscription current;
            synchronized (signalLock) {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    done = true;
                    current = upstream;
                    upstream = null;
                    currentClaim = null;
                }
            }
            if (current != null) {
                current.cancel();
            }
            rollback(run);
            emitError(error);
        }

        private void emitError(Throwable error) {
            synchronized (signalLock) {
                downstream.onError(error);
            }
        }

        private void emitComplete() {
            synchronized (signalLock) {
                downstream.onComplete();
            }
        }

    }

    private static long addCap(long current, long increment) {
        long result = current + increment;
        return result < 0 ? Long.MAX_VALUE : result;
    }
}
