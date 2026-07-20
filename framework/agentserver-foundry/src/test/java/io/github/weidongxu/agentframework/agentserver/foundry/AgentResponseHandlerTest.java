package io.github.weidongxu.agentframework.agentserver.foundry;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryConversationStore;
import io.github.weidongxu.agentframework.agentserver.responses.InMemoryResponseStore;
import io.github.weidongxu.agentframework.agentserver.responses.StoredResponse;
import io.github.weidongxu.agentframework.agentserver.responses.PlatformContext;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseContext;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseRequest;
import io.github.weidongxu.agentframework.agentserver.responses.ResponseSink;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.DataContent;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResponseHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ResponseContext buffered() {
        return new ResponseContext(PlatformContext.EMPTY, false);
    }

    private static ResponseContext streaming() {
        return new ResponseContext(PlatformContext.EMPTY, true);
    }

    private static ResponseContext identity(String userId, String callId) {
        return new ResponseContext(new PlatformContext(userId, callId), false);
    }

    @Test
    void returnsNormalizedBufferedResponse() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .responseId("resp_1")
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);
        MockSink sink = new MockSink();

        handler.handle(
                new ResponseRequest(Map.of("input", "question", "model", "model-1")),
                buffered(),
                sink);

        JsonNode body = objectMapper.readTree(sink.json);
        assertEquals("resp_1", body.path("id").asText());
        assertEquals("response", body.path("object").asText());
        assertEquals("hello", body.at("/output/0/content/0/text").asText());
        assertEquals("question", agent.messages.get(0).getText());
        assertEquals("model-1", agent.options.getChatOptions().getModelId());
    }

    @Test
    void propagatesFoundryHostedIdentity() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);

        handler.handle(
                new ResponseRequest(Map.of("input", "question")),
                identity("user-1", "call-1"),
                new MockSink());

        assertEquals("user-1",
                agent.options.getAdditionalProperties().get("x-agent-user-id"));
        assertEquals("call-1",
                agent.options.getAdditionalProperties().get("x-agent-foundry-call-id"));
    }

    @Test
    void establishesSessionFromUserId() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);

        handler.handle(
                new ResponseRequest(Map.of("input", "question")),
                identity("user-2", "call-2"),
                new MockSink());

        assertEquals("user-2", agent.session.getId());
    }

    @Test
    void parsesInputFileAttachmentIntoDataContent() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("ok"))
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};
        String base64 = Base64.getEncoder().encodeToString(bytes);

        handler.handle(
                new ResponseRequest(Map.of("input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", "develop this"),
                                Map.of("type", "input_file", "filename", "shot.RAF",
                                        "file_data", "data:image/x-fuji-raf;base64," + base64)))))),
                buffered(),
                new MockSink());

        ChatMessage message = agent.messages.get(0);
        // The text part keeps the prompt and gains a breadcrumb naming the attachment.
        assertTrue(message.getText().contains("develop this"));
        assertTrue(message.getText().contains("shot.RAF"));
        // The bytes are carried as DataContent, never flattened to text or sent to the model.
        DataContent data = (DataContent) message.getContents().stream()
                .filter(DataContent.class::isInstance)
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertArrayEquals(bytes, data.getData());
        assertEquals("image/x-fuji-raf", data.getMediaType());
        assertEquals("shot.RAF", data.getName());
    }

    @Test
    void hasNoSessionWithoutIdentity() throws Exception {        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);

        handler.handle(
                new ResponseRequest(Map.of("input", "question")),
                buffered(),
                new MockSink());

        assertNull(agent.session);
    }

    @Test
    void returnsFunctionCallsAsTopLevelOutputItems() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(new FunctionCallContent(
                                "call_1",
                                "lookup",
                                "{\"q\":\"x\"}"))
                        .build())
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);
        MockSink sink = new MockSink();

        handler.handle(new ResponseRequest(Map.of("input", "question")), buffered(), sink);

        JsonNode item = objectMapper.readTree(sink.json).at("/output/0");
        assertEquals("function_call", item.path("type").asText());
        assertEquals("call_1", item.path("call_id").asText());
        assertEquals("lookup", item.path("name").asText());
    }

    @Test
    void mapsToolApprovalRequestsAndResponses() throws Exception {
        FunctionCallContent call =
                new FunctionCallContent("call_1", "delete", "{}");
        CapturingAgent requesting = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(new ToolApprovalRequestContent(
                                "approval_1",
                                call))
                        .build())
                .rawRepresentation(Map.of(
                        "id", "raw_response",
                        "object", "response",
                        "output", Collections.emptyList()))
                .build());
        MockSink requestSink = new MockSink();

        new AgentResponseHandler(requesting, objectMapper)
                .handle(new ResponseRequest(Map.of("input", "question")), buffered(), requestSink);

        JsonNode item = objectMapper.readTree(requestSink.json).at("/output/0");
        assertEquals("tool_approval_request", item.path("type").asText());
        assertEquals("approval_1", item.path("id").asText());
        assertEquals("delete", item.path("name").asText());
        assertTrue(!"raw_response".equals(
                objectMapper.readTree(requestSink.json).path("id").asText()));

        CapturingAgent approving = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("done"))
                .build());
        new AgentResponseHandler(approving, objectMapper)
                .handle(new ResponseRequest(Map.of("input", List.of(Map.of(
                                "type", "tool_approval_response",
                                "request_id", "approval_1",
                                "approved", true)))),
                        buffered(),
                        new MockSink());

        ToolApprovalResponseContent response = (ToolApprovalResponseContent)
                approving.messages.get(0).getContents().get(0);
        assertEquals("approval_1", response.getRequestId());
        assertTrue(response.isApproved());
    }

    @Test
    void stampsResponseMetadataOntoBufferedEnvelope() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .responseId("resp_meta")
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper)
                .withResponseMetadata(Map.of("chat_client", "langchain4j"));
        MockSink sink = new MockSink();

        handler.handle(new ResponseRequest(Map.of("input", "question")), buffered(), sink);

        JsonNode body = objectMapper.readTree(sink.json);
        assertEquals("langchain4j", body.at("/metadata/chat_client").asText());
    }

    @Test
    void persistsResponseWhenStoreProvided() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .responseId("resp_persist")
                .build());
        InMemoryResponseStore responseStore = new InMemoryResponseStore();
        AgentResponseHandler handler = new AgentResponseHandler(
                agent, objectMapper, Duration.ofMinutes(5), null, false, responseStore);

        handler.handle(
                new ResponseRequest(Map.of("input", "question", "model", "model-1")),
                buffered(),
                new MockSink());

        StoredResponse stored = responseStore.get("resp_persist").orElseThrow(AssertionError::new);
        assertEquals("resp_persist", stored.getEnvelope().get("id"));
        assertEquals(1, stored.getInputItems().size());
        Map<String, Object> item = stored.getInputItems().get(0);
        assertEquals("message", item.get("type"));
        assertEquals("user", item.get("role"));
    }

    @Test
    void passesThroughRawResponsesShape() throws Exception {
        Map<String, Object> raw = Map.of(
                "id", "resp_raw",
                "object", "response",
                "output", Collections.emptyList(),
                "custom", true);
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .rawRepresentation(raw)
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(agent, objectMapper);
        MockSink sink = new MockSink();

        handler.handle(new ResponseRequest(Map.of("input", "question")), buffered(), sink);

        JsonNode body = objectMapper.readTree(sink.json);
        assertEquals("resp_raw", body.path("id").asText());
        assertTrue(body.path("custom").asBoolean());
    }

    @Test
    void threadsHistoryFromStoreWithoutForwardingIds() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("ok"))
                .responseId("resp_new")
                .build());
        InMemoryConversationStore store = new InMemoryConversationStore();
        store.save("resp_previous", List.of(
                ChatMessage.user("earlier"), ChatMessage.assistant("prior answer")));
        AgentResponseHandler handler = new AgentResponseHandler(
                agent, objectMapper, Duration.ofMinutes(5), store, false);

        handler.handle(
                new ResponseRequest(Map.of("input", "next", "previous_response_id", "resp_previous")),
                buffered(),
                new MockSink());

        // History is prepended; the id is NOT forwarded to the upstream ChatOptions.
        assertEquals(3, agent.messages.size());
        assertEquals("earlier", agent.messages.get(0).getText());
        assertEquals("prior answer", agent.messages.get(1).getText());
        assertEquals("next", agent.messages.get(2).getText());
        assertNull(agent.options.getContinuationToken());
        assertNull(agent.options.getChatOptions().getConversationId());

        // The turn is persisted under the newly issued response id for the next hop.
        List<ChatMessage> saved = store.load("resp_new");
        assertEquals(4, saved.size());
        assertEquals("ok", saved.get(3).getText());
    }

    @Test
    void echoesConversationIdAndPersistsUnderIt() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hi"))
                .responseId("resp_x")
                .build());
        InMemoryConversationStore store = new InMemoryConversationStore();
        AgentResponseHandler handler = new AgentResponseHandler(
                agent, objectMapper, Duration.ofMinutes(5), store, false);
        MockSink sink = new MockSink();

        handler.handle(
                new ResponseRequest(Map.of("input", "hello", "conversation", Map.of("id", "conv_1"))),
                buffered(),
                sink);

        // conv_* is echoed in the body and used as a persistence key (both MAF parity behaviors).
        JsonNode body = objectMapper.readTree(sink.json);
        assertEquals("conv_1", body.path("conversation").path("id").asText());
        assertNull(agent.options.getChatOptions().getConversationId());
        assertEquals(2, store.load("conv_1").size());
    }

    @Test
    void strictLookupReturns404ForUnknownSession() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("ok"))
                .build());
        AgentResponseHandler handler = new AgentResponseHandler(
                agent, objectMapper, Duration.ofMinutes(5),
                new InMemoryConversationStore(), true);
        MockSink sink = new MockSink();

        handler.handle(
                new ResponseRequest(Map.of("input", "next", "previous_response_id", "resp_missing")),
                buffered(),
                sink);

        assertEquals(Integer.valueOf(404), sink.errorStatus);
    }

    @Test
    void emitsNormalizedStreamingEvents() throws Exception {
        CapturingAgent agent = new CapturingAgent(List.of(
                AgentResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .messageId("msg_1")
                        .content(new io.github.weidongxu.agentframework.chat.TextContent("hel"))
                        .build(),
                AgentResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .messageId("msg_1")
                        .content(new io.github.weidongxu.agentframework.chat.TextContent("lo"))
                        .build()));
        AgentResponseHandler handler =
                new AgentResponseHandler(agent, objectMapper, Duration.ofSeconds(5));
        MockSink sink = new MockSink();

        handler.handle(
                new ResponseRequest(Map.of("input", "question", "stream", true)),
                streaming(),
                sink);

        String body = sink.sseBody();
        assertTrue(body.contains("event: response.created"));
        assertTrue(body.contains("event: response.output_text.delta"));
        assertTrue(body.contains("\"delta\":\"hel\""));
        assertTrue(body.contains("event: response.completed"));
        assertTrue(body.contains("\"text\":\"hello\""));
    }

    @Test
    void passesThroughRawStreamingEvents() throws Exception {
        CapturingAgent agent = new CapturingAgent(List.of(
                AgentResponseUpdate.builder()
                        .rawRepresentation(Map.of(
                                "type", "response.created",
                                "response", Map.of("id", "resp_raw")))
                        .build(),
                AgentResponseUpdate.builder()
                        .rawRepresentation(Map.of(
                                "type", "response.completed",
                                "response", Map.of("id", "resp_raw")))
                        .build()));
        AgentResponseHandler handler =
                new AgentResponseHandler(agent, objectMapper, Duration.ofSeconds(5));
        MockSink sink = new MockSink();

        handler.handle(
                new ResponseRequest(Map.of("input", "question", "stream", true)),
                streaming(),
                sink);

        String body = sink.sseBody();
        assertEquals(1, occurrences(body, "event: response.created"));
        assertEquals(1, occurrences(body, "event: response.completed"));
        assertTrue(body.contains("resp_raw"));
    }

    @Test
    void emitsNormalizedStreamingFunctionCalls() throws Exception {
        CapturingAgent agent = new CapturingAgent(List.of(
                AgentResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(new FunctionCallContent(
                                "call_1",
                                "lookup",
                                "{\"q\":\"x\"}"))
                        .build()));
        AgentResponseHandler handler =
                new AgentResponseHandler(agent, objectMapper, Duration.ofSeconds(5));
        MockSink sink = new MockSink();

        handler.handle(
                new ResponseRequest(Map.of("input", "question", "stream", true)),
                streaming(),
                sink);

        String body = sink.sseBody();
        assertTrue(body.contains("event: response.output_item.added"));
        assertTrue(body.contains("event: response.function_call_arguments.done"));
        assertTrue(body.contains("event: response.output_item.done"));
        assertTrue(body.contains("\"type\":\"function_call\""));
        assertTrue(body.contains("\"call_id\":\"call_1\""));
    }

    @Test
    void emitsApprovalBeforeFinalStreamingCompletion() throws Exception {
        ToolApprovalRequestContent request =
                new ToolApprovalRequestContent(
                        "approval_1",
                        new FunctionCallContent("call_1", "delete", "{}"));
        CapturingAgent agent = new CapturingAgent(List.of(
                AgentResponseUpdate.builder()
                        .rawRepresentation(Map.of(
                                "type", "response.created",
                                "response", Map.of("id", "raw_response")))
                        .build(),
                AgentResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(request)
                        .continuationToken("response-1")
                        .build()));
        MockSink sink = new MockSink();

        new AgentResponseHandler(agent, objectMapper, Duration.ofSeconds(5))
                .handle(
                        new ResponseRequest(Map.of("input", "question", "stream", true)),
                        streaming(),
                        sink);

        String body = sink.sseBody();
        int approval = body.indexOf("\"type\":\"tool_approval_request\"");
        int completed = body.lastIndexOf("event: response.completed");
        assertTrue(approval >= 0);
        assertTrue(completed > approval);
        assertTrue(body.substring(completed).contains("\"id\":\"response-1\""));
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    /** Captures whatever the handler writes, without any servlet dependency. */
    private static final class MockSink implements ResponseSink {
        private final StringWriter sse = new StringWriter();
        private PrintWriter writer;
        private String json;
        private Integer errorStatus;

        @Override
        public PrintWriter beginSse() {
            writer = new PrintWriter(sse);
            return writer;
        }

        @Override
        public void writeJson(String body) throws IOException {
            this.json = body;
        }

        @Override
        public void writeError(int status, String body) throws IOException {
            this.errorStatus = status;
            this.json = body;
        }

        private String sseBody() {
            if (writer != null) {
                writer.flush();
            }
            return sse.toString();
        }
    }

    private static final class CapturingAgent implements Agent {
        private final AgentResponse response;
        private final List<AgentResponseUpdate> updates;
        private List<ChatMessage> messages;
        private AgentRunOptions options;
        private AgentSession session;

        private CapturingAgent(AgentResponse response) {
            this.response = response;
            this.updates = Collections.emptyList();
        }

        private CapturingAgent(List<AgentResponseUpdate> updates) {
            this.response = null;
            this.updates = updates;
        }

        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
            this.messages = messages;
            this.session = session;
            this.options = options;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
            this.messages = messages;
            this.session = session;
            this.options = options;
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done && count > 0) {
                        done = true;
                        updates.forEach(subscriber::onNext);
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }
}
