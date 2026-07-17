package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokedContext;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agent.AgentSessionCodec;
import io.github.weidongxu.agentframework.agent.ChatHistoryProvider;
import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.middleware.AgentMiddleware;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareContext;
import io.github.weidongxu.agentframework.middleware.AgentMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.AgentStreamingMiddlewareNext;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatClientAgent implements Agent {
    private final ChatClient chatClient;
    private final String id;
    private final String name;
    private final String description;
    private final ChatOptions defaultChatOptions;
    private final ChatHistoryProvider chatHistoryProvider;
    private final List<AIContextProvider> aiContextProviders;
    private final AgentSessionCodec sessionCodec;
    private final List<AgentMiddleware> agentMiddleware;
    private final boolean perServiceCallHistory;

    private ChatClientAgent(Builder builder) {
        this.chatClient = ensureFunctionInvoking(builder.chatClient);
        this.id = builder.id;
        this.name = builder.name;
        this.description = builder.description;
        this.defaultChatOptions = buildDefaultOptions(builder);
        this.chatHistoryProvider = builder.chatHistoryProvider;
        this.aiContextProviders = Collections.unmodifiableList(
                new ArrayList<>(builder.aiContextProviders));
        this.sessionCodec = builder.sessionCodec;
        this.agentMiddleware = Collections.unmodifiableList(
                new ArrayList<>(builder.agentMiddleware));
        this.perServiceCallHistory =
                containsPerServiceCallHistory(chatClient);
    }

    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    public ChatClient getChatClient() {
        return chatClient;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ChatOptions getDefaultChatOptions() {
        return defaultChatOptions;
    }

    public ChatHistoryProvider getChatHistoryProvider() {
        return chatHistoryProvider;
    }

    public List<AIContextProvider> getAIContextProviders() {
        return aiContextProviders;
    }

    @Override
    public CompletionStage<String> serializeSession(AgentSession session) {
        try {
            return CompletableFuture.completedFuture(sessionCodec.serialize(session));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletionStage<AgentSession> deserializeSession(String serializedSession) {
        try {
            return CompletableFuture.completedFuture(
                    sessionCodec.deserialize(serializedSession));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletionStage<AgentResponse> run(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options) {
        AgentMiddlewareContext context =
                new AgentMiddlewareContext(this, messages, session, options, false);
        return invokeAgentMiddleware(context, 0);
    }

    private CompletionStage<AgentResponse> runCore(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options) {
        List<ChatMessage> input = immutableMessages(messages);
        AgentRunOptions safeOptions = options == null ? AgentRunOptions.empty() : options;
        Object serviceSessionId = session == null ? null : session.getServiceSessionId();
        ChatOptions chatOptions =
                mergeOptions(defaultChatOptions, safeOptions, serviceSessionId);
        PreparationTracker tracker = new PreparationTracker();
        CompletableFuture<AgentResponse> result = new CompletableFuture<>();
        prepareInvocation(input, session, serviceSessionId, chatOptions, tracker)
                .whenComplete((invocation, preparationError) -> {
                    Throwable failure = unwrapCompletionError(preparationError);
                    if (failure != null) {
                        notifyPreparationFailure(tracker, input, session, failure)
                                .whenComplete((ignored, notificationError) -> {
                                    Throwable callbackFailure =
                                            unwrapCompletionError(notificationError);
                                    if (callbackFailure != null) {
                                        failure.addSuppressed(callbackFailure);
                                    }
                                    result.completeExceptionally(failure);
                                });
                        return;
                    }
                    runBuffered(invocation).whenComplete((response, runError) -> {
                        Throwable runFailure = unwrapCompletionError(runError);
                        if (runFailure != null) {
                            result.completeExceptionally(runFailure);
                        } else {
                            result.complete(response);
                        }
                    });
                });
        return result;
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options) {
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            AgentMiddlewareContext context;
            Flow.Publisher<AgentResponseUpdate> publisher;
            try {
                context = new AgentMiddlewareContext(
                        this,
                        messages,
                        session,
                        options,
                        true);
                publisher = Objects.requireNonNull(
                        invokeAgentStreamingMiddleware(context, 0),
                        "Agent middleware returned null Publisher");
            } catch (Throwable error) {
                ChatClientAgent.<AgentResponseUpdate>failedPublisher(error)
                        .subscribe(subscriber);
                return;
            }
            publisher.subscribe(subscriber);
        };
    }

    private Flow.Publisher<AgentResponseUpdate> runStreamingCore(
            List<ChatMessage> messages,
            AgentSession session,
            AgentRunOptions options) {
        List<ChatMessage> input = immutableMessages(messages);
        AgentRunOptions safeOptions = options == null ? AgentRunOptions.empty() : options;

        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            Object serviceSessionId =
                    session == null ? null : session.getServiceSessionId();
            ChatOptions chatOptions;
            try {
                chatOptions =
                        mergeOptions(defaultChatOptions, safeOptions, serviceSessionId);
            } catch (Throwable error) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(error);
                return;
            }
            StreamingRun run = new StreamingRun(
                    subscriber,
                    input,
                    session,
                    serviceSessionId,
                    chatOptions,
                    new PreparationTracker());
            subscriber.onSubscribe(run);
            run.start();
        };
    }

    private CompletionStage<AgentResponse> invokeAgentMiddleware(
            AgentMiddlewareContext context,
            int index) {
        if (index == agentMiddleware.size()) {
            return runCore(
                    context.getMessages(),
                    context.getSession(),
                    context.getOptions());
        }
        AgentMiddleware current = agentMiddleware.get(index);
        AtomicBoolean called = new AtomicBoolean();
        AgentMiddlewareNext next = nextContext -> {
            if (!called.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Agent middleware called next more than once"));
            }
            return invokeAgentMiddleware(nextContext, index + 1);
        };
        try {
            return Objects.requireNonNull(
                    current.invoke(context, next),
                    "Agent middleware returned null CompletionStage");
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private Flow.Publisher<AgentResponseUpdate> invokeAgentStreamingMiddleware(
            AgentMiddlewareContext context,
            int index) {
        if (index == agentMiddleware.size()) {
            return runStreamingCore(
                    context.getMessages(),
                    context.getSession(),
                    context.getOptions());
        }
        AgentMiddleware current = agentMiddleware.get(index);
        AtomicBoolean called = new AtomicBoolean();
        AgentStreamingMiddlewareNext next = nextContext -> {
            if (!called.compareAndSet(false, true)) {
                return failedPublisher(
                        new IllegalStateException("Agent middleware called next more than once"));
            }
            return invokeAgentStreamingMiddleware(nextContext, index + 1);
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

    private CompletionStage<PreparedInvocation> prepareInvocation(
            List<ChatMessage> input,
            AgentSession session,
            Object serviceSessionId,
            ChatOptions chatOptions,
            PreparationTracker tracker) {
        AIContext initial = AIContext.builder()
                .instructions(chatOptions.getInstructions())
                .messages(input)
                .tools(chatOptions.getTools())
                .build();
        CompletionStage<AIContext> stage = CompletableFuture.completedFuture(initial);
        boolean useLocalHistory = chatOptions.getContinuationToken() == null
                && chatOptions.getConversationId() == null;
        if (chatHistoryProvider != null
                && useLocalHistory
                && !perServiceCallHistory) {
            stage = stage.thenCompose(context -> {
                tracker.historyProviderInvoked = true;
                return chatHistoryProvider
                    .invoking(new AgentInvokingContext(this, session, context))
                    .thenApply(messages -> AIContext.builder()
                            .instructions(context.getInstructions())
                            .messages(messages)
                            .tools(context.getTools())
                            .build());
            });
        }
        for (AIContextProvider provider : aiContextProviders) {
            stage = stage.thenCompose(context -> {
                tracker.contextProvidersInvoked.add(provider);
                return provider.invoking(new AgentInvokingContext(this, session, context));
            });
        }
        return stage.thenApply(context ->
                prepared(
                        input,
                        session,
                        serviceSessionId,
                        chatOptions,
                        context,
                        useLocalHistory));
    }

    private PreparedInvocation prepared(
            List<ChatMessage> input,
            AgentSession session,
            Object serviceSessionId,
            ChatOptions baseOptions,
            AIContext context,
            boolean useLocalHistory) {
        ChatOptions.Builder options = baseOptions.toBuilder()
                .instructions(context.getInstructions())
                .clearTools()
                .tools(context.getTools());
        if (session != null) {
            options.additionalProperty(
                    RunContextProperties.AGENT_SESSION,
                    session);
        }
        options.additionalProperty(RunContextProperties.AGENT, this);
        if (chatHistoryProvider != null) {
            options.additionalProperty(
                    RunContextProperties.CHAT_HISTORY_PROVIDER,
                    chatHistoryProvider);
        }
        if (perServiceCallHistory) {
            options.additionalProperty(
                    RunContextProperties.PER_SERVICE_CALL_HISTORY,
                    true);
        }
        ChatOptions resolved = options.build();
        validateToolNames(resolved.getTools());
        return new PreparedInvocation(
                context.getMessages(),
                input,
                session,
                serviceSessionId,
                resolved,
                useLocalHistory);
    }

    private CompletionStage<AgentResponse> runBuffered(PreparedInvocation invocation) {
        CompletableFuture<AgentResponse> result = new CompletableFuture<>();
        CompletionStage<ChatResponse> responseStage;
        try {
            responseStage = Objects.requireNonNull(
                    chatClient.getResponse(invocation.modelMessages, invocation.chatOptions),
                    "ChatClient returned null CompletionStage");
        } catch (Throwable error) {
            finishBuffered(invocation, null, error, result);
            return result;
        }
        responseStage.whenComplete((response, error) ->
                finishBuffered(
                        invocation,
                        response,
                        error == null && response == null
                                ? new NullPointerException(
                                        "ChatClient completed with null response")
                                : unwrapCompletionError(error),
                        result));
        return result;
    }

    private void finishBuffered(
            PreparedInvocation invocation,
            ChatResponse response,
            Throwable invocationError,
            CompletableFuture<AgentResponse> result) {
        List<ChatMessage> responseMessages =
                response == null ? Collections.emptyList() : response.getMessages();
        Throwable completionError = invocationError;
        if (completionError == null
                && response != null
                && !perServiceCallHistory) {
            try {
                updateServiceSessionId(
                        invocation.session,
                        invocation.serviceSessionId,
                        response.getConversationId(),
                        response.getContinuationToken());
            } catch (Throwable error) {
                completionError = error;
            }
        }
        Throwable finalCompletionError = completionError;
        notifyProviders(invocation, responseMessages, finalCompletionError)
                .whenComplete((ignored, notificationError) -> {
                    Throwable callbackFailure = unwrapCompletionError(notificationError);
                    if (finalCompletionError != null) {
                        if (callbackFailure != null) {
                            finalCompletionError.addSuppressed(callbackFailure);
                        }
                        result.completeExceptionally(finalCompletionError);
                    } else if (callbackFailure != null) {
                        result.completeExceptionally(callbackFailure);
                    } else {
                        result.complete(toAgentResponse(Objects.requireNonNull(
                                response,
                                "ChatClient completed with null response")));
                    }
                });
    }

    private CompletionStage<Void> notifyProviders(
            PreparedInvocation invocation,
            List<ChatMessage> responseMessages,
            Throwable invocationError) {
        AgentInvokedContext context = new AgentInvokedContext(
                this,
                invocation.session,
                invocation.requestMessages,
                responseMessages,
                invocationError);
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        if (invocation.useLocalHistory
                && chatHistoryProvider != null
                && !perServiceCallHistory) {
            stage = stage.thenCompose(ignored -> chatHistoryProvider.invoked(context));
        }
        for (AIContextProvider provider : aiContextProviders) {
            stage = stage.thenCompose(ignored -> provider.invoked(context));
        }
        return stage;
    }

    private CompletionStage<Void> notifyPreparationFailure(
            PreparationTracker tracker,
            List<ChatMessage> requestMessages,
            AgentSession session,
            Throwable error) {
        AgentInvokedContext context = new AgentInvokedContext(
                this,
                session,
                requestMessages,
                Collections.emptyList(),
                error);
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        if (tracker.historyProviderInvoked && chatHistoryProvider != null) {
            stage = stage.thenCompose(ignored -> chatHistoryProvider.invoked(context));
        }
        for (AIContextProvider provider : tracker.contextProvidersInvoked) {
            stage = stage.thenCompose(ignored -> provider.invoked(context));
        }
        return stage;
    }

    private AgentResponse toAgentResponse(ChatResponse response) {
        AgentResponse.Builder result = AgentResponse.builder()
                .messages(response.getMessages())
                .responseId(response.getResponseId())
                .conversationId(response.getConversationId())
                .agentId(id)
                .continuationToken(response.getContinuationToken())
                .finishReason(response.getFinishReason())
                .usage(response.getUsage())
                .rawRepresentation(response.getRawRepresentation());
        response.getAdditionalProperties().forEach(result::additionalProperty);
        return result.build();
    }

    private AgentResponseUpdate toAgentResponseUpdate(ChatResponseUpdate update) {
        AgentResponseUpdate.Builder result = AgentResponseUpdate.builder()
                .contents(update.getContents())
                .role(update.getRole())
                .messageId(update.getMessageId())
                .responseId(update.getResponseId())
                .conversationId(update.getConversationId())
                .continuationToken(update.getContinuationToken())
                .agentId(id)
                .finishReason(update.getFinishReason())
                .rawRepresentation(update.getRawRepresentation());
        update.getAdditionalProperties().forEach(result::additionalProperty);
        return result.build();
    }

    private static ChatOptions buildDefaultOptions(Builder builder) {
        ChatOptions.Builder options = builder.chatOptions == null
                ? ChatOptions.builder()
                : builder.chatOptions.toBuilder();
        if (builder.instructions != null) {
            options.instructions(builder.instructions);
        }
        options.tools(builder.tools);
        return options.build();
    }

    private static ChatOptions mergeOptions(
            ChatOptions defaults,
            AgentRunOptions runOptions,
            Object serviceSessionId) {
        ChatOptions overrides = runOptions.getChatOptions();
        ChatOptions.Builder merged = defaults.toBuilder();
        applyServiceSessionId(defaults, serviceSessionId, merged);

        if (overrides != null) {
            if (overrides.getModelId() != null) {
                merged.modelId(overrides.getModelId());
            }
            if (overrides.getInstructions() != null) {
                merged.instructions(overrides.getInstructions());
            }
            if (overrides.getConversationId() != null) {
                merged.continuationToken(null)
                        .conversationId(overrides.getConversationId());
            }
            if (overrides.getContinuationToken() != null) {
                merged.conversationId(null)
                        .continuationToken(overrides.getContinuationToken());
            }
            if (overrides.isToolsSpecified()) {
                merged.clearTools().tools(overrides.getTools());
            }
            if (overrides.getTemperature() != null) {
                merged.temperature(overrides.getTemperature());
            }
            if (overrides.getMaxOutputTokens() != null) {
                merged.maxOutputTokens(overrides.getMaxOutputTokens());
            }
            if (overrides.getResponseFormat() != null) {
                merged.responseFormat(overrides.getResponseFormat());
            }
            overrides.getAdditionalProperties().forEach(merged::additionalProperty);
        }

        if (runOptions.getContinuationToken() != null) {
            merged.conversationId(null)
                    .continuationToken(runOptions.getContinuationToken());
        }
        runOptions.getAdditionalProperties().forEach(merged::additionalProperty);
        ChatOptions result = merged.build();
        validateToolNames(result.getTools());
        return result;
    }

    private static void applyServiceSessionId(
            ChatOptions defaults,
            Object serviceSessionId,
            ChatOptions.Builder options) {
        if (serviceSessionId == null) {
            return;
        }
        if (serviceSessionId instanceof String) {
            options.conversationId(null)
                    .continuationToken((String) serviceSessionId);
            return;
        }
        if (!(serviceSessionId instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "ChatClientAgent service session ID must be a string or object");
        }
        Map<?, ?> state = (Map<?, ?>) serviceSessionId;
        Object conversationId = state.get("conversation_id");
        Object continuationToken = state.get("previous_response_id");
        if (conversationId instanceof String && !((String) conversationId).isBlank()) {
            options.continuationToken(null)
                    .conversationId((String) conversationId);
        } else if (continuationToken instanceof String
                && !((String) continuationToken).isBlank()) {
            options.conversationId(null)
                    .continuationToken((String) continuationToken);
        } else {
            throw new IllegalArgumentException(
                    "ChatClientAgent service session ID must contain conversation_id "
                            + "or previous_response_id");
        }
    }

    private static void updateServiceSessionId(
            AgentSession session,
            Object expectedServiceSessionId,
            String conversationId,
            String continuationToken) {
        if (session == null) {
            return;
        }

        Object updated = null;
        if (conversationId != null && !conversationId.isBlank()) {
            updated = Collections.singletonMap("conversation_id", conversationId);
        } else if (continuationToken != null && !continuationToken.isBlank()) {
            updated = Collections.singletonMap("previous_response_id", continuationToken);
        }
        if (updated != null
                && !session.compareAndSetServiceSessionId(
                        expectedServiceSessionId,
                        updated)) {
            throw new ConcurrentModificationException(
                    "Agent session service state changed during invocation");
        }
    }

    private static boolean containsPerServiceCallHistory(ChatClient client) {
        ChatClient current = client;
        while (current instanceof DelegatingChatClient) {
            if (current instanceof PerServiceCallHistoryChatClient) {
                return true;
            }
            current = ((DelegatingChatClient) current).getInnerClient();
        }
        return current instanceof PerServiceCallHistoryChatClient;
    }

    /**
     * Ensures the chat pipeline can execute local function tools by wrapping it in a
     * {@link FunctionInvokingChatClient} when one is not already present, mirroring how
     * microsoft/agent-framework composes the default agent middleware. Callers may still supply a
     * pre-wrapped/customized {@code FunctionInvokingChatClient} (e.g. with an approval store or
     * custom iteration limit); in that case the pipeline is used as-is.
     */
    private static ChatClient ensureFunctionInvoking(ChatClient client) {
        Objects.requireNonNull(client, "chatClient");
        return containsFunctionInvoking(client)
                ? client
                : new FunctionInvokingChatClient(client);
    }

    private static boolean containsFunctionInvoking(ChatClient client) {
        ChatClient current = client;
        while (current instanceof DelegatingChatClient) {
            if (current instanceof FunctionInvokingChatClient) {
                return true;
            }
            current = ((DelegatingChatClient) current).getInnerClient();
        }
        return current instanceof FunctionInvokingChatClient;
    }

    private static List<ChatMessage> immutableMessages(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public static final class Builder {
        private final ChatClient chatClient;
        private String id;
        private String name;
        private String description;
        private String instructions;
        private ChatOptions chatOptions;
        private final List<Tool> tools = new ArrayList<>();
        private ChatHistoryProvider chatHistoryProvider;
        private final List<AIContextProvider> aiContextProviders = new ArrayList<>();
        private AgentSessionCodec sessionCodec = AgentSessionCodec.standard();
        private final List<AgentMiddleware> agentMiddleware = new ArrayList<>();

        private Builder(ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = chatOptions;
            return this;
        }

        public Builder tool(Tool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder tools(List<? extends Tool> tools) {
            Objects.requireNonNull(tools, "tools").forEach(this::tool);
            return this;
        }

        public Builder chatHistoryProvider(ChatHistoryProvider chatHistoryProvider) {
            this.chatHistoryProvider = chatHistoryProvider;
            return this;
        }

        public Builder aiContextProvider(AIContextProvider provider) {
            aiContextProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        public Builder aiContextProviders(List<? extends AIContextProvider> providers) {
            Objects.requireNonNull(providers, "providers").forEach(this::aiContextProvider);
            return this;
        }

        public Builder sessionCodec(AgentSessionCodec sessionCodec) {
            this.sessionCodec = Objects.requireNonNull(sessionCodec, "sessionCodec");
            return this;
        }

        public Builder middleware(AgentMiddleware middleware) {
            agentMiddleware.add(Objects.requireNonNull(middleware, "middleware"));
            return this;
        }

        public Builder middleware(List<? extends AgentMiddleware> middleware) {
            Objects.requireNonNull(middleware, "middleware").forEach(this::middleware);
            return this;
        }

        public ChatClientAgent build() {
            List<Tool> allTools = new ArrayList<>();
            if (chatOptions != null) {
                allTools.addAll(chatOptions.getTools());
            }
            allTools.addAll(tools);
            ChatClientAgent.validateToolNames(allTools);
            ChatClientAgent.validateProviderStateKeys(
                    chatHistoryProvider,
                    aiContextProviders);
            return new ChatClientAgent(this);
        }
    }

    private static void validateToolNames(List<? extends Tool> tools) {
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            if (!names.add(tool.getName())) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.getName());
            }
        }
    }

    private static void validateProviderStateKeys(
                ChatHistoryProvider historyProvider,
                List<? extends AIContextProvider> contextProviders) {
            Set<String> keys = new HashSet<>();
            if (historyProvider != null) {
                addProviderStateKeys(keys, historyProvider.getStateKeys());
            }
            for (AIContextProvider provider : contextProviders) {
                addProviderStateKeys(keys, provider.getStateKeys());
            }
        }

        private static void addProviderStateKeys(Set<String> keys, List<String> providerKeys) {
            Objects.requireNonNull(providerKeys, "provider state keys");
            for (String key : providerKeys) {
                if (!keys.add(Objects.requireNonNull(key, "provider state key"))) {
                    throw new IllegalArgumentException("Duplicate provider state key: " + key);
                }
            }
        }

        private static Throwable unwrapCompletionError(Throwable error) {
            Throwable cause = error;
            while ((cause instanceof java.util.concurrent.CompletionException
                    || cause instanceof java.util.concurrent.ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause;
        }

        private static final class PreparedInvocation {
            private final List<ChatMessage> modelMessages;
            private final List<ChatMessage> requestMessages;
            private final AgentSession session;
            private final Object serviceSessionId;
            private final ChatOptions chatOptions;
            private final boolean useLocalHistory;

            private PreparedInvocation(
                    List<ChatMessage> modelMessages,
                    List<ChatMessage> requestMessages,
                    AgentSession session,
                    Object serviceSessionId,
                    ChatOptions chatOptions,
                    boolean useLocalHistory) {
                this.modelMessages = Collections.unmodifiableList(new ArrayList<>(modelMessages));
                this.requestMessages = requestMessages;
                this.session = session;
                this.serviceSessionId = serviceSessionId;
                this.chatOptions = chatOptions;
                this.useLocalHistory = useLocalHistory;
            }
        }

        private static final class PreparationTracker {
            private boolean historyProviderInvoked;
            private final List<AIContextProvider> contextProvidersInvoked = new ArrayList<>();
        }

        private final class StreamingRun
                implements Flow.Subscription, Flow.Subscriber<ChatResponseUpdate> {
            private final Flow.Subscriber<? super AgentResponseUpdate> downstream;
            private final List<ChatMessage> input;
            private final AgentSession session;
            private final Object serviceSessionId;
            private final ChatOptions chatOptions;
            private final PreparationTracker preparationTracker;
            private final List<ChatResponseUpdate> updates = new ArrayList<>();
            private final Object signalLock = new Object();

            private PreparedInvocation invocation;
            private Flow.Subscription upstream;
            private long demand;
            private boolean started;
            private boolean finishing;
            private boolean done;

            private StreamingRun(
                    Flow.Subscriber<? super AgentResponseUpdate> downstream,
                    List<ChatMessage> input,
                    AgentSession session,
                    Object serviceSessionId,
                    ChatOptions chatOptions,
                    PreparationTracker preparationTracker) {
                this.downstream = downstream;
                this.input = input;
                this.session = session;
                this.serviceSessionId = serviceSessionId;
                this.chatOptions = chatOptions;
                this.preparationTracker = preparationTracker;
            }

            private synchronized void start() {
                if (started || done) {
                    return;
                }
                started = true;
                prepareInvocation(
                                input,
                                session,
                                serviceSessionId,
                                chatOptions,
                                preparationTracker)
                        .whenComplete((prepared, error) -> {
                            Throwable failure = unwrapCompletionError(error);
                            synchronized (StreamingRun.this) {
                                if (done) {
                                    return;
                                }
                                if (failure == null) {
                                    invocation = prepared;
                                }
                            }
                            if (failure != null) {
                                notifyPreparationFailure(
                                                preparationTracker,
                                                input,
                                                session,
                                                failure)
                                        .whenComplete((ignored, notificationError) -> {
                                            Throwable callbackFailure =
                                                    unwrapCompletionError(notificationError);
                                            if (callbackFailure != null) {
                                                failure.addSuppressed(callbackFailure);
                                            }
                                            fail(failure);
                                        });
                                return;
                            }
                            try {
                                chatClient.getStreamingResponse(
                                                prepared.modelMessages,
                                                prepared.chatOptions)
                                        .subscribe(StreamingRun.this);
                            } catch (Throwable subscribeError) {
                                finish(subscribeError);
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
                AgentResponseUpdate mapped;
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    if (demand == 0) {
                        fail(new IllegalStateException("Upstream emitted without demand"));
                        return;
                    }
                    if (demand != Long.MAX_VALUE) {
                        demand--;
                    }
                    updates.add(update);
                    mapped = toAgentResponseUpdate(update);
                }
                synchronized (signalLock) {
                    synchronized (this) {
                        if (done) {
                            return;
                        }
                    }
                    downstream.onNext(mapped);
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

            private void finish(Throwable invocationError) {
                PreparedInvocation currentInvocation;
                List<ChatMessage> responseMessages;
                synchronized (this) {
                    if (done || finishing) {
                        return;
                    }
                    finishing = true;
                    upstream = null;
                    currentInvocation = invocation;
                    responseMessages = responseMessages(updates);
                }
                if (currentInvocation == null) {
                    fail(invocationError == null
                            ? new IllegalStateException("Streaming invocation was not prepared")
                            : invocationError);
                    return;
                }
                Throwable completionError = invocationError;
                if (completionError == null && !perServiceCallHistory) {
                    try {
                        ChatResponseUpdate serviceState = lastServiceState(updates);
                        if (serviceState != null) {
                            updateServiceSessionId(
                                    currentInvocation.session,
                                    currentInvocation.serviceSessionId,
                                    serviceState.getConversationId(),
                                    serviceState.getContinuationToken());
                        }
                    } catch (Throwable error) {
                        completionError = error;
                    }
                }
                Throwable finalCompletionError = completionError;
                notifyProviders(currentInvocation, responseMessages, finalCompletionError)
                        .whenComplete((ignored, notificationError) -> {
                            Throwable callbackFailure = unwrapCompletionError(notificationError);
                            if (finalCompletionError != null) {
                                if (callbackFailure != null) {
                                    finalCompletionError.addSuppressed(callbackFailure);
                                }
                                fail(finalCompletionError);
                            } else if (callbackFailure != null) {
                                fail(callbackFailure);
                            } else {
                                complete();
                            }
                        });
            }

            private void fail(Throwable error) {
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
                synchronized (signalLock) {
                    downstream.onError(error);
                }
            }

            private void complete() {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    done = true;
                }
                synchronized (signalLock) {
                    downstream.onComplete();
                }
            }
        }

        private static List<ChatMessage> responseMessages(List<ChatResponseUpdate> updates) {
            List<ChatResponseUpdate> finalTurn = new ArrayList<>();
            for (ChatResponseUpdate update : updates) {
                finalTurn.add(update);
                boolean invokesTool = update.getFinishReason() == FinishReason.TOOL_CALLS
                        || update.getContents().stream()
                                .anyMatch(FunctionCallContent.class::isInstance);
                if (invokesTool) {
                    finalTurn.clear();
                }
            }

            Map<String, MessageAccumulator> messages = new LinkedHashMap<>();
            for (ChatResponseUpdate update : finalTurn) {
                if (update.getContents().isEmpty()) {
                    continue;
                }
                ChatRole role = update.getRole() == null ? ChatRole.ASSISTANT : update.getRole();
                String key = update.getMessageId() == null
                        ? "role:" + role
                        : "id:" + update.getMessageId();
                MessageAccumulator accumulator =
                        messages.computeIfAbsent(key, ignored -> new MessageAccumulator(role));
                for (ChatContent content : update.getContents()) {
                    if (!(content instanceof FunctionCallContent)
                            && !(content instanceof FunctionResultContent)) {
                        accumulator.contents.add(content);
                    }
                }
            }
            List<ChatMessage> result = new ArrayList<>();
            for (MessageAccumulator accumulator : messages.values()) {
                if (!accumulator.contents.isEmpty()) {
                    result.add(ChatMessage.builder(accumulator.role)
                            .contents(accumulator.contents)
                            .build());
                }
            }
            return result;
        }

        private static ChatResponseUpdate lastServiceState(
                List<ChatResponseUpdate> updates) {
            for (int index = updates.size() - 1; index >= 0; index--) {
                ChatResponseUpdate update = updates.get(index);
                if ((update.getConversationId() != null
                        && !update.getConversationId().isBlank())
                        || (update.getContinuationToken() != null
                        && !update.getContinuationToken().isBlank())) {
                    return update;
                }
            }
            return null;
        }

        private static long addCap(long current, long increment) {
            long result = current + increment;
            return result < 0 ? Long.MAX_VALUE : result;
        }

        private static final class MessageAccumulator {
            private final ChatRole role;
            private final List<ChatContent> contents = new ArrayList<>();

            private MessageAccumulator(ChatRole role) {
                this.role = role;
            }
        }
}
