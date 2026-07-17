package io.github.weidongxu.agentframework.langchain4j;

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
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.chat.Usage;
import io.github.weidongxu.agentframework.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

public final class LangChain4jChatClient implements ChatClient {
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final Executor executor;

    public LangChain4jChatClient(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            Executor executor) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.streamingChatModel = Objects.requireNonNull(
                streamingChatModel,
                "streamingChatModel");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<ChatResponse> getResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        ChatRequest request = request(messages, options);
        return CompletableFuture.supplyAsync(
                () -> map(chatModel.chat(request)),
                executor);
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> getStreamingResponse(
            List<ChatMessage> messages,
            ChatOptions options) {
        ChatRequest request = request(messages, options);
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            StreamingSubscription subscription =
                    new StreamingSubscription(subscriber, request);
            subscriber.onSubscribe(subscription);
            subscription.start();
        };
    }

    ChatRequest request(
            List<ChatMessage> messages,
            ChatOptions options) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(options, "options");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        if (options.getConversationId() != null
                || options.getContinuationToken() != null) {
            throw new IllegalArgumentException(
                    "LangChain4j models do not support framework continuation state");
        }
        List<dev.langchain4j.data.message.ChatMessage> mapped =
                new ArrayList<>();
        if (options.getInstructions() != null
                && !options.getInstructions().isBlank()) {
            mapped.add(SystemMessage.from(options.getInstructions()));
        }
        for (ChatMessage message : messages) {
            mapped.add(map(message));
        }
        ChatRequest.Builder request = ChatRequest.builder()
                .messages(mapped);
        if (options.getModelId() != null) {
            request.modelName(options.getModelId());
        }
        if (options.getTemperature() != null) {
            request.temperature(options.getTemperature());
        }
        if (options.getMaxOutputTokens() != null) {
            request.maxOutputTokens(options.getMaxOutputTokens());
        }
        if (options.getResponseFormat() != null) {
            request.responseFormat(responseFormat(
                    options.getResponseFormat()));
        }
        List<ToolSpecification> tools = new ArrayList<>();
        for (Tool tool : options.getTools()) {
            tools.add(tool(tool));
        }
        if (!tools.isEmpty()) {
            request.toolSpecifications(tools);
        }
        return request.build();
    }

    private dev.langchain4j.model.chat.request.ResponseFormat responseFormat(
            io.github.weidongxu.agentframework.chat.ResponseFormat format) {
        switch (format.getType()) {
            case TEXT:
                return dev.langchain4j.model.chat.request.ResponseFormat.TEXT;
            case JSON_OBJECT:
                return dev.langchain4j.model.chat.request.ResponseFormat.JSON;
            case JSON_SCHEMA:
                return dev.langchain4j.model.chat.request.ResponseFormat.builder()
                        .type(dev.langchain4j.model.chat.request.ResponseFormatType.JSON)
                        .jsonSchema(
                                dev.langchain4j.model.chat.request.json.JsonSchema
                                        .builder()
                                        .name(format.getName())
                                        .rootElement(objectSchema(
                                                format.getSchema()))
                                        .build())
                        .build();
            default:
                throw new IllegalArgumentException(
                        "Unsupported response format: " + format.getType());
        }
    }

    private dev.langchain4j.data.message.ChatMessage map(
            ChatMessage message) {
        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> calls = new ArrayList<>();
        FunctionResultContent result = null;
        for (ChatContent content : message.getContents()) {
            if (content instanceof TextContent) {
                text.append(((TextContent) content).getText());
            } else if (content instanceof FunctionCallContent) {
                FunctionCallContent call = (FunctionCallContent) content;
                calls.add(ToolExecutionRequest.builder()
                        .id(call.getCallId())
                        .name(call.getName())
                        .arguments(call.getArguments())
                        .build());
            } else if (content instanceof FunctionResultContent) {
                if (result != null || message.getContents().size() != 1) {
                    throw new IllegalArgumentException(
                            "LangChain4j tool messages require one result");
                }
                result = (FunctionResultContent) content;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported LangChain4j chat content: "
                                + content.getClass().getName());
            }
        }
        if (result != null) {
            if (message.getRole() != ChatRole.TOOL) {
                throw new IllegalArgumentException(
                        "Function results require the TOOL role");
            }
            return ToolExecutionResultMessage.from(
                    result.getCallId(),
                    message.getAuthorName() == null
                            ? "tool"
                            : message.getAuthorName(),
                    result.getResult());
        }
        switch (message.getRole()) {
            case SYSTEM:
            case DEVELOPER:
                requireNoCalls(calls, message.getRole());
                return SystemMessage.from(text.toString());
            case ASSISTANT:
                return text.length() == 0
                        ? AiMessage.from(calls)
                        : AiMessage.from(text.toString(), calls);
            case USER:
                requireNoCalls(calls, message.getRole());
                return message.getAuthorName() == null
                        ? UserMessage.from(text.toString())
                        : UserMessage.from(
                                message.getAuthorName(),
                                text.toString());
            default:
                throw new IllegalArgumentException(
                        "Unsupported LangChain4j role: "
                                + message.getRole());
        }
    }

    private static void requireNoCalls(
            List<ToolExecutionRequest> calls,
            ChatRole role) {
        if (!calls.isEmpty()) {
            throw new IllegalArgumentException(
                    "Function calls require the ASSISTANT role, not " + role);
        }
    }

    private ToolSpecification tool(Tool tool) {
        return ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .parameters(objectSchema(tool.getParametersSchema()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private JsonObjectSchema objectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder result = JsonObjectSchema.builder();
        Object description = schema.get("description");
        if (description instanceof String) {
            result.description((String) description);
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?>) {
            ((Map<?, ?>) properties).forEach((name, value) -> {
                if (!(name instanceof String)
                        || !(value instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(
                            "Tool schema properties must be objects");
                }
                result.addProperty(
                        (String) name,
                        schemaElement((Map<String, Object>) value));
            });
        }
        Object required = schema.get("required");
        if (required instanceof List<?>) {
            List<String> names = new ArrayList<>();
            for (Object name : (List<?>) required) {
                if (!(name instanceof String)) {
                    throw new IllegalArgumentException(
                            "Tool schema required values must be strings");
                }
                names.add((String) name);
            }
            result.required(names);
        }
        Object additional = schema.get("additionalProperties");
        if (additional instanceof Boolean) {
            result.additionalProperties((Boolean) additional);
        }
        return result.build();
    }

    @SuppressWarnings("unchecked")
    private JsonSchemaElement schemaElement(Map<String, Object> schema) {
        String description = schema.get("description") instanceof String
                ? (String) schema.get("description")
                : null;
        Object values = schema.get("enum");
        if (values instanceof List<?>) {
            List<String> strings = new ArrayList<>();
            for (Object value : (List<?>) values) {
                strings.add(String.valueOf(value));
            }
            return JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(strings)
                    .build();
        }
        String type = String.valueOf(schema.getOrDefault("type", "string"));
        switch (type) {
            case "object":
                return objectSchema(schema);
            case "array":
                Object items = schema.get("items");
                if (!(items instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(
                            "Array tool schema requires object items");
                }
                return JsonArraySchema.builder()
                        .description(description)
                        .items(schemaElement((Map<String, Object>) items))
                        .build();
            case "integer":
                return JsonIntegerSchema.builder()
                        .description(description)
                        .build();
            case "number":
                return JsonNumberSchema.builder()
                        .description(description)
                        .build();
            case "boolean":
                return JsonBooleanSchema.builder()
                        .description(description)
                        .build();
            case "string":
                return JsonStringSchema.builder()
                        .description(description)
                        .build();
            default:
                throw new IllegalArgumentException(
                        "Unsupported tool schema type: " + type);
        }
    }

    private static ChatResponse map(
            dev.langchain4j.model.chat.response.ChatResponse response) {
        AiMessage message = response.aiMessage();
        ChatMessage.Builder mapped =
                ChatMessage.builder(ChatRole.ASSISTANT);
        if (message.text() != null && !message.text().isEmpty()) {
            mapped.text(message.text());
        }
        for (ToolExecutionRequest call : message.toolExecutionRequests()) {
            mapped.addContent(new FunctionCallContent(
                    call.id(),
                    call.name(),
                    call.arguments()));
        }
        ChatResponse.Builder result = ChatResponse.builder()
                .message(mapped.build())
                .responseId(response.id())
                .finishReason(map(response.finishReason()))
                .rawRepresentation(response);
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            result.usage(new Usage(
                    value(usage.inputTokenCount()),
                    value(usage.outputTokenCount())));
        }
        if (response.modelName() != null) {
            result.additionalProperty(
                    "langchain4j.model_name",
                    response.modelName());
        }
        return result.build();
    }

    private static FinishReason map(
            dev.langchain4j.model.output.FinishReason reason) {
        if (reason == null) {
            return null;
        }
        switch (reason) {
            case STOP:
                return FinishReason.STOP;
            case LENGTH:
                return FinishReason.LENGTH;
            case TOOL_EXECUTION:
                return FinishReason.TOOL_CALLS;
            case CONTENT_FILTER:
                return FinishReason.CONTENT_FILTER;
            default:
                return FinishReason.OTHER;
        }
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long addCap(long current, long increment) {
        long result = current + increment;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    private final class StreamingSubscription
            implements Flow.Subscription, StreamingChatResponseHandler {
        private final Flow.Subscriber<? super ChatResponseUpdate> downstream;
        private final ChatRequest request;
        private final Deque<ChatResponseUpdate> pending = new ArrayDeque<>();
        private long demand;
        private boolean started;
        private boolean cancelled;
        private boolean terminal;
        private boolean draining;
        private Throwable failure;
        private StreamingHandle streamingHandle;

        private StreamingSubscription(
                Flow.Subscriber<? super ChatResponseUpdate> downstream,
                ChatRequest request) {
            this.downstream = downstream;
            this.request = request;
        }

        private synchronized void start() {
            if (started || cancelled) {
                return;
            }
            started = true;
            executor.execute(() -> {
                try {
                    streamingChatModel.chat(request, this);
                } catch (Throwable error) {
                    onError(error);
                }
            });
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                onError(new IllegalArgumentException(
                        "Flow demand must be positive"));
                return;
            }
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                demand = addCap(demand, count);
            }
            drain();
        }

        @Override
        public void cancel() {
            StreamingHandle handle;
            synchronized (this) {
                cancelled = true;
                pending.clear();
                handle = streamingHandle;
            }
            if (handle != null) {
                handle.cancel();
            }
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            enqueue(ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .text(partialResponse)
                    .build());
        }

        @Override
        public void onPartialResponse(
                PartialResponse partialResponse,
                PartialResponseContext context) {
            capture(context.streamingHandle());
            onPartialResponse(partialResponse.text());
        }

        @Override
        public void onPartialToolCall(
                PartialToolCall partialToolCall,
                PartialToolCallContext context) {
            capture(context.streamingHandle());
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall call) {
            ToolExecutionRequest tool = call.toolExecutionRequest();
            enqueue(ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .content(new FunctionCallContent(
                            tool.id(),
                            tool.name(),
                            tool.arguments()))
                    .build());
        }

        @Override
        public void onCompleteResponse(
                dev.langchain4j.model.chat.response.ChatResponse response) {
            ChatResponse mapped = map(response);
            enqueue(ChatResponseUpdate.builder()
                    .role(ChatRole.ASSISTANT)
                    .responseId(mapped.getResponseId())
                    .finishReason(mapped.getFinishReason())
                    .rawRepresentation(response)
                    .build());
            synchronized (this) {
                terminal = true;
            }
            drain();
        }

        @Override
        public void onError(Throwable error) {
            synchronized (this) {
                if (cancelled || terminal) {
                    return;
                }
                failure = Objects.requireNonNull(error, "error");
                terminal = true;
            }
            drain();
        }

        private void enqueue(ChatResponseUpdate update) {
            synchronized (this) {
                if (cancelled || terminal) {
                    return;
                }
                pending.addLast(update);
            }
            drain();
        }

        private void capture(StreamingHandle handle) {
            boolean cancel;
            synchronized (this) {
                streamingHandle = handle;
                cancel = cancelled;
            }
            if (cancel && handle != null) {
                handle.cancel();
            }
        }

        private void drain() {
            synchronized (this) {
                if (draining || cancelled) {
                    return;
                }
                draining = true;
            }
            while (true) {
                ChatResponseUpdate update;
                Throwable error;
                boolean complete;
                synchronized (this) {
                    if (cancelled) {
                        draining = false;
                        return;
                    }
                    if (demand > 0 && !pending.isEmpty()) {
                        update = pending.removeFirst();
                        if (demand != Long.MAX_VALUE) {
                            demand--;
                        }
                        error = null;
                        complete = false;
                    } else {
                        update = null;
                        error = terminal && pending.isEmpty()
                                ? failure
                                : null;
                        complete = terminal
                                && pending.isEmpty()
                                && failure == null;
                        if (error != null || complete) {
                            cancelled = true;
                        } else {
                            draining = false;
                        }
                    }
                }
                if (update != null) {
                    downstream.onNext(update);
                } else if (error != null) {
                    downstream.onError(error);
                    return;
                } else if (complete) {
                    downstream.onComplete();
                    return;
                } else {
                    return;
                }
            }
        }
    }
}
