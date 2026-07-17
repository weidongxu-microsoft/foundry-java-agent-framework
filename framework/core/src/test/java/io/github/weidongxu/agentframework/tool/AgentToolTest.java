package io.github.weidongxu.agentframework.tool;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentResponseUpdate;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void defaultsDeriveNameAndDescriptionFromAgent() {
        FakeAgent agent = new FakeAgent("Research Agent", "Finds facts", "answer");
        AgentTool tool = AgentTool.of(agent);

        assertEquals("Research_Agent", tool.getName());
        assertEquals("Finds facts", tool.getDescription());
        assertSame(agent, tool.getAgent());
        assertEquals(ApprovalMode.NEVER, tool.getApprovalMode());

        Map<String, Object> schema = tool.getParametersSchema();
        assertEquals("object", schema.get("type"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("query"));
        assertEquals(Collections.singletonList("query"), schema.get("required"));
    }

    @Test
    void invokeRunsAgentWithQueryAndReturnsText() throws Exception {
        FakeAgent agent = new FakeAgent("agent", "desc", "the result");
        AgentTool tool = AgentTool.of(agent);

        String result = tool.invoke(Collections.singletonMap("query", "hello"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("the result", result);
        assertEquals(1, agent.lastMessages.size());
        assertEquals("hello", agent.lastMessages.get(0).getText());
        assertEquals(ChatRole.USER, agent.lastMessages.get(0).getRole());
    }

    @Test
    void missingQueryCoercesToEmptyString() throws Exception {
        FakeAgent agent = new FakeAgent("agent", "desc", "ok");
        AgentTool tool = AgentTool.of(agent);

        tool.invoke(Collections.emptyMap()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("", agent.lastMessages.get(0).getText());
    }

    @Test
    void optionsOverrideNameDescriptionAndApprovalMode() {
        FakeAgent agent = new FakeAgent("agent", "desc", "ok");
        AgentTool tool = AgentTool.of(agent, AgentToolOptions.builder()
                .name("custom_tool")
                .description("custom description")
                .approvalMode(ApprovalMode.ALWAYS_REQUIRE)
                .build());

        assertEquals("custom_tool", tool.getName());
        assertEquals("custom description", tool.getDescription());
        assertEquals(ApprovalMode.ALWAYS_REQUIRE, tool.getApprovalMode());
    }

    @Test
    void blankAgentNameFallsBackToDefault() {
        FakeAgent agent = new FakeAgent("  ", "desc", "ok");
        AgentTool tool = AgentTool.of(agent);
        assertEquals("invoke_agent", tool.getName());
    }

    @Test
    void suppliedSessionIsPassedThroughToAgent() throws Exception {
        FakeAgent agent = new FakeAgent("agent", "desc", "ok");
        AgentSession session = new AgentSession();
        AgentTool tool = AgentTool.of(agent, AgentToolOptions.builder().session(session).build());

        tool.invoke(Collections.singletonMap("query", "x"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertSame(session, agent.lastSession);
    }

    private static final class FakeAgent implements Agent {
        private final String name;
        private final String description;
        private final String responseText;
        List<ChatMessage> lastMessages;
        AgentSession lastSession;

        FakeAgent(String name, String description, String responseText) {
            this.name = name;
            this.description = description;
            this.responseText = responseText;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public CompletionStage<AgentResponse> run(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            this.lastMessages = messages;
            this.lastSession = session;
            AgentResponse response = AgentResponse.builder()
                    .message(ChatMessage.builder(ChatRole.ASSISTANT)
                            .addContent(new io.github.weidongxu.agentframework.chat.TextContent(responseText))
                            .build())
                    .build();
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<ChatMessage> messages, AgentSession session, AgentRunOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
