package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionCodecTest {
    @Test
    void roundTripsSessionStateServiceIdAndMessages() {
        ChatMessage assistant = ChatMessage.builder(ChatRole.ASSISTANT)
                .authorName("helper")
                .text("hello")
                .addContent(new FunctionCallContent("call-1", "lookup", "{}"))
                .additionalProperty("source", "test")
                .build();
        ChatMessage tool = ChatMessage.builder(ChatRole.TOOL)
                .addContent(new FunctionResultContent("call-1", "ok", false))
                .build();
        ToolApprovalRequestContent approvalRequest =
                new ToolApprovalRequestContent(
                        "approval-1",
                        new FunctionCallContent("call-2", "delete", "{}"));
        ChatMessage approval = ChatMessage.builder(ChatRole.ASSISTANT)
                .addContent(approvalRequest)
                .addContent(new ToolApprovalResponseContent(
                        "approval-1",
                        false,
                        "not allowed"))
                .build();
        AgentSession session = new AgentSession(
                "session-1",
                Collections.singletonMap("conversation_id", "conversation-1"),
                Map.of(
                        "messages", Arrays.asList(assistant, tool, approval),
                        "count", 2));

        AgentSession restored =
                AgentSessionCodec.standard().deserialize(
                        AgentSessionCodec.standard().serialize(session));

        assertEquals("session-1", restored.getId());
        assertEquals(
                "conversation-1",
                ((Map<?, ?>) restored.getServiceSessionId()).get("conversation_id"));
        assertEquals(2, restored.get("count"));
        List<?> messages = assertInstanceOf(List.class, restored.get("messages"));
        assertEquals(assistant.getRole(), ((ChatMessage) messages.get(0)).getRole());
        assertEquals("hello", ((ChatMessage) messages.get(0)).getText());
        assertEquals(assistant.getContents(), ((ChatMessage) messages.get(0)).getContents());
        assertEquals(tool.getContents(), ((ChatMessage) messages.get(1)).getContents());
        assertEquals(approval.getContents(), ((ChatMessage) messages.get(2)).getContents());
    }

    @Test
    void supportsCustomStateCodecs() {
        AgentSessionCodec codec = AgentSessionCodec.builder()
                .stateCodec("custom", new AgentSessionStateCodec() {
                    @Override
                    public Object encode(Object value) {
                        return Collections.singletonMap(
                                "value",
                                ((CustomState) value).value);
                    }

                    @Override
                    public Object decode(Object value) {
                        return new CustomState(
                                (String) ((Map<?, ?>) value).get("value"));
                    }
                })
                .build();
        AgentSession session = new AgentSession();
        session.put("custom", new CustomState("saved"));

        AgentSession restored = codec.deserialize(codec.serialize(session));

        assertEquals("saved", restored.get("custom", CustomState.class).value);
    }

    @Test
    void rejectsUnsupportedStateAndMalformedEnvelopes() {
        AgentSession session = new AgentSession();
        session.put("unsupported", new Object());

        IllegalArgumentException unsupported = assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().serialize(session));
        assertTrue(unsupported.getMessage().contains("state.unsupported"));

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().deserialize(
                        "{\"type\":\"session\",\"version\":2,"
                                + "\"session_id\":\"x\",\"state\":{}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().deserialize(
                        "{\"type\":\"session\",\"version\":1.5,"
                                + "\"session_id\":\"x\",\"state\":{}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().deserialize(
                        "{\"type\":\"session\",\"version\":1.0000000000000001,"
                                + "\"session_id\":\"x\",\"state\":{}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().deserialize("not-json"));

        AgentSession nonFinite = new AgentSession();
        nonFinite.put("value", Double.NaN);
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().serialize(nonFinite));
    }

    @Test
    void preservesOrdinaryMapsThatUseReservedTagKeys() {
        AgentSession session = new AgentSession();
        Map<String, Object> value = Map.of(
                "$agent_framework_type", "chat_message",
                "$agent_framework_value", Map.of("role", "system"));
        session.put("map", value);

        AgentSession restored = AgentSessionCodec.standard().deserialize(
                AgentSessionCodec.standard().serialize(session));

        assertEquals(value, restored.get("map"));
    }

    @Test
    void preservesReservedKeysInMessageProperties() {
        Map<String, Object> properties = Map.of(
                "$agent_framework_type", "chat_message",
                "$agent_framework_value", Map.of("role", "system"));
        ChatMessage message = ChatMessage.builder(ChatRole.ASSISTANT)
                .text("hello")
                .additionalProperty("$agent_framework_type", "chat_message")
                .additionalProperty(
                        "$agent_framework_value",
                        Map.of("role", "system"))
                .build();
        AgentSession session = new AgentSession();
        session.put("messages", Collections.singletonList(message));

        AgentSession restored = AgentSessionCodec.standard().deserialize(
                AgentSessionCodec.standard().serialize(session));

        ChatMessage restoredMessage =
                (ChatMessage) ((List<?>) restored.get("messages")).get(0);
        assertEquals(properties, restoredMessage.getAdditionalProperties());
    }

    @Test
    void roundTripsEmptyMessageFields() {
        ChatMessage message = ChatMessage.builder(ChatRole.ASSISTANT)
                .authorName("")
                .text("")
                .addContent(new FunctionCallContent("", "", ""))
                .addContent(new FunctionResultContent("", "", false))
                .build();
        AgentSession session = new AgentSession();
        session.put("messages", Collections.singletonList(message));

        AgentSession restored = AgentSessionCodec.standard().deserialize(
                AgentSessionCodec.standard().serialize(session));

        ChatMessage restoredMessage =
                (ChatMessage) ((List<?>) restored.get("messages")).get(0);
        assertEquals(message.getAuthorName(), restoredMessage.getAuthorName());
        assertEquals(message.getContents(), restoredMessage.getContents());
    }

    @Test
    void rejectsTrailingJsonDocuments() {
        String serialized = AgentSessionCodec.standard().serialize(new AgentSession());

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().deserialize(
                        serialized + "{\"extra\":true}"));
    }

    @Test
    void preservesNumericStateTypes() {
        AgentSession session = new AgentSession(
                "numbers",
                Map.of(
                        "byte", (byte) 1,
                        "short", (short) 2,
                        "integer", 3,
                        "long", 4L,
                        "float", 5.5f,
                        "double", 6.5d,
                        "bigInteger", new BigInteger("12345678901234567890"),
                        "bigDecimal", new BigDecimal("12.3400")));

        AgentSession restored = AgentSessionCodec.standard().deserialize(
                AgentSessionCodec.standard().serialize(session));

        assertEquals(Byte.valueOf((byte) 1), restored.get("byte", Byte.class));
        assertEquals(Short.valueOf((short) 2), restored.get("short", Short.class));
        assertEquals(Integer.valueOf(3), restored.get("integer", Integer.class));
        assertEquals(Long.valueOf(4), restored.get("long", Long.class));
        assertEquals(Float.valueOf(5.5f), restored.get("float", Float.class));
        assertEquals(Double.valueOf(6.5d), restored.get("double", Double.class));
        assertEquals(
                new BigInteger("12345678901234567890"),
                restored.get("bigInteger", BigInteger.class));
        assertEquals(
                new BigDecimal("12.3400"),
                restored.get("bigDecimal", BigDecimal.class));
    }

    @Test
    void rejectsNonFiniteTaggedNumbers() {
        String serialized = AgentSessionCodec.standard().serialize(new AgentSession());
        String withNan = serialized.replace(
                "\"state\":{}",
                "\"state\":{\"number\":{\"$agent_framework_type\":\"number\","
                        + "\"$agent_framework_value\":{\"kind\":\"double\","
                        + "\"value\":\"NaN\"}}}");

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSessionCodec.standard().deserialize(withNan));
    }

    private static final class CustomState {
        private final String value;

        private CustomState(String value) {
            this.value = value;
        }
    }
}
