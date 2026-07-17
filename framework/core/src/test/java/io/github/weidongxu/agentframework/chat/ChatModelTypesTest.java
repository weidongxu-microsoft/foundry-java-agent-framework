package io.github.weidongxu.agentframework.chat;

import io.github.weidongxu.agentframework.tool.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelTypesTest {
    @Test
    void chatMessageExposesTextAcrossContentParts() {
        ChatMessage message = ChatMessage.builder(ChatRole.ASSISTANT)
                .text("hello")
                .addContent(new FunctionCallContent("call-1", "weather", "{}"))
                .text(" world")
                .build();

        assertEquals("hello world", message.getText());
        assertEquals(3, message.getContents().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> message.getContents().add(new TextContent("!")));
    }

    @Test
    void chatOptionsAreImmutableAndValidateRanges() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        FunctionTool tool = new FunctionTool(
                "weather",
                "Get weather",
                schema,
                arguments -> CompletableFuture.completedFuture("sunny"));

        ChatOptions options = ChatOptions.builder()
                .modelId("test-model")
                .temperature(0.5)
                .maxOutputTokens(100)
                .tool(tool)
                .additionalProperty("provider", "test")
                .build();

        schema.put("changed", true);

        assertEquals("test-model", options.getModelId());
        assertEquals("object", tool.getParametersSchema().get("type"));
        assertEquals(Collections.singletonList(tool), options.getTools());
        assertThrows(
                UnsupportedOperationException.class,
                () -> options.getAdditionalProperties().put("x", "y"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChatOptions.builder().temperature(3.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChatOptions.builder().maxOutputTokens(0));
    }

    @Test
    void responseTextIncludesAssistantMessages() {
        ChatResponse response = ChatResponse.builder()
                .message(ChatMessage.user("ignored"))
                .message(ChatMessage.assistant("first"))
                .message(ChatMessage.assistant(" second"))
                .build();

        assertEquals("first second", response.getText());
    }
}
