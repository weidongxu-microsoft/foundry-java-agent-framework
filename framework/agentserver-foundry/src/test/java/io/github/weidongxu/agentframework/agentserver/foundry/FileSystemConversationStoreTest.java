package io.github.weidongxu.agentframework.agentserver.foundry;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemConversationStoreTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsNullForUnknownKey(@TempDir Path dir) {
        FileSystemConversationStore store = new FileSystemConversationStore(dir, objectMapper);
        assertNull(store.load("resp_missing"));
    }

    @Test
    void roundTripsAllContentTypes(@TempDir Path dir) {
        FileSystemConversationStore store = new FileSystemConversationStore(dir, objectMapper);
        List<ChatMessage> history = List.of(
                ChatMessage.system("you are helpful"),
                ChatMessage.user("call the tool"),
                ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(new FunctionCallContent("call_1", "lookup", "{\"q\":\"x\"}"))
                        .build(),
                ChatMessage.builder(ChatRole.TOOL)
                        .addContent(new FunctionResultContent("call_1", "result-text", false))
                        .build(),
                ChatMessage.builder(ChatRole.ASSISTANT)
                        .addContent(new ToolApprovalRequestContent(
                                "approval_1",
                                new FunctionCallContent("call_2", "delete", "{}")))
                        .build(),
                ChatMessage.builder(ChatRole.USER)
                        .addContent(new ToolApprovalResponseContent("approval_1", true, "ok"))
                        .build(),
                ChatMessage.assistant("all done"));

        store.save("conv_1", history);
        List<ChatMessage> loaded = store.load("conv_1");

        assertEquals(7, loaded.size());
        assertEquals(ChatRole.SYSTEM, loaded.get(0).getRole());
        assertEquals("you are helpful", loaded.get(0).getText());

        FunctionCallContent call = (FunctionCallContent) loaded.get(2).getContents().get(0);
        assertEquals("call_1", call.getCallId());
        assertEquals("lookup", call.getName());
        assertEquals("{\"q\":\"x\"}", call.getArguments());

        FunctionResultContent result = (FunctionResultContent) loaded.get(3).getContents().get(0);
        assertEquals("call_1", result.getCallId());
        assertEquals("result-text", result.getResult());

        ToolApprovalRequestContent request =
                (ToolApprovalRequestContent) loaded.get(4).getContents().get(0);
        assertEquals("approval_1", request.getRequestId());
        assertEquals("delete", request.getFunctionCall().getName());

        ToolApprovalResponseContent response =
                (ToolApprovalResponseContent) loaded.get(5).getContents().get(0);
        assertTrue(response.isApproved());
        assertEquals("ok", response.getReason());

        assertEquals("all done", loaded.get(6).getText());
    }

    @Test
    void persistsAcrossStoreInstances(@TempDir Path dir) {
        new FileSystemConversationStore(dir, objectMapper)
                .save("resp_1", List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello")));

        // A fresh instance (simulating a restarted pod) reads the durable history.
        List<ChatMessage> loaded =
                new FileSystemConversationStore(dir, objectMapper).load("resp_1");

        assertEquals(2, loaded.size());
        assertEquals("hi", loaded.get(0).getText());
        assertEquals("hello", loaded.get(1).getText());
    }

    @Test
    void overwritesOnResave(@TempDir Path dir) {
        FileSystemConversationStore store = new FileSystemConversationStore(dir, objectMapper);
        store.save("conv_1", List.of(ChatMessage.user("first")));
        store.save("conv_1", List.of(ChatMessage.user("first"), ChatMessage.assistant("second")));

        assertEquals(2, store.load("conv_1").size());
    }

    @Test
    void handlesGatewayStyleKeysWithUnsafeCharacters(@TempDir Path dir) {
        FileSystemConversationStore store = new FileSystemConversationStore(dir, objectMapper);
        store.save("conv/../weird key", List.of(ChatMessage.user("x")));

        assertEquals(1, store.load("conv/../weird key").size());
        assertNull(store.load("conv/../other key"));
    }
}
