package io.github.weidongxu.agentframework.openai;

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
import io.github.weidongxu.agentframework.chat.PlatformRequestHeaders;
import io.github.weidongxu.agentframework.chat.ResponseFormat;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.chat.Usage;
import io.github.weidongxu.agentframework.tool.HostedCodeInterpreterTool;
import io.github.weidongxu.agentframework.tool.HostedFileSearchTool;
import io.github.weidongxu.agentframework.tool.HostedImageGenerationTool;
import io.github.weidongxu.agentframework.tool.HostedMcpTool;
import io.github.weidongxu.agentframework.tool.HostedTool;
import io.github.weidongxu.agentframework.tool.HostedWebSearchTool;
import io.github.weidongxu.agentframework.tool.Tool;
import io.github.weidongxu.agentframework.tool.ApprovalMode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FileSearchTool;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.WebSearchTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.services.blocking.ResponseService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

public final class OpenAIResponsesChatClient implements ChatClient {
    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper();

    private final OpenAIResponseTransport transport;
    private final Executor streamingExecutor;

    public OpenAIResponsesChatClient(OpenAIClient client, Executor streamingExecutor) {
        this(new SdkOpenAIResponseTransport(client), streamingExecutor);
    }

    public OpenAIResponsesChatClient(
            ResponseService responseService,
            Executor requestExecutor,
            Executor streamingExecutor) {
        this(
                new BlockingOpenAIResponseTransport(responseService, requestExecutor),
                streamingExecutor);
    }

