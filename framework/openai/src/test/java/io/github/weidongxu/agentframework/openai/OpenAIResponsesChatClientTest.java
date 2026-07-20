package io.github.weidongxu.agentframework.openai;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.ChatResponse;
import io.github.weidongxu.agentframework.chat.ChatResponseUpdate;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.DataContent;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIResponsesChatClientTest {
    @Test
    void forwardsCallIdHeaderWhenPresent() {
        ChatOptions options = ChatOptions.builder()
                .modelId("gpt-test")
                .additionalProperty("x-agent-foundry-call-id", "call-123")
                .additionalProperty("x-agent-user-id", "user-should-not-leak")
                .build();

        ResponseCreateParams params = OpenAIResponsesChatClient.createParams(
                Arrays.asList(ChatMessage.user("hello")), options);

        assertEquals(
                Arrays.asList("call-123"),
                params._additionalHeaders().values("x-agent-foundry-call-id"));
        assertTrue(params._additionalHeaders().values("x-agent-user-id").isEmpty());
    }

    @Test
    void doesNotForwardCallIdHeaderWhenAbsent() {
        ChatOptions options = ChatOptions.builder().modelId("gpt-test").build();

        ResponseCreateParams params = OpenAIResponsesChatClient.createParams(
                Arrays.asList(ChatMessage.user("hello")), options);

        assertTrue(params._additionalHeaders().values("x-agent-foundry-call-id").isEmpty());
    }

    @Test
    void mapsImageDataContentToInputImage() {
        ChatMessage message = ChatMessage.builder(ChatRole.USER)
                .text("what adjustments does this photo need?")
                .addContent(new DataContent(new byte[] {1, 2, 3, 4}, "image/jpeg", "preview.jpg"))
                .build();

        ResponseCreateParams params = OpenAIResponsesChatClient.createParams(
                List.of(message), ChatOptions.builder().modelId("gpt-test").build());

        List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
        assertTrue(input.get(0).isEasyInputMessage());
        var content = input.get(0).asEasyInputMessage().content();
        assertTrue(content.isResponseInputMessageContentList());
        var parts = content.asResponseInputMessageContentList();
        assertTrue(parts.stream().anyMatch(p -> p.isInputText()));
        var image = parts.stream().filter(p -> p.isInputImage()).findFirst().orElseThrow();
        assertTrue(image.asInputImage().imageUrl().orElse("").startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void skipsNonImageDataContent() {
        ChatMessage message = ChatMessage.builder(ChatRole.USER)
                .text("develop this")
                .addContent(new DataContent(new byte[] {9, 9}, "image/x-fuji-raf", "shot.raf"))
                .build();

        ResponseCreateParams params = OpenAIResponsesChatClient.createParams(
                List.of(message), ChatOptions.builder().modelId("gpt-test").build());

        List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
        // A camera RAW is not sent upstream: the message stays plain text.
        assertEquals("develop this", input.get(0).asEasyInputMessage().content().asTextInput());
    }

    @Test
    void mapsMessagesAndContinuationToRequest() {
        ChatMessage toolResult = ChatMessage.builder(ChatRole.TOOL)
                .addContent(new FunctionResultContent("call_1", "{\"ok\":true}", false))
                .build();
        ChatOptions options = ChatOptions.builder()
                .modelId("gpt-test")
                .instructions("Be concise.")
                .continuationToken("resp_previous")
                .build();

        ResponseCreateParams params =
                OpenAIResponsesChatClient.createParams(
                        Arrays.asList(ChatMessage.user("hello"), toolResult),
                        options);

        assertEquals("resp_previous", params.previousResponseId().orElse(null));
        assertEquals("Be concise.", params.instructions().orElse(null));
        List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
        assertTrue(input.get(0).isEasyInputMessage());
        assertEquals(
                "hello",
                input.get(0).asEasyInputMessage().content().asTextInput());
        assertTrue(input.get(1).isFunctionCallOutput());
        assertEquals("call_1", input.get(1).asFunctionCallOutput().callId());
    }

    @Test
    void mapsBufferedTextToolCallsUsageAndState() throws Exception {
        Response response = response(
                "[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\","
                        + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                        + "\"text\":\"hello\",\"annotations\":[],\"logprobs\":[]}]},"
                        + "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                        + "\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}]",
                "\"conversation\":{\"id\":\"conv_1\"},"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":5,"
                        + "\"input_tokens_details\":{\"cached_tokens\":0},"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":0},"
                        + "\"total_tokens\":8},");

        ChatResponse mapped = OpenAIResponsesChatClient.mapResponse(response);

        assertEquals("resp_1", mapped.getResponseId());
        assertEquals("resp_1", mapped.getContinuationToken());
        assertEquals("conv_1", mapped.getConversationId());
        assertEquals("hello", mapped.getText());
        assertEquals(FinishReason.TOOL_CALLS, mapped.getFinishReason());
        assertEquals(3, mapped.getUsage().getInputTokens());
        FunctionCallContent call = (FunctionCallContent) mapped.getMessages()
                .get(1).getContents().get(0);
        assertEquals("call_1", call.getCallId());
        assertEquals("lookup", call.getName());
    }

    @Test
    void streamingHonorsDemandAndClosesAtCompletedEvent() throws Exception {
        ResponseStreamEvent text = event(
                "{\"type\":\"response.output_text.delta\",\"content_index\":0,"
                        + "\"delta\":\"hi\",\"item_id\":\"msg_1\",\"logprobs\":[],"
                        + "\"output_index\":0,\"sequence_number\":1}");
        ResponseStreamEvent completed = event(
                "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":"
                        + responseJson("[]", "") + "}");
        AtomicBoolean closed = new AtomicBoolean();
        FakeTransport transport = new FakeTransport(
                response("[]", ""),
                new TestStreamResponse(Arrays.asList(text, completed), closed));
        OpenAIResponsesChatClient client =
                new OpenAIResponsesChatClient(transport, Runnable::run);
        TestSubscriber subscriber = new TestSubscriber();

        client.getStreamingResponse(
                        List.of(ChatMessage.user("hello")),
                        ChatOptions.builder().modelId("gpt-test").build())
                .subscribe(subscriber);

        subscriber.subscription.request(1);
        assertEquals(1, subscriber.updates.size());
        assertEquals("hi", subscriber.updates.get(0).getText());
        assertFalse(subscriber.completed);

        subscriber.subscription.request(1);
        assertEquals(2, subscriber.updates.size());
        assertEquals("resp_1", subscriber.updates.get(1).getContinuationToken());
        assertTrue(subscriber.completed);
        assertTrue(closed.get());
    }

    @Test
    void preservesCreatedStreamingEvent() throws Exception {
        ResponseStreamEvent created = event(
                "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                        + responseJson("[]", "") + "}");
        ResponseStreamEvent completed = event(
                "{\"type\":\"response.completed\",\"sequence_number\":1,\"response\":"
                        + responseJson("[]", "") + "}");
        FakeTransport transport = new FakeTransport(
                response("[]", ""),
                new TestStreamResponse(
                        Arrays.asList(created, completed),
                        new AtomicBoolean()));
        TestSubscriber subscriber = new TestSubscriber();
        new OpenAIResponsesChatClient(transport, Runnable::run)
                .getStreamingResponse(
                        List.of(ChatMessage.user("hello")),
                        ChatOptions.builder().modelId("gpt-test").build())
                .subscribe(subscriber);

        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(2, subscriber.updates.size());
        assertEquals(created, subscriber.updates.get(0).getRawRepresentation());
        assertTrue(subscriber.completed);
    }

    @Test
    void mapsStreamingRefusalText() throws Exception {
        ResponseStreamEvent refusal = event(
                "{\"type\":\"response.refusal.delta\",\"content_index\":0,"
                        + "\"delta\":\"cannot comply\",\"item_id\":\"msg_1\","
                        + "\"output_index\":0,\"sequence_number\":1}");
        ResponseStreamEvent completed = event(
                "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":"
                        + responseJson("[]", "") + "}");
        FakeTransport transport = new FakeTransport(
                response("[]", ""),
                new TestStreamResponse(
                        Arrays.asList(refusal, completed),
                        new AtomicBoolean()));
        TestSubscriber subscriber = new TestSubscriber();
        new OpenAIResponsesChatClient(transport, Runnable::run)
                .getStreamingResponse(
                        List.of(ChatMessage.user("hello")),
                        ChatOptions.builder().modelId("gpt-test").build())
                .subscribe(subscriber);

        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals("cannot comply", subscriber.updates.get(0).getText());
        assertEquals(FinishReason.CONTENT_FILTER, subscriber.updates.get(0).getFinishReason());
        assertTrue(subscriber.completed);
    }

    @Test
    void preservesFailedStreamingEventAsTerminalUpdate() throws Exception {
        String failedResponse = responseJson("[]", "")
                .replace(
                        "\"error\":null",
                        "\"error\":{\"code\":\"server_error\","
                                + "\"message\":\"failed\"}")
                .replace("\"status\":\"completed\"", "\"status\":\"failed\"");
        ResponseStreamEvent failed = event(
                "{\"type\":\"response.failed\",\"sequence_number\":1,\"response\":"
                        + failedResponse + "}");
        FakeTransport transport = new FakeTransport(
                response("[]", ""),
                new TestStreamResponse(
                        List.of(failed),
                        new AtomicBoolean()));
        TestSubscriber subscriber = new TestSubscriber();
        new OpenAIResponsesChatClient(transport, Runnable::run)
                .getStreamingResponse(
                        List.of(ChatMessage.user("hello")),
                        ChatOptions.builder().modelId("gpt-test").build())
                .subscribe(subscriber);

        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(1, subscriber.updates.size());
        assertEquals(failed, subscriber.updates.get(0).getRawRepresentation());
        assertEquals(FinishReason.OTHER, subscriber.updates.get(0).getFinishReason());
        assertTrue(subscriber.completed);
    }

    @Test
    void prematureStreamingEofIsAnError() throws Exception {
        ResponseStreamEvent text = event(
                "{\"type\":\"response.output_text.delta\",\"content_index\":0,"
                        + "\"delta\":\"partial\",\"item_id\":\"msg_1\",\"logprobs\":[],"
                        + "\"output_index\":0,\"sequence_number\":1}");
        FakeTransport transport = new FakeTransport(
                response("[]", ""),
                new TestStreamResponse(List.of(text), new AtomicBoolean()));
        TestSubscriber subscriber = new TestSubscriber();
        new OpenAIResponsesChatClient(transport, Runnable::run)
                .getStreamingResponse(
                        List.of(ChatMessage.user("hello")),
                        ChatOptions.builder().modelId("gpt-test").build())
                .subscribe(subscriber);

        subscriber.subscription.request(Long.MAX_VALUE);

        assertTrue(subscriber.error instanceof OpenAIAdapterException);
        assertFalse(subscriber.completed);
    }

    @Test
    void bufferedResponseErrorIsThrown() throws Exception {
        Response failed = response(
                "[]",
                "\"error\":{\"code\":\"server_error\",\"message\":\"failed\"},"
                        + "\"status\":\"failed\",");

        assertThrows(
                OpenAIAdapterException.class,
                () -> OpenAIResponsesChatClient.mapResponse(failed));
    }

    @Test
    void cancellationClosesStreamingResponse() throws Exception {
        ResponseStreamEvent text = event(
                "{\"type\":\"response.output_text.delta\",\"content_index\":0,"
                        + "\"delta\":\"hi\",\"item_id\":\"msg_1\",\"logprobs\":[],"
                        + "\"output_index\":0,\"sequence_number\":1}");
        AtomicBoolean closed = new AtomicBoolean();
        FakeTransport transport = new FakeTransport(
                response("[]", ""),
                new TestStreamResponse(List.of(text), closed));
        OpenAIResponsesChatClient client =
                new OpenAIResponsesChatClient(transport, Runnable::run);
        TestSubscriber subscriber = new TestSubscriber();

        client.getStreamingResponse(
                        List.of(ChatMessage.user("hello")),
                        ChatOptions.builder().modelId("gpt-test").build())
                .subscribe(subscriber);
        subscriber.subscription.request(1);
        subscriber.subscription.cancel();

        assertTrue(closed.get());
        assertFalse(subscriber.completed);
    }

    @Test
    void mapsHostedToolsToResponsesToolUnion() {
        ChatOptions options = ChatOptions.builder()
                .modelId("gpt-test")
                .tool(new io.github.weidongxu.agentframework.tool.HostedWebSearchTool(
                        io.github.weidongxu.agentframework.tool.HostedWebSearchTool
                                .SearchContextSize.HIGH))
                .tool(new io.github.weidongxu.agentframework.tool.HostedCodeInterpreterTool())
                .tool(new io.github.weidongxu.agentframework.tool.HostedFileSearchTool(
                        List.of("vs_1", "vs_2"), 5))
                .tool(new io.github.weidongxu.agentframework.tool.HostedMcpTool(
                        "github", "https://mcp.example/sse", List.of("search"),
                        io.github.weidongxu.agentframework.tool.ApprovalMode.ALWAYS_REQUIRE))
                .tool(new io.github.weidongxu.agentframework.tool.HostedImageGenerationTool(
                        "gpt-image-1"))
                .build();

        ResponseCreateParams params = OpenAIResponsesChatClient.createParams(
                List.of(ChatMessage.user("hello")), options);
        List<com.openai.models.responses.Tool> tools = params.tools().orElseThrow();

        assertEquals(5, tools.size());

        com.openai.models.responses.WebSearchTool webSearch = tools.get(0).asWebSearch();
        assertEquals(
                com.openai.models.responses.WebSearchTool.SearchContextSize.HIGH,
                webSearch.searchContextSize().orElseThrow());

        assertTrue(tools.get(1).asCodeInterpreter().container().isCodeInterpreterToolAuto());

        com.openai.models.responses.FileSearchTool fileSearch = tools.get(2).asFileSearch();
        assertEquals(List.of("vs_1", "vs_2"), fileSearch.vectorStoreIds());
        assertEquals(5L, fileSearch.maxNumResults().orElseThrow());

        com.openai.models.responses.Tool.Mcp mcp = tools.get(3).asMcp();
        assertEquals("github", mcp.serverLabel());
        assertEquals("https://mcp.example/sse", mcp.serverUrl().orElseThrow());
        assertEquals(List.of("search"), mcp.allowedTools().orElseThrow().asMcp());
        assertEquals(
                com.openai.models.responses.Tool.Mcp.RequireApproval.McpToolApprovalSetting.ALWAYS,
                mcp.requireApproval().orElseThrow().asMcpToolApprovalSetting());

        assertEquals(
                "gpt-image-1",
                tools.get(4).asImageGeneration().model().orElseThrow().asString());
    }

    @Test
    void hostedToolCannotBeInvokedLocally() {
        io.github.weidongxu.agentframework.tool.HostedTool tool =
                new io.github.weidongxu.agentframework.tool.HostedWebSearchTool();
        assertEquals("web_search", tool.getName());
        assertTrue(tool.getParametersSchema().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> tool.invoke(java.util.Collections.emptyMap()));
    }

    private static Response response(String output, String extra) throws Exception {
        return ObjectMappers.jsonMapper().readValue(
                responseJson(output, extra),
                Response.class);
    }

    private static String responseJson(String output, String extra) {
        return "{\"id\":\"resp_1\",\"created_at\":1,\"error\":null,"
                + "\"incomplete_details\":null,\"instructions\":null,\"metadata\":{},"
                + "\"model\":\"gpt-test\",\"object\":\"response\",\"output\":" + output + ","
                + "\"parallel_tool_calls\":true,\"temperature\":1,\"tool_choice\":\"auto\","
                + "\"tools\":[],\"top_p\":1," + extra + "\"status\":\"completed\"}";
    }

    private static ResponseStreamEvent event(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, ResponseStreamEvent.class);
    }

    private static final class FakeTransport implements OpenAIResponseTransport {
        private final Response response;
        private final StreamResponse<ResponseStreamEvent> streamingResponse;

        private FakeTransport(
                Response response,
                StreamResponse<ResponseStreamEvent> streamingResponse) {
            this.response = response;
            this.streamingResponse = streamingResponse;
        }

        @Override
        public CompletableFuture<Response> create(ResponseCreateParams params) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public StreamResponse<ResponseStreamEvent> createStreaming(
                ResponseCreateParams params) {
            assertNotNull(params);
            return streamingResponse;
        }
    }

    private static final class TestStreamResponse
            implements StreamResponse<ResponseStreamEvent> {
        private final List<ResponseStreamEvent> events;
        private final AtomicBoolean closed;

        private TestStreamResponse(
                List<ResponseStreamEvent> events,
                AtomicBoolean closed) {
            this.events = events;
            this.closed = closed;
        }

        @Override
        public Stream<ResponseStreamEvent> stream() {
            return events.stream();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class TestSubscriber
            implements Flow.Subscriber<ChatResponseUpdate> {
        private final List<ChatResponseUpdate> updates = new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable error;
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
            error = throwable;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}
