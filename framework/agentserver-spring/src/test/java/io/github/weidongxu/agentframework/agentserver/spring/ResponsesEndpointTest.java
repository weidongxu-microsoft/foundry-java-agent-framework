package io.github.weidongxu.agentframework.agentserver.spring;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.agentserver.foundry.AgentResponseHandler;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.TextContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesEndpointTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsBufferedResponse() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .responseId("resp_1")
                .build());
        ResponsesEndpoint endpoint = endpoint(agent);
        MockHttpServletRequest request = jsonRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        endpoint.createResponse(
                Map.of("input", "question"), null, request, response);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("resp_1", body.path("id").asText());
        assertEquals("hello", body.at("/output/0/content/0/text").asText());
    }

    @Test
    void mapsUserIdHeaderToSessionAndProperties() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .build());
        ResponsesEndpoint endpoint = endpoint(agent);
        MockHttpServletRequest request = jsonRequest();
        request.addHeader("x-agent-user-id", "user-1");
        request.addHeader("x-agent-foundry-call-id", "call-1");

        endpoint.createResponse(
                Map.of("input", "question"), null, request, new MockHttpServletResponse());

        assertEquals("user-1", agent.session.getId());
        assertEquals("user-1",
                agent.options.getAdditionalProperties().get("x-agent-user-id"));
        assertEquals("call-1",
                agent.options.getAdditionalProperties().get("x-agent-foundry-call-id"));
    }

    @Test
    void fallsBackToIsolationKeyHeaderForSession() throws Exception {
        CapturingAgent agent = new CapturingAgent(AgentResponse.builder()
                .message(ChatMessage.assistant("hello"))
                .build());
        ResponsesEndpoint endpoint = endpoint(agent);
        MockHttpServletRequest request = jsonRequest();
        request.addHeader("x-agent-user-isolation-key", "isolation-9");

        endpoint.createResponse(
                Map.of("input", "question"), null, request, new MockHttpServletResponse());

        assertEquals("isolation-9", agent.session.getId());
    }

    @Test
    void streamsWhenStreamFlagSet() throws Exception {
        CapturingAgent agent = new CapturingAgent(List.of(
                AgentResponseUpdate.builder()
                        .role(ChatRole.ASSISTANT)
                        .messageId("msg_1")
                        .content(new TextContent("hello"))
                        .build()));
        ResponsesEndpoint endpoint = endpoint(agent);
        MockHttpServletResponse response = new MockHttpServletResponse();

        endpoint.createResponse(
                Map.of("input", "question", "stream", true),
                null,
                jsonRequest(),
                response);

        String body = response.getContentAsString();
        assertTrue(body.contains("event: response.created"));
        assertTrue(body.contains("event: response.completed"));
        assertTrue(response.getContentType().startsWith("text/event-stream"));
    }

    private ResponsesEndpoint endpoint(Agent agent) {
        return new ResponsesEndpoint(
                new AgentResponseHandler(agent, objectMapper, Duration.ofSeconds(5)),
                Duration.ZERO);
    }

    private static MockHttpServletRequest jsonRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/responses");
        request.setContentType("application/json");
        return request;
    }

    private static final class CapturingAgent implements Agent {
        private final AgentResponse response;
        private final List<AgentResponseUpdate> updates;
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
            this.session = session;
            this.options = options;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages,
                AgentSession session,
                AgentRunOptions options) {
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