    OpenAIResponsesChatClient(
            OpenAIResponseTransport transport,
            Executor streamingExecutor) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.streamingExecutor = Objects.requireNonNull(streamingExecutor, "streamingExecutor");
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        ResponseCreateParams params = createParams(messages, options);
        return transport.create(params).thenApply(OpenAIResponsesChatClient::mapResponse);
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        ResponseCreateParams params = createParams(messages, options);
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            StreamingSubscription subscription =
                    new StreamingSubscription(subscriber, params, transport, streamingExecutor);
            subscriber.onSubscribe(subscription);
        };
    }

    static ResponseCreateParams createParams(
            List<ChatMessage> messages,
            ChatOptions options) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(options, "options");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        if (options.getModelId() == null || options.getModelId().trim().isEmpty()) {
            throw new IllegalArgumentException("OpenAI Responses requires a modelId");
        }
        if (options.getConversationId() != null && options.getContinuationToken() != null) {
            throw new IllegalArgumentException(
                    "conversationId and continuationToken cannot both be set");
        }

        List<ResponseInputItem> input = new ArrayList<>();
        for (ChatMessage message : messages) {
            mapMessage(message, input);
        }
        if (input.isEmpty()) {
            throw new IllegalArgumentException("messages contain no OpenAI-compatible content");
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(options.getModelId())
                .inputOfResponse(input);
        if (options.getInstructions() != null) {
            builder.instructions(options.getInstructions());
        }
        if (options.getConversationId() != null) {
            builder.conversation(options.getConversationId());
        } else if (options.getContinuationToken() != null) {
            builder.previousResponseId(options.getContinuationToken());
        }
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getMaxOutputTokens() != null) {
            builder.maxOutputTokens(options.getMaxOutputTokens().longValue());
        }
        if (options.getResponseFormat() != null) {
            builder.text(mapResponseFormat(options.getResponseFormat()));
        }
        for (Tool tool : options.getTools()) {
            builder.addTool(mapTool(tool));
        }
        String callId = PlatformRequestHeaders.outboundCallId(options);
        if (callId != null) {
            builder.putAdditionalHeader(PlatformRequestHeaders.CALL_ID, callId);
        }
        return builder.build();
    }

    private static void mapMessage(ChatMessage message, List<ResponseInputItem> input) {
        Objects.requireNonNull(message, "message");
        StringBuilder text = new StringBuilder();
        for (ChatContent content : message.getContents()) {
            if (content instanceof TextContent) {
                text.append(((TextContent) content).getText());
            } else if (content instanceof FunctionCallContent) {
                FunctionCallContent call = (FunctionCallContent) content;
                input.add(ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .callId(call.getCallId())
                                .name(call.getName())
                                .arguments(call.getArguments())
                                .build()));
            } else if (content instanceof FunctionResultContent) {
                FunctionResultContent result = (FunctionResultContent) content;
                input.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                .callId(result.getCallId())
                                .output(result.getResult())
                                .build()));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported OpenAI chat content: " + content.getClass().getName());
            }
        }
        if (text.length() > 0) {
            input.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                            .role(mapRole(message.getRole()))
                            .content(text.toString())
                            .build()));
        }
    }

    private static EasyInputMessage.Role mapRole(ChatRole role) {
        switch (role) {
            case SYSTEM:
                return EasyInputMessage.Role.SYSTEM;
            case DEVELOPER:
                return EasyInputMessage.Role.DEVELOPER;
            case USER:
                return EasyInputMessage.Role.USER;
            case ASSISTANT:
                return EasyInputMessage.Role.ASSISTANT;
            case TOOL:
                throw new IllegalArgumentException(
                        "Tool messages must contain FunctionResultContent");
            default:
                throw new IllegalArgumentException("Unsupported chat role: " + role);
        }
    }

    private static com.openai.models.responses.Tool mapTool(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        if (tool instanceof HostedTool) {
            return mapHostedTool((HostedTool) tool);
        }
        FunctionTool.Parameters parameters = JSON_MAPPER.convertValue(
                tool.getParametersSchema(),
                FunctionTool.Parameters.class);
        return com.openai.models.responses.Tool.ofFunction(FunctionTool.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .parameters(parameters)
                .strict(false)
                .build());
    }

    private static com.openai.models.responses.Tool mapHostedTool(HostedTool tool) {
        if (tool instanceof HostedWebSearchTool) {
            HostedWebSearchTool webSearch = (HostedWebSearchTool) tool;
            WebSearchTool.Builder builder = WebSearchTool.builder()
                    .type(WebSearchTool.Type.WEB_SEARCH);
            if (webSearch.getSearchContextSize() != null) {
                builder.searchContextSize(WebSearchTool.SearchContextSize.of(
                        webSearch.getSearchContextSize().name().toLowerCase(Locale.ROOT)));
            }
            return com.openai.models.responses.Tool.ofWebSearch(builder.build());
        }
        if (tool instanceof HostedCodeInterpreterTool) {
            HostedCodeInterpreterTool codeInterpreter = (HostedCodeInterpreterTool) tool;
            com.openai.models.responses.Tool.CodeInterpreter.Container container =
                    codeInterpreter.getContainerId() != null
                            ? com.openai.models.responses.Tool.CodeInterpreter.Container.ofString(
                                    codeInterpreter.getContainerId())
                            : com.openai.models.responses.Tool.CodeInterpreter.Container.ofCodeInterpreterToolAuto(
                                    com.openai.models.responses.Tool.CodeInterpreter.Container
                                            .CodeInterpreterToolAuto.builder().build());
            return com.openai.models.responses.Tool.ofCodeInterpreter(
                    com.openai.models.responses.Tool.CodeInterpreter.builder()
                            .container(container)
                            .build());
        }
        if (tool instanceof HostedFileSearchTool) {
            HostedFileSearchTool fileSearch = (HostedFileSearchTool) tool;
            FileSearchTool.Builder builder = FileSearchTool.builder()
                    .vectorStoreIds(fileSearch.getVectorStoreIds());
            if (fileSearch.getMaxResults() != null) {
                builder.maxNumResults(fileSearch.getMaxResults().longValue());
            }
            return com.openai.models.responses.Tool.ofFileSearch(builder.build());
        }
        if (tool instanceof HostedMcpTool) {
            HostedMcpTool mcp = (HostedMcpTool) tool;
            com.openai.models.responses.Tool.Mcp.Builder builder =
                    com.openai.models.responses.Tool.Mcp.builder()
                            .serverLabel(mcp.getServerLabel())
                            .serverUrl(mcp.getServerUrl())
                            .requireApproval(mapMcpApproval(mcp.getApprovalMode()));
            if (!mcp.getAllowedTools().isEmpty()) {
                builder.allowedToolsOfMcp(mcp.getAllowedTools());
            }
            return com.openai.models.responses.Tool.ofMcp(builder.build());
        }
        if (tool instanceof HostedImageGenerationTool) {
            HostedImageGenerationTool imageGeneration = (HostedImageGenerationTool) tool;
            com.openai.models.responses.Tool.ImageGeneration.Builder builder =
                    com.openai.models.responses.Tool.ImageGeneration.builder();
            if (imageGeneration.getModel() != null) {
                builder.model(imageGeneration.getModel());
            }
            return com.openai.models.responses.Tool.ofImageGeneration(builder.build());
        }
        throw new IllegalArgumentException(
                "Unsupported hosted tool: " + tool.getClass().getName());
    }

    private static com.openai.models.responses.Tool.Mcp.RequireApproval.McpToolApprovalSetting
            mapMcpApproval(ApprovalMode approvalMode) {
        return approvalMode == ApprovalMode.ALWAYS_REQUIRE
                ? com.openai.models.responses.Tool.Mcp.RequireApproval.McpToolApprovalSetting.ALWAYS
                : com.openai.models.responses.Tool.Mcp.RequireApproval.McpToolApprovalSetting.NEVER;
    }

    private static ResponseTextConfig mapResponseFormat(ResponseFormat responseFormat) {
        if (responseFormat.getType() == ResponseFormat.Type.TEXT) {
            return JSON_MAPPER.convertValue(Collections.emptyMap(), ResponseTextConfig.class);
        }
        Map<String, Object> format = new LinkedHashMap<>();
        if (responseFormat.getType() == ResponseFormat.Type.JSON_OBJECT) {
            format.put("type", "json_object");
        } else {
            format.put("type", "json_schema");
            format.put("name", responseFormat.getName());
            format.put("schema", responseFormat.getSchema());
            format.put("strict", false);
        }
        return JSON_MAPPER.convertValue(
                Collections.singletonMap("format", format),
                ResponseTextConfig.class);
    }

    static ChatResponse mapResponse(Response response) {
        response.error().ifPresent(error -> {
            throw new OpenAIAdapterException("OpenAI response failed: " + error);
        });
        ChatResponse.Builder result = ChatResponse.builder()
                .messages(mapOutput(response.output()))
                .responseId(response.id())
                .continuationToken(response.id())
                .finishReason(finishReason(response))
                .rawRepresentation(response);
        response.conversation().ifPresent(conversation -> result.conversationId(conversation.id()));
        response.usage().ifPresent(usage -> result.usage(mapUsage(usage)));
        response.status().ifPresent(status -> result.additionalProperty(
                "openai.status",
                status.toString()));
        return result.build();
    }

    private static List<ChatMessage> mapOutput(List<ResponseOutputItem> output) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ResponseOutputItem item : output) {
            if (item.isMessage()) {
                messages.add(mapMessage(item.asMessage()));
            } else if (item.isFunctionCall()) {
                messages.add(ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(mapFunctionCall(item.asFunctionCall()))
                        .build());
            }
        }
        return messages;
    }

    private static ChatMessage mapMessage(ResponseOutputMessage message) {
        ChatMessage.Builder result = ChatMessage.builder(ChatRole.ASSISTANT);
        for (ResponseOutputMessage.Content content : message.content()) {
            if (content.isOutputText()) {
                result.text(content.asOutputText().text());
            } else if (content.isRefusal()) {
                result.text(content.asRefusal().refusal());
            }
        }
        return result.additionalProperty("openai.messageId", message.id()).build();
    }

    private static FunctionCallContent mapFunctionCall(ResponseFunctionToolCall call) {
        return new FunctionCallContent(call.callId(), call.name(), call.arguments());
    }

    private static Usage mapUsage(ResponseUsage usage) {
        return new Usage(usage.inputTokens(), usage.outputTokens());
    }

    private static FinishReason finishReason(Response response) {
        for (ResponseOutputItem item : response.output()) {
            if (item.isFunctionCall()) {
                return FinishReason.TOOL_CALLS;
            }
        }
        if (response.error().isPresent()) {
            return FinishReason.OTHER;
        }
        if (response.incompleteDetails().isPresent()) {
            return FinishReason.LENGTH;
        }
        return FinishReason.STOP;
    }

    private static ChatResponseUpdate mapEvent(ResponseStreamEvent event) {
        if (event.isOutputTextDelta()) {
            return ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .messageId(event.asOutputTextDelta().itemId())
                    .text(event.asOutputTextDelta().delta())
                    .rawRepresentation(event)
                    .build();
        }
        if (event.isRefusalDelta()) {
            return ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .messageId(event.asRefusalDelta().itemId())
                    .text(event.asRefusalDelta().delta())
                    .finishReason(FinishReason.CONTENT_FILTER)
                    .rawRepresentation(event)
                    .build();
        }
        if (event.isOutputItemDone()) {
            ResponseOutputItem item = event.asOutputItemDone().item();
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                return ChatResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .messageId(call.id().orElse(null))
                        .content(mapFunctionCall(call))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .rawRepresentation(event)
                        .build();
            }
            return ChatResponseUpdate.builder()
                    .rawRepresentation(event)
                    .build();
        }
        if (event.isCompleted()) {
            return terminalUpdate(event.asCompleted().response(), event, null);
        }
        if (event.isIncomplete()) {
            return terminalUpdate(
                    event.asIncomplete().response(),
                    event,
                    FinishReason.LENGTH);
        }
        if (event.isFailed()) {
            return terminalUpdate(
                    event.asFailed().response(),
                    event,
                    FinishReason.OTHER);
        }
        return ChatResponseUpdate.builder()
                .rawRepresentation(event)
                .build();
    }

    private static ChatResponseUpdate terminalUpdate(
            Response response,
            ResponseStreamEvent event,
            FinishReason overrideFinishReason) {
        ChatResponseUpdate.Builder update = ChatResponseUpdate.builder()
                .role(ChatRole.ASSISTANT)
                .responseId(response.id())
                .continuationToken(response.id())
                .finishReason(overrideFinishReason == null
                        ? finishReason(response)
                        : overrideFinishReason)
                .rawRepresentation(event);
        response.conversation().ifPresent(conversation ->
                update.conversationId(conversation.id()));
        response.usage().ifPresent(usage ->
                update.additionalProperty("usage", mapUsage(usage)));
        return update.build();
    }

    private static final class StreamingSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super ChatResponseUpdate> downstream;
        private final ResponseCreateParams params;
        private final OpenAIResponseTransport transport;
        private final Executor executor;

        private StreamResponse<ResponseStreamEvent> response;
        private Stream<ResponseStreamEvent> stream;
        private Iterator<ResponseStreamEvent> iterator;
        private long demand;
        private boolean draining;
        private boolean done;

        private StreamingSubscription(
                Flow.Subscriber<? super ChatResponseUpdate> downstream,
                ResponseCreateParams params,
                OpenAIResponseTransport transport,
                Executor executor) {
            this.downstream = downstream;
            this.params = params;
            this.transport = transport;
            this.executor = executor;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Flow demand must be positive"));
                return;
            }
            synchronized (this) {
                if (done) {
                    return;
                }
                demand = addCap(demand, count);
            }
            schedule();
        }

        @Override
        public void cancel() {
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
            }
            closeResources();
        }

        private void schedule() {
            synchronized (this) {
                if (done || draining || demand == 0) {
                    return;
                }
                draining = true;
            }
            try {
                executor.execute(this::drain);
            } catch (Throwable error) {
                synchronized (this) {
                    draining = false;
                }
                fail(error);
            }
        }

        private void drain() {
            try {
                ensureOpen();
                while (true) {
                    synchronized (this) {
                        if (done || demand == 0) {
                            draining = false;
                            return;
                        }
                    }
                    if (!iterator.hasNext()) {
                        fail(new OpenAIAdapterException(
                                "OpenAI response stream ended before a terminal event"));
                        return;
                    }
                    ResponseStreamEvent event = iterator.next();
                    ChatResponseUpdate update = mapEvent(event);
                    if (update == null) {
                        continue;
                    }
                    synchronized (this) {
                        if (done) {
                            draining = false;
                            return;
                        }
                        if (demand != Long.MAX_VALUE) {
                            demand--;
                        }
                    }
                    downstream.onNext(update);
                    if (event.isCompleted()
                            || event.isIncomplete()
                            || event.isFailed()) {
                        complete();
                        return;
                    }
                }
            } catch (Throwable error) {
                fail(error);
            }
        }

        private void ensureOpen() {
            synchronized (this) {
                if (iterator != null || done) {
                    return;
                }
            }
            StreamResponse<ResponseStreamEvent> openedResponse =
                    transport.createStreaming(params);
            Stream<ResponseStreamEvent> openedStream = openedResponse.stream();
            synchronized (this) {
                if (done) {
                    openedStream.close();
                    openedResponse.close();
                    return;
                }
                response = openedResponse;
                stream = openedStream;
                iterator = openedStream.iterator();
            }
        }

        private void complete() {
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                draining = false;
            }
            closeResources();
            downstream.onComplete();
        }

        private void fail(Throwable error) {
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                draining = false;
            }
            closeResources();
            downstream.onError(error);
        }

        private void closeResources() {
            Stream<ResponseStreamEvent> currentStream;
            StreamResponse<ResponseStreamEvent> currentResponse;
            synchronized (this) {
                currentStream = stream;
                currentResponse = response;
                stream = null;
                response = null;
                iterator = null;
            }
            if (currentStream != null) {
                currentStream.close();
            }
            if (currentResponse != null) {
                currentResponse.close();
            }
        }

        private static long addCap(long current, long increment) {
            long total = current + increment;
            return total < 0 ? Long.MAX_VALUE : total;
        }
    }
}
