package io.github.weidongxu.agentframework.impl;

import io.github.weidongxu.agentframework.chat.ChatClient;
import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.PlatformCallContext;
import io.github.weidongxu.agentframework.chat.PlatformRequestHeaders;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import io.github.weidongxu.agentframework.middleware.FunctionInvocationContext;
import io.github.weidongxu.agentframework.middleware.FunctionMiddleware;
import io.github.weidongxu.agentframework.middleware.FunctionMiddlewareNext;
import io.github.weidongxu.agentframework.middleware.ProgressiveToolRegistry;
import io.github.weidongxu.agentframework.tool.ApprovalMode;
import io.github.weidongxu.agentframework.tool.Tool;
import io.github.weidongxu.agentframework.tool.ToolApprovalBatch;
import io.github.weidongxu.agentframework.tool.ToolApprovalClaim;
import io.github.weidongxu.agentframework.tool.ToolApprovalStore;
import io.github.weidongxu.agentframework.tool.ToolContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FunctionInvokingChatClient extends DelegatingChatClient {
    public static final int DEFAULT_MAX_ITERATIONS = 8;
    public static final Duration DEFAULT_APPROVAL_LEASE =
            Duration.ofMinutes(5);

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    private final ObjectMapper objectMapper;
    private final int maxIterations;
    private final List<FunctionMiddleware> functionMiddleware;
    private final ToolApprovalStore approvalStore;
    private final Duration approvalLease;
    private final Clock clock;
    private final Map<String, List<Tool>> runtimeToolsByBatchId =
            new ConcurrentHashMap<>();
    private final Map<String, String> batchIdsByRequestId =
            new ConcurrentHashMap<>();

    public FunctionInvokingChatClient(ChatClient delegate) {
        this(
                delegate,
                new ObjectMapper(),
                DEFAULT_MAX_ITERATIONS,
                Collections.emptyList(),
                new InMemoryToolApprovalStore(),
                DEFAULT_APPROVAL_LEASE,
                Clock.systemUTC());
    }

    public FunctionInvokingChatClient(
            ChatClient delegate,
            ObjectMapper objectMapper,
            int maxIterations) {
        this(
                delegate,
                objectMapper,
                maxIterations,
                Collections.emptyList(),
                new InMemoryToolApprovalStore(),
                DEFAULT_APPROVAL_LEASE,
                Clock.systemUTC());
    }

    public FunctionInvokingChatClient(
            ChatClient delegate,
            ObjectMapper objectMapper,
            int maxIterations,
            List<? extends FunctionMiddleware> functionMiddleware) {
        this(
                delegate,
                objectMapper,
                maxIterations,
                functionMiddleware,
                new InMemoryToolApprovalStore(),
                DEFAULT_APPROVAL_LEASE,
                Clock.systemUTC());
    }

    public FunctionInvokingChatClient(
            ChatClient delegate,
            ObjectMapper objectMapper,
            int maxIterations,
            List<? extends FunctionMiddleware> functionMiddleware,
            ToolApprovalStore approvalStore,
            Duration approvalLease) {
        this(
                delegate,
                objectMapper,
                maxIterations,
                functionMiddleware,
                approvalStore,
                approvalLease,
                Clock.systemUTC());
    }

    FunctionInvokingChatClient(
            ChatClient delegate,
            ObjectMapper objectMapper,
            int maxIterations,
            List<? extends FunctionMiddleware> functionMiddleware,
            ToolApprovalStore approvalStore,
            Duration approvalLease,
            Clock clock) {
        super(delegate);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
        this.functionMiddleware = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        functionMiddleware,
                        "functionMiddleware")));
        this.approvalStore = Objects.requireNonNull(
                approvalStore,
                "approvalStore");
        this.approvalLease = Objects.requireNonNull(
                approvalLease,
                "approvalLease");
        if (approvalLease.isZero() || approvalLease.isNegative()) {
            throw new IllegalArgumentException(
                    "approvalLease must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        List<ChatMessage> conversation = mutableMessages(messages);
        LiveToolRegistry tools = new LiveToolRegistry(options.getTools());
        return resolveApprovalResponses(conversation, tools, options)
                .thenCompose(resolution -> runBuffered(
                        resolution.conversation,
                        withTools(options, resolution.tools.getTools()),
                        resolution.tools,
                        new ArrayList<>(resolution.results),
                        0));
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        List<ChatMessage> conversation = mutableMessages(messages);
        LiveToolRegistry tools = new LiveToolRegistry(options.getTools());
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            StreamingToolLoop loop = new StreamingToolLoop(subscriber, conversation, options, tools);
            subscriber.onSubscribe(loop);
            loop.start();
        };
    }

    private CompletionStage<ChatResponse> runBuffered(
            List<ChatMessage> conversation,
            ChatOptions options,
            LiveToolRegistry tools,
            List<ChatMessage> generated,
            int iteration) {
        return getDelegate().getResponse(
                        Collections.unmodifiableList(new ArrayList<>(conversation)),
                        options)
                .thenCompose(response -> {
                    List<FunctionCallContent> calls = functionCalls(response.getMessages());
                    generated.addAll(response.getMessages());
                    if (calls.isEmpty()) {
                        return CompletableFuture.completedFuture(copyWithMessages(response, generated));
                    }
                    if (iteration >= maxIterations) {
                        return failedFuture(new ToolInvocationException(
                                "Tool invocation exceeded " + maxIterations + " iterations"));
                    }
                    if (requiresApproval(calls, tools)) {
                        return approvalRequests(
                                calls,
                                approvalScope(
                                        response.getConversationId(),
                                        response.getContinuationToken(),
                                        options),
                                hasServerManagedState(response, options)
                                        ? null
                                        : appendMessages(
                                                conversation,
                                                response.getMessages()),
                                tools).thenApply(requests -> {
                                    generated.addAll(requests.messages);
                                    return copyWithMessages(
                                            response,
                                            generated);
                                });
                    }
                    return invokeTools(calls, tools, options).thenCompose(results -> {
                        generated.addAll(results);
                        List<ChatMessage> nextConversation;
                        ChatOptions nextOptions;
                        if (hasServerManagedState(response, options)) {
                            nextConversation = new ArrayList<>(results);
                            nextOptions = advanceOptions(
                                    withTools(options, tools.getTools()),
                                    response.getConversationId(),
                                    response.getContinuationToken());
                        } else {
                            conversation.addAll(response.getMessages());
                            conversation.addAll(results);
                            nextConversation = conversation;
                            nextOptions = withTools(options, tools.getTools());
                        }
                        return runBuffered(
                                nextConversation,
                                nextOptions,
                                tools,
                                generated,
                                iteration + 1);
                    });
                });
    }

    private CompletionStage<List<ChatMessage>> invokeTools(
            List<FunctionCallContent> calls,
            LiveToolRegistry tools,
            ChatOptions options) {
        Map<String, Tool> batchTools = tools.snapshot();
        List<CompletableFuture<ChatMessage>> futures = new ArrayList<>();
        for (FunctionCallContent call : calls) {
            futures.add(invokeTool(
                    call,
                    batchTools.get(call.getName()),
                    tools,
                    options).toCompletableFuture());
        }
        CompletableFuture<Void> all =
                CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
        return all.thenApply(ignored -> {
            List<ChatMessage> results = new ArrayList<>(futures.size());
            for (CompletableFuture<ChatMessage> future : futures) {
                results.add(future.join());
            }
            return results;
        });
    }

    private CompletionStage<ApprovalResolution> resolveApprovalResponses(
            List<ChatMessage> messages,
            LiveToolRegistry tools,
            ChatOptions options) {
        List<ToolApprovalResponseContent> responses = approvalResponses(messages);
        if (responses.isEmpty()) {
            List<ChatMessage> conversation =
                    withoutApprovalContent(messages, false);
            return CompletableFuture.completedFuture(
                    new ApprovalResolution(
                            conversation,
                            Collections.emptyList(),
                            tools));
        }
        Set<String> responseIds = approvalResponseIds(responses);
        return approvalStore.claim(
                        responseIds,
                        approvalScope(null, null, options),
                        approvalLease)
                .handle((claim, error) -> {
                    if (error != null) {
                        throw approvalFailure(error);
                    }
                    return claim;
                })
                .thenCompose(claim -> resolveClaim(
                        claim,
                        responses,
                        messages,
                        tools,
                        options));
    }

    private CompletionStage<ApprovalResolution> resolveClaim(
            ToolApprovalClaim claim,
            List<ToolApprovalResponseContent> responses,
            List<ChatMessage> messages,
            LiveToolRegistry currentTools,
            ChatOptions options) {
        ToolApprovalBatch batch = claim.getBatch();
        List<ChatMessage> suppliedConversation =
                withoutApprovalContent(messages, true);
        List<ChatMessage> conversation = restoreConversation(
                batch.getResumeConversation(),
                suppliedConversation);
        List<Tool> runtimeTools =
                runtimeToolsByBatchId.get(batch.getId());
        LiveToolRegistry resolvedTools = new LiveToolRegistry(
                runtimeTools == null ? currentTools.getTools() : runtimeTools);
        Map<String, Tool> batchTools = resolvedTools.snapshot();
        if (!batchTools.keySet().containsAll(batch.getToolNames())) {
            ToolInvocationException failure = new ToolInvocationException(
                    "Approval resume is missing tools from the paused invocation");
            CompletableFuture<ApprovalResolution> result =
                    new CompletableFuture<>();
            approvalStore.release(
                            batch.getId(),
                            claim.getFencingToken())
                    .whenComplete((ignored, releaseError) -> {
                        if (releaseError != null) {
                            failure.addSuppressed(
                                    unwrapCompletionError(releaseError));
                        }
                        result.completeExceptionally(failure);
                    });
            return result;
        }
        List<CompletableFuture<ChatMessage>> futures = new ArrayList<>();
        for (ToolApprovalResponseContent response : responses) {
            FunctionCallContent call =
                    batch.getCallsByRequestId().get(response.getRequestId());
            if (response.isApproved()) {
                futures.add(invokeTool(
                        call,
                        batchTools.get(call.getName()),
                        resolvedTools,
                        options).toCompletableFuture());
            } else {
                String reason = response.getReason();
                futures.add(CompletableFuture.completedFuture(
                        ChatMessage.builder(ChatRole.TOOL)
                                .authorName(call.getName())
                                .addContent(new FunctionResultContent(
                                        call.getCallId(),
                                        reason == null || reason.isBlank()
                                                ? "Tool invocation rejected"
                                                : reason,
                                        true))
                                .build()));
            }
        }
        CompletableFuture<Void> all =
                CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture<?>[0]));
        CompletableFuture<ApprovalResolution> result =
                new CompletableFuture<>();
        all.whenComplete((ignored, error) -> {
            if (error != null) {
                Throwable failure = unwrapCompletionError(error);
                approvalStore.release(
                                batch.getId(),
                                claim.getFencingToken())
                        .whenComplete((released, releaseError) -> {
                            if (releaseError != null) {
                                failure.addSuppressed(
                                        unwrapCompletionError(releaseError));
                            }
                            result.completeExceptionally(failure);
                        });
                return;
            }
            List<ChatMessage> results = new ArrayList<>(futures.size());
            for (CompletableFuture<ChatMessage> future : futures) {
                results.add(future.join());
            }
            approvalStore.complete(
                            batch.getId(),
                            claim.getFencingToken())
                    .whenComplete((completed, completeError) -> {
                        if (completeError != null) {
                            result.completeExceptionally(
                                    unwrapCompletionError(completeError));
                            return;
                        }
                        removeRuntimeApproval(batch);
                        conversation.addAll(results);
                        result.complete(new ApprovalResolution(
                                conversation,
                                results,
                                resolvedTools));
                    });
        });
        return result;
    }

    private boolean requiresApproval(
            List<FunctionCallContent> calls,
            LiveToolRegistry tools) {
        boolean requiresApproval = false;
        boolean allowsImmediateInvocation = false;
        for (FunctionCallContent call : calls) {
            Tool tool = tools.get(call.getName());
            if (tool == null) {
                throw new ToolInvocationException(
                        "Model requested unknown tool: " + call.getName());
            }
            if (tool.getApprovalMode() == ApprovalMode.ALWAYS_REQUIRE) {
                requiresApproval = true;
            } else {
                allowsImmediateInvocation = true;
            }
        }
        if (requiresApproval && allowsImmediateInvocation) {
            throw new ToolInvocationException(
                    "A tool-call batch cannot mix approval-required and immediate tools");
        }
        return requiresApproval;
    }

    private CompletionStage<ApprovalRequestBatch> approvalRequests(
            List<FunctionCallContent> calls,
            String scope,
            List<ChatMessage> resumeConversation,
            LiveToolRegistry tools) {
        List<ChatMessage> requests = new ArrayList<>(calls.size());
        Map<String, FunctionCallContent> callsByRequestId = new LinkedHashMap<>();
        for (FunctionCallContent call : calls) {
            String requestId = "approval_" + java.util.UUID.randomUUID();
            callsByRequestId.put(requestId, call);
            requests.add(ChatMessage.builder(ChatRole.ASSISTANT)
                    .addContent(new ToolApprovalRequestContent(requestId, call))
                    .build());
        }
        String batchId = "approval_batch_" + java.util.UUID.randomUUID();
        List<Tool> runtimeTools = tools.getTools();
        List<String> toolNames = new ArrayList<>(runtimeTools.size());
        runtimeTools.forEach(tool -> toolNames.add(tool.getName()));
        ToolApprovalBatch batch = new ToolApprovalBatch(
                batchId,
                scope,
                callsByRequestId,
                resumeConversation,
                toolNames,
                clock.instant());
        return approvalStore.create(batch)
                .handle((ignored, error) -> {
                    if (error != null) {
                        throw approvalFailure(error);
                    }
                    runtimeToolsByBatchId.put(batchId, runtimeTools);
                    callsByRequestId.keySet().forEach(requestId ->
                            batchIdsByRequestId.put(requestId, batchId));
                    return new ApprovalRequestBatch(
                            requests,
                            callsByRequestId.keySet());
                });
    }

    private static Set<String> approvalResponseIds(
            List<ToolApprovalResponseContent> responses) {
        Set<String> responseIds = new HashSet<>();
        for (ToolApprovalResponseContent response : responses) {
            if (!responseIds.add(response.getRequestId())) {
                throw new ToolInvocationException(
                        "Duplicate tool approval response: "
                                + response.getRequestId());
            }
        }
        return responseIds;
    }

    private void abandonApprovalRequests(Set<String> requestIds) {
        if (requestIds.isEmpty()) {
            return;
        }
        approvalStore.abandon(requestIds).whenComplete((ignored, error) -> {
            if (error == null) {
                requestIds.forEach(requestId -> {
                    String batchId = batchIdsByRequestId.remove(requestId);
                    if (batchId != null) {
                        runtimeToolsByBatchId.remove(batchId);
                    }
                });
            }
        });
    }

    private static String approvalScope(
            String conversationId,
            String continuationToken,
            ChatOptions options) {
        Object session = options.getAdditionalProperties().get(
                RunContextProperties.AGENT_SESSION);
        Object agent = options.getAdditionalProperties().get(
                RunContextProperties.AGENT);
        if (session instanceof io.github.weidongxu.agentframework.agent.AgentSession) {
            String agentId = agent instanceof io.github.weidongxu.agentframework.agent.Agent
                    ? ((io.github.weidongxu.agentframework.agent.Agent) agent).getId()
                    : "unknown";
            return "agent:" + agentId + "/session:"
                    + ((io.github.weidongxu.agentframework.agent.AgentSession) session)
                            .getId();
        }
        if (conversationId != null) {
            return "conversation:" + conversationId;
        }
        if (continuationToken != null) {
            return "continuation:" + continuationToken;
        }
        if (options.getConversationId() != null) {
            return "conversation:" + options.getConversationId();
        }
        if (options.getContinuationToken() != null) {
            return "continuation:" + options.getContinuationToken();
        }
        return null;
    }

    private void removeRuntimeApproval(ToolApprovalBatch batch) {
        runtimeToolsByBatchId.remove(batch.getId());
        batch.getCallsByRequestId().keySet().forEach(
                batchIdsByRequestId::remove);
    }

    private static ToolInvocationException approvalFailure(Throwable error) {
        Throwable cause = unwrapCompletionError(error);
        return cause instanceof ToolInvocationException
                ? (ToolInvocationException) cause
                : new ToolInvocationException(cause.getMessage(), cause);
    }

    private CompletionStage<ChatMessage> invokeTool(
            FunctionCallContent call,
            Tool tool,
            LiveToolRegistry progressiveTools,
            ChatOptions options) {
        if (tool == null) {
            return failedFuture(new ToolInvocationException(
                    "Model requested unknown tool: " + call.getName()));
        }

        Map<String, Object> arguments;
        try {
            arguments = parseArguments(call);
        } catch (JsonProcessingException error) {
            return failedFuture(new ToolInvocationException(
                    "Invalid JSON arguments for tool " + call.getName(), error));
        }

        CompletionStage<String> invocation;
        try {
            FunctionInvocationContext context =
                    new FunctionInvocationContext(
                            tool,
                            call,
                            arguments,
                            options,
                            progressiveTools);
            String callId = PlatformRequestHeaders.outboundCallId(options);
            invocation = Objects.requireNonNull(
                    PlatformCallContext.callWith(
                            callId, () -> invokeFunctionMiddleware(context, 0)),
                    "Function middleware returned null CompletionStage");
        } catch (Throwable error) {
            return failedFuture(new ToolInvocationException(
                    "Tool invocation failed: " + tool.getName(), error));
        }

        return invocation.handle((result, error) -> {
            if (error != null) {
                throw new ToolInvocationException(
                        "Tool invocation failed: " + tool.getName(),
                        unwrapCompletionError(error));
            }
            if (result == null) {
                throw new ToolInvocationException(
                        "Tool invocation returned null result: " + tool.getName());
            }
            return ChatMessage.builder(ChatRole.TOOL)
                    .authorName(tool.getName())
                    .addContent(new FunctionResultContent(call.getCallId(), result, false))
                    .build();
        });
    }

    private static ToolContext toolContext(ChatOptions options) {
        Object session = options.getAdditionalProperties()
                .get(RunContextProperties.AGENT_SESSION);
        return new ToolContext(
                session instanceof AgentSession ? (AgentSession) session : null);
    }

    private CompletionStage<String> invokeFunctionMiddleware(
            FunctionInvocationContext context,
            int index) {
        if (index == functionMiddleware.size()) {
            ToolContext toolContext = toolContext(context.getOptions());
            return context.getTool().invoke(context.getArguments(), toolContext);
        }
        FunctionMiddleware current = functionMiddleware.get(index);
        AtomicBoolean called = new AtomicBoolean();
        FunctionMiddlewareNext next = nextContext -> {
            if (!called.compareAndSet(false, true)) {
                return failedFuture(
                        new IllegalStateException(
                                "Function middleware called next more than once"));
            }
            return invokeFunctionMiddleware(nextContext, index + 1);
        };
        try {
            return Objects.requireNonNull(
                    current.invoke(context, next),
                    "Function middleware returned null CompletionStage");
        } catch (Throwable error) {
            return failedFuture(error);
        }
    }

    private Map<String, Object> parseArguments(FunctionCallContent call)
            throws JsonProcessingException {
        if (call.getArguments().trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> arguments =
                objectMapper.readValue(call.getArguments(), ARGUMENTS_TYPE);
        return arguments == null ? Collections.emptyMap() : arguments;
    }

    private static List<FunctionCallContent> functionCalls(List<ChatMessage> messages) {
        List<FunctionCallContent> calls = new ArrayList<>();
        for (ChatMessage message : messages) {
            for (ChatContent content : message.getContents()) {
                if (content instanceof FunctionCallContent) {
                    calls.add((FunctionCallContent) content);
                }
            }
        }
        return calls;
    }

    private static List<ToolApprovalResponseContent> approvalResponses(
            List<ChatMessage> messages) {
        List<ToolApprovalResponseContent> responses = new ArrayList<>();
        for (ChatMessage message : messages) {
            for (ChatContent content : message.getContents()) {
                if (content instanceof ToolApprovalResponseContent) {
                    responses.add((ToolApprovalResponseContent) content);
                }
            }
        }
        return responses;
    }

    private static List<ChatMessage> withoutApprovalContent(
            List<ChatMessage> messages,
            boolean allowEmpty) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            ChatMessage.Builder copy = ChatMessage.builder(message.getRole());
            if (message.getAuthorName() != null) {
                copy.authorName(message.getAuthorName());
            }
            message.getAdditionalProperties().forEach(copy::additionalProperty);
            int contentCount = 0;
            for (ChatContent content : message.getContents()) {
                if (!(content instanceof ToolApprovalRequestContent)
                        && !(content instanceof ToolApprovalResponseContent)) {
                    copy.addContent(content);
                    contentCount++;
                }
            }
            if (contentCount > 0) {
                result.add(copy.build());
            }
        }
        if (result.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException(
                    "messages must contain content other than tool approvals");
        }
        return result;
    }

    private static List<ChatMessage> restoreConversation(
            List<ChatMessage> saved,
            List<ChatMessage> supplied) {
        return saved == null ? supplied : new ArrayList<>(saved);
    }

    private static List<ChatMessage> appendMessages(
            List<ChatMessage> first,
            List<ChatMessage> second) {
        List<ChatMessage> result =
                new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static List<ChatMessage> mutableMessages(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        return new ArrayList<>(messages);
    }

    private static ChatResponse copyWithMessages(
            ChatResponse response,
            List<ChatMessage> messages) {
        ChatResponse.Builder result = ChatResponse.builder()
                .messages(messages)
                .responseId(response.getResponseId())
                .conversationId(response.getConversationId())
                .continuationToken(response.getContinuationToken())
                .finishReason(response.getFinishReason())
                .usage(response.getUsage())
                .rawRepresentation(response.getRawRepresentation());
        response.getAdditionalProperties().forEach(result::additionalProperty);
        return result.build();
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
            next.continuationToken(continuationToken);
        }
        return next.build();
    }

    private static ChatOptions withTools(
            ChatOptions options,
            List<? extends Tool> tools) {
        return options.toBuilder()
                .clearTools()
                .tools(tools)
                .build();
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

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static final class LiveToolRegistry
            implements ProgressiveToolRegistry {
        private final Map<String, Tool> tools = new LinkedHashMap<>();

        private LiveToolRegistry(List<? extends Tool> initialTools) {
            addTools(initialTools);
        }

        @Override
        public synchronized List<Tool> getTools() {
            return Collections.unmodifiableList(
                    new ArrayList<>(tools.values()));
        }

        @Override
        public synchronized void addTools(List<? extends Tool> additions) {
            Objects.requireNonNull(additions, "tools");
            Map<String, Tool> updated = new LinkedHashMap<>(tools);
            for (Tool tool : additions) {
                Tool addition = Objects.requireNonNull(tool, "tool");
                Tool existing = updated.get(addition.getName());
                if (existing == addition) {
                    continue;
                }
                if (existing != null) {
                    throw new IllegalArgumentException(
                            "A different tool already uses name: "
                                    + addition.getName());
                }
                updated.put(addition.getName(), addition);
            }
            tools.clear();
            tools.putAll(updated);
        }

        @Override
        public synchronized void removeTools(List<String> toolNames) {
            Objects.requireNonNull(toolNames, "toolNames");
            List<String> validated = new ArrayList<>(toolNames.size());
            for (String toolName : toolNames) {
                validated.add(Objects.requireNonNull(
                        toolName,
                        "toolName"));
            }
            validated.forEach(tools::remove);
        }

        private synchronized Tool get(String name) {
            return tools.get(name);
        }

        private synchronized Map<String, Tool> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        }
    }

    private static final class ApprovalResolution {
        private final List<ChatMessage> conversation;
        private final List<ChatMessage> results;
        private final LiveToolRegistry tools;

        private ApprovalResolution(
                List<ChatMessage> conversation,
                List<ChatMessage> results,
                LiveToolRegistry tools) {
            this.conversation = conversation;
            this.results = results;
            this.tools = tools;
        }
    }

    private static final class ApprovalRequestBatch {
        private final List<ChatMessage> messages;
        private final Set<String> requestIds;

        private ApprovalRequestBatch(
                List<ChatMessage> messages,
                Set<String> requestIds) {
            this.messages = Collections.unmodifiableList(
                    new ArrayList<>(messages));
            this.requestIds = Collections.unmodifiableSet(
                    new HashSet<>(requestIds));
        }
    }

    private final class StreamingToolLoop
            implements Flow.Subscription, Flow.Subscriber<ChatResponseUpdate> {
        private final Flow.Subscriber<? super ChatResponseUpdate> downstream;
        private final List<ChatMessage> conversation;
        private ChatOptions options;
        private LiveToolRegistry tools;
        private final List<ChatContent> turnContents = new ArrayList<>();
        private final List<ChatResponseUpdate> terminalUpdates = new ArrayList<>();
        private final Set<String> undeliveredApprovalRequests = new HashSet<>();
        private final Object signalLock = new Object();
        private Flow.Subscription upstream;
        private long demand;
        private int iteration;
        private boolean started;
        private boolean done;
        private boolean completeAfterTerminalUpdates;
        private String turnConversationId;
        private String turnContinuationToken;

        private StreamingToolLoop(
                Flow.Subscriber<? super ChatResponseUpdate> downstream,
                List<ChatMessage> conversation,
                ChatOptions options,
                LiveToolRegistry tools) {
            this.downstream = downstream;
            this.conversation = conversation;
            this.options = options;
            this.tools = tools;
        }

        private synchronized void start() {
            if (!started && !done) {
                started = true;
                resolveApprovalResponses(conversation, tools, options)
                        .whenComplete((resolution, error) -> {
                            if (error != null) {
                                fail(unwrapCompletionError(error));
                                return;
                            }
                            synchronized (StreamingToolLoop.this) {
                                if (done) {
                                    return;
                                }
                                conversation.clear();
                                conversation.addAll(resolution.conversation);
                                tools = resolution.tools;
                                options = withTools(
                                        options,
                                        tools.getTools());
                                subscribeTurn();
                            }
                        });
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
            } else {
                drainTerminalUpdates();
                if (current != null) {
                    current.request(count);
                }
            }
        }

        @Override
        public void cancel() {
            Set<String> abandoned;
            Flow.Subscription current;
            synchronized (signalLock) {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    done = true;
                    current = upstream;
                    upstream = null;
                    abandoned = new HashSet<>(undeliveredApprovalRequests);
                    undeliveredApprovalRequests.clear();
                }
            }
            if (current != null) {
                current.cancel();
            }
            abandonApprovalRequests(abandoned);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            long currentDemand;
            boolean reject;
            boolean duplicate;
            synchronized (this) {
                reject = done;
                duplicate = !done && upstream != null;
                if (!reject && !duplicate) {
                    upstream = subscription;
                }
                currentDemand = demand;
            }
            if (reject) {
                subscription.cancel();
                return;
            }
            if (duplicate) {
                subscription.cancel();
                fail(new IllegalStateException("Upstream subscribed more than once"));
                return;
            }
            if (currentDemand > 0) {
                subscription.request(currentDemand);
            }
        }

        @Override
        public void onNext(ChatResponseUpdate update) {
            boolean suppressTerminal;
            synchronized (this) {
                if (done) {
                    return;
                }
                suppressTerminal = update.getContents().isEmpty()
                        && update.getFinishReason()
                        == io.github.weidongxu.agentframework.chat.FinishReason.TOOL_CALLS
                        && !functionCallsFromContents(turnContents).isEmpty();
                if (!suppressTerminal && demand == 0) {
                    fail(new IllegalStateException("Upstream emitted without demand"));
                    return;
                }
                if (!suppressTerminal && demand != Long.MAX_VALUE) {
                    demand--;
                }
                turnContents.addAll(update.getContents());
                if (update.getConversationId() != null) {
                    turnConversationId = update.getConversationId();
                }
                if (update.getContinuationToken() != null) {
                    turnContinuationToken = update.getContinuationToken();
                }
            }
            if (!suppressTerminal) {
                emitNext(update);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable);
        }

        @Override
        public void onComplete() {
            List<FunctionCallContent> calls;
            Throwable failure = null;
            boolean complete = false;
            ChatMessage assistantMessage = null;
            boolean serverManaged = false;
            String conversationId = null;
            String continuationToken = null;
            synchronized (this) {
                if (done) {
                    return;
                }
                upstream = null;
                calls = functionCallsFromContents(turnContents);
                if (calls.isEmpty()) {
                    done = true;
                    complete = true;
                } else if (iteration >= maxIterations) {
                    failure = new ToolInvocationException(
                            "Tool invocation exceeded " + maxIterations + " iterations");
                } else {
                    assistantMessage = ChatMessage.builder(ChatRole.ASSISTANT)
                            .contents(turnContents)
                            .build();
                    conversationId = turnConversationId;
                    continuationToken = turnContinuationToken;
                    serverManaged = conversationId != null
                            || continuationToken != null
                            || options.getConversationId() != null
                            || options.getContinuationToken() != null
                            || Boolean.TRUE.equals(
                                    options.getAdditionalProperties().get(
                                            RunContextProperties
                                                    .PER_SERVICE_CALL_HISTORY));
                    turnContents.clear();
                    turnConversationId = null;
                    turnContinuationToken = null;
                    iteration++;
                }
            }
            if (complete) {
                emitComplete();
                return;
            }
            if (failure != null) {
                fail(failure);
                return;
            }

            final ChatMessage completedAssistantMessage = assistantMessage;
            final boolean useServerManagedState = serverManaged;
            final String nextConversationId = conversationId;
            final String nextContinuationToken = continuationToken;
            try {
                if (requiresApproval(calls, tools)) {
                    approvalRequests(
                                calls,
                                approvalScope(
                                        nextConversationId,
                                        nextContinuationToken,
                                        options),
                                useServerManagedState
                                        ? null
                                        : appendMessages(
                                                conversation,
                                                Collections.singletonList(
                                                        completedAssistantMessage)),
                                tools).whenComplete((requests, error) -> {
                                    if (error != null) {
                                        fail(unwrapCompletionError(error));
                                        return;
                                    }
                                    synchronized (signalLock) {
                                        synchronized (this) {
                                            if (done) {
                                                abandonApprovalRequests(
                                                       requests.requestIds);
                                                return;
                                            }
                                            for (ChatMessage request
                                                   : requests.messages) {
                                                ToolApprovalRequestContent approval =
                                                       (ToolApprovalRequestContent)
                                                               request.getContents().get(0);
                                                undeliveredApprovalRequests.add(
                                                       approval.getRequestId());
                                                terminalUpdates.add(
                                                       ChatResponseUpdate.builder()
                                                               .role(ChatRole.ASSISTANT)
                                                               .contents(request.getContents())
                                                               .conversationId(
                                                                       nextConversationId)
                                                               .continuationToken(
                                                                       nextContinuationToken)
                                                               .finishReason(
                                                                       io.github.weidongxu.agentframework
                                                                               .chat.FinishReason
                                                                               .TOOL_CALLS)
                                                               .build());
                                            }
                                            completeAfterTerminalUpdates = true;
                                        }
                                    }
                                    drainTerminalUpdates();
                                });
                    return;
                }
            } catch (Throwable error) {
                fail(error);
                return;
            }
            invokeTools(calls, tools, options).whenComplete((results, error) -> {
                if (error != null) {
                    fail(unwrapCompletionError(error));
                    return;
                }
                synchronized (StreamingToolLoop.this) {
                    if (done) {
                        return;
                    }
                    if (useServerManagedState) {
                        conversation.clear();
                        conversation.addAll(results);
                        options = advanceOptions(
                                withTools(options, tools.getTools()),
                                nextConversationId,
                                nextContinuationToken);
                    } else {
                        conversation.add(completedAssistantMessage);
                        conversation.addAll(results);
                        options = withTools(options, tools.getTools());
                    }
                    subscribeTurn();
                }
            });
        }

        private void subscribeTurn() {
            getDelegate().getStreamingResponse(
                            Collections.unmodifiableList(new ArrayList<>(conversation)),
                            options)
                    .subscribe(this);
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
            emitError(error);
        }

        private void emitNext(ChatResponseUpdate update) {
            synchronized (signalLock) {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                }
                downstream.onNext(update);
            }
        }

        private void drainTerminalUpdates() {
            while (true) {
                synchronized (signalLock) {
                    ChatResponseUpdate update;
                    boolean complete;
                    synchronized (this) {
                        if (done) {
                            return;
                        }
                        if (!terminalUpdates.isEmpty() && demand > 0) {
                            update = terminalUpdates.remove(0);
                            if (demand != Long.MAX_VALUE) {
                                demand--;
                            }
                            complete = false;
                        } else {
                            update = null;
                            complete = terminalUpdates.isEmpty()
                                    && completeAfterTerminalUpdates;
                            if (complete) {
                                done = true;
                            }
                        }
                    }
                    if (update != null) {
                        downstream.onNext(update);
                        for (ChatContent content : update.getContents()) {
                            if (content instanceof ToolApprovalRequestContent) {
                                synchronized (this) {
                                    undeliveredApprovalRequests.remove(
                                            ((ToolApprovalRequestContent) content)
                                                    .getRequestId());
                                }
                            }
                        }
                    } else {
                        if (complete) {
                            downstream.onComplete();
                        }
                        return;
                    }
                }
            }
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

        private List<FunctionCallContent> functionCallsFromContents(
                List<ChatContent> contents) {
            List<FunctionCallContent> calls = new ArrayList<>();
            for (ChatContent content : contents) {
                if (content instanceof FunctionCallContent) {
                    calls.add((FunctionCallContent) content);
                }
            }
            return calls;
        }
    }

    private static long addCap(long current, long increment) {
        long result = current + increment;
        return result < 0 ? Long.MAX_VALUE : result;
    }
}
