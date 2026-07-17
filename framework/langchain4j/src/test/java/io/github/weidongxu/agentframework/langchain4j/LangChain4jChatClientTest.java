package io.github.weidongxu.agentframework.langchain4j;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.ResponseFormat;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jChatClientTest {
    @Test
    void mapsBufferedMessagesToolsUsageAndFinishReason() throws Exception {
        RecordingChatModel model = new RecordingChatModel();
        LangChain4jChatClient client = new LangChain4jChatClient(
                model,
                new StreamingChatModel() {
                    @Override
                    public void doChat(
                            ChatRequest request,
                            StreamingChatResponseHandler handler) {
                    }
                },
                Runnable::run);
        FunctionTool tool = new FunctionTool(
                "lookup",
                "Lookup",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string")),
                        "required", List.of("query")),
                arguments -> CompletableFuture.completedFuture("unused"));

        ChatResponse response = client.getResponse(
                        Collections.singletonList(ChatMessage.user("hello")),
                        ChatOptions.builder()
                                .modelId("model-1")
                                .instructions("Be concise.")
                                .temperature(0.2)
                                .maxOutputTokens(100)
                                .responseFormat(ResponseFormat.jsonObject())
                                .tool(tool)
                                .build())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertTrue(model.request.messages().get(0) instanceof SystemMessage);
        assertEquals("model-1", model.request.modelName());
        assertEquals(1, model.request.toolSpecifications().size());
        assertEquals(
                dev.langchain4j.model.chat.request.ResponseFormatType.JSON,
                model.request.responseFormat().type());
        assertEquals("hello", response.getText());
        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(3, response.getUsage().getInputTokens());
        FunctionCallContent call = (FunctionCallContent) response
                .getMessages().get(0).getContents().get(1);
        assertEquals("lookup", call.getName());
    }

    @Test
    void streamingHonorsDemandAndSuppressesCallbacksAfterCancel()
            throws Exception {
        RecordingStreamingModel model = new RecordingStreamingModel();
        LangChain4jChatClient client = new LangChain4jChatClient(
                new RecordingChatModel(),
                model,
                Runnable::run);
        CollectingSubscriber subscriber = new CollectingSubscriber();

        client.getStreamingResponse(
                        Collections.singletonList(ChatMessage.user("hello")),
                        ChatOptions.builder().build())
                .subscribe(subscriber);
        model.emit();

        assertTrue(subscriber.updates.isEmpty());
        subscriber.subscription.request(1);
        assertEquals("part", subscriber.updates.get(0).getText());
        assertFalse(subscriber.completed);
        subscriber.subscription.cancel();
        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(1, subscriber.updates.size());
        assertFalse(subscriber.completed);
        assertTrue(model.cancelled.get());
    }

    private static dev.langchain4j.model.chat.response.ChatResponse response() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("lookup")
                .arguments("{\"query\":\"x\"}")
                .build();
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .id("response-1")
                .modelName("model-1")
                .aiMessage(AiMessage.from(
                        "hello",
                        Collections.singletonList(call)))
                .tokenUsage(new TokenUsage(3, 4, 7))
                .finishReason(
                        dev.langchain4j.model.output.FinishReason
                                .TOOL_EXECUTION)
                .build();
    }

    private static final class RecordingChatModel implements ChatModel {
        private ChatRequest request;

        @Override
        public dev.langchain4j.model.chat.response.ChatResponse doChat(
                ChatRequest request) {
            this.request = request;
            return response();
        }
    }

    private static final class RecordingStreamingModel
            implements StreamingChatModel {
        private StreamingChatResponseHandler handler;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void doChat(
                ChatRequest request,
                StreamingChatResponseHandler handler) {
            this.handler = handler;
        }

        private void emit() {
            handler.onPartialResponse(
                    new PartialResponse("part"),
                    new PartialResponseContext(new StreamingHandle() {
                        @Override
                        public void cancel() {
                            cancelled.set(true);
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled.get();
                        }
                    }));
            handler.onCompleteToolCall(new CompleteToolCall(
                    0,
                    ToolExecutionRequest.builder()
                            .id("call-1")
                            .name("lookup")
                            .arguments("{}")
                            .build()));
            handler.onCompleteResponse(response());
        }
    }

    private static final class CollectingSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private final List<ChatResponseUpdate> updates =
                new ArrayList<>();
        private final CompletableFuture<Void> completion =
                new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
            completion.complete(null);
        }
    }
}
