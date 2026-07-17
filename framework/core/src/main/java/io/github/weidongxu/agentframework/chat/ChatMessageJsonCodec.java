package io.github.weidongxu.agentframework.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Jackson (de)serialization for {@link ChatMessage} and the {@link ChatContent} subtypes the
 * framework persists. Kept in one place so every durable store — the AgentServer
 * {@code FileSystemConversationStore}, the {@code FileChatHistoryProvider}, and any future backend —
 * shares an identical, forward-compatible wire format instead of re-implementing the codec.
 *
 * <p>Unknown content types are dropped on write and skipped on read rather than failing the whole
 * document, so a persisted history stays loadable across framework versions.</p>
 */
public final class ChatMessageJsonCodec {

    private ChatMessageJsonCodec() {
    }

    /** Serializes a list of messages to a JSON array. */
    public static ArrayNode writeMessages(ObjectMapper mapper, List<? extends ChatMessage> messages) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(messages, "messages");
        ArrayNode root = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            root.add(writeMessage(mapper, message));
        }
        return root;
    }

    /** Reads a list of messages from a JSON array node; non-array input yields an empty list. */
    public static List<ChatMessage> readMessages(JsonNode arrayNode) {
        List<ChatMessage> messages = new ArrayList<>();
        if (arrayNode == null || !arrayNode.isArray()) {
            return messages;
        }
        for (JsonNode messageNode : arrayNode) {
            ChatMessage message = readMessage(messageNode);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    public static ObjectNode writeMessage(ObjectMapper mapper, ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.getRole().name());
        if (message.getAuthorName() != null) {
            node.put("authorName", message.getAuthorName());
        }
        ArrayNode contents = node.putArray("contents");
        for (ChatContent content : message.getContents()) {
            ObjectNode contentNode = writeContent(mapper, content);
            if (contentNode != null) {
                contents.add(contentNode);
            }
        }
        return node;
    }

    private static ObjectNode writeContent(ObjectMapper mapper, ChatContent content) {
        ObjectNode node = mapper.createObjectNode();
        if (content instanceof TextContent) {
            node.put("type", "text");
            node.put("text", ((TextContent) content).getText());
        } else if (content instanceof FunctionCallContent) {
            FunctionCallContent call = (FunctionCallContent) content;
            node.put("type", "function_call");
            node.put("callId", call.getCallId());
            node.put("name", call.getName());
            node.put("arguments", call.getArguments());
        } else if (content instanceof FunctionResultContent) {
            FunctionResultContent result = (FunctionResultContent) content;
            node.put("type", "function_result");
            node.put("callId", result.getCallId());
            node.put("result", result.getResult());
            node.put("error", result.isError());
        } else if (content instanceof ToolApprovalRequestContent) {
            ToolApprovalRequestContent request = (ToolApprovalRequestContent) content;
            FunctionCallContent call = request.getFunctionCall();
            node.put("type", "tool_approval_request");
            node.put("requestId", request.getRequestId());
            ObjectNode callNode = node.putObject("functionCall");
            callNode.put("callId", call.getCallId());
            callNode.put("name", call.getName());
            callNode.put("arguments", call.getArguments());
        } else if (content instanceof ToolApprovalResponseContent) {
            ToolApprovalResponseContent response = (ToolApprovalResponseContent) content;
            node.put("type", "tool_approval_response");
            node.put("requestId", response.getRequestId());
            node.put("approved", response.isApproved());
            if (response.getReason() != null) {
                node.put("reason", response.getReason());
            }
        } else {
            // Unknown content type — drop it rather than fail the whole persistence.
            return null;
        }
        return node;
    }

    public static ChatMessage readMessage(JsonNode node) {
        if (node == null || !node.hasNonNull("role")) {
            return null;
        }
        ChatRole role;
        try {
            role = ChatRole.valueOf(node.get("role").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownRole) {
            return null;
        }
        ChatMessage.Builder builder = ChatMessage.builder(role);
        if (node.hasNonNull("authorName")) {
            builder.authorName(node.get("authorName").asText());
        }
        JsonNode contents = node.get("contents");
        if (contents != null && contents.isArray()) {
            for (JsonNode contentNode : contents) {
                ChatContent content = readContent(contentNode);
                if (content != null) {
                    builder.addContent(content);
                }
            }
        }
        return builder.build();
    }

    private static ChatContent readContent(JsonNode node) {
        if (node == null || !node.hasNonNull("type")) {
            return null;
        }
        switch (node.get("type").asText()) {
            case "text":
                return new TextContent(node.path("text").asText(""));
            case "function_call":
                return new FunctionCallContent(
                        node.path("callId").asText(""),
                        node.path("name").asText(""),
                        node.path("arguments").asText(""));
            case "function_result":
                return new FunctionResultContent(
                        node.path("callId").asText(""),
                        node.path("result").asText(""),
                        node.path("error").asBoolean(false));
            case "tool_approval_request": {
                JsonNode call = node.path("functionCall");
                return new ToolApprovalRequestContent(
                        node.path("requestId").asText(""),
                        new FunctionCallContent(
                                call.path("callId").asText(""),
                                call.path("name").asText(""),
                                call.path("arguments").asText("")));
            }
            case "tool_approval_response":
                return new ToolApprovalResponseContent(
                        node.path("requestId").asText(""),
                        node.path("approved").asBoolean(false),
                        node.hasNonNull("reason") ? node.get("reason").asText() : null);
            default:
                return null;
        }
    }
}
