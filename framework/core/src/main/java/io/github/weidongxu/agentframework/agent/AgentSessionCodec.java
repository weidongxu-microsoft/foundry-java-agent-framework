package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatContent;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalRequestContent;
import io.github.weidongxu.agentframework.chat.ToolApprovalResponseContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.math.BigInteger;

public final class AgentSessionCodec {
    private static final int VERSION = 1;
    private static final String TAG_TYPE = "$agent_framework_type";
    private static final String TAG_VALUE = "$agent_framework_value";
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    private final ObjectMapper objectMapper;
    private final Map<String, AgentSessionStateCodec> stateCodecs;

    private AgentSessionCodec(Builder builder) {
        this.objectMapper = builder.objectMapper;
        this.stateCodecs = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.stateCodecs));
    }

    public static AgentSessionCodec standard() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String serialize(AgentSession session) {
        Objects.requireNonNull(session, "session");
        Map<String, Object> state = new LinkedHashMap<>();
        session.snapshot().forEach((key, value) -> {
            AgentSessionStateCodec codec = stateCodecs.get(key);
            Object encoded = codec == null ? value : codec.encode(value);
            state.put(key, encodeValue(encoded, "state." + key));
        });

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "session");
        envelope.put("version", VERSION);
        envelope.put("session_id", session.getId());
        envelope.put(
                "service_session_id",
                encodeValue(session.getServiceSessionId(), "service_session_id"));
        envelope.put("state", state);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Unable to serialize agent session", error);
        }
    }

    public AgentSession deserialize(String serializedSession) {
        Objects.requireNonNull(serializedSession, "serializedSession");
        JsonNode root;
        Map<String, Object> envelope;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(serializedSession);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                        "Serialized agent session must be a JSON object");
            }
            envelope = objectMapper.convertValue(root, MAP_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid serialized agent session", error);
        }
        if (!"session".equals(envelope.get("type"))) {
            throw new IllegalArgumentException("Serialized session type must be 'session'");
        }
        JsonNode versionNode = root.get("version");
        if (versionNode == null
                || !versionNode.isIntegralNumber()
                || !versionNode.canConvertToInt()
                || versionNode.intValue() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported serialized session version: "
                            + (versionNode == null ? null : versionNode));
        }
        String sessionId = requiredString(envelope.get("session_id"), "session_id");
        Object rawState = envelope.get("state");
        if (!(rawState instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Serialized session state must be an object");
        }

        Map<String, Object> state = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawState).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("Serialized session state keys must be strings");
            }
            String key = (String) entry.getKey();
            Object decoded = decodeValue(entry.getValue(), "state." + key);
            AgentSessionStateCodec codec = stateCodecs.get(key);
            state.put(key, codec == null ? decoded : codec.decode(decoded));
        }
        Object serviceSessionId =
                decodeValue(envelope.get("service_session_id"), "service_session_id");
        return new AgentSession(sessionId, serviceSessionId, state);
    }

    private static Object encodeValue(Object value, String path) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double && !Double.isFinite((Double) value)) {
            throw unsupported(path, value);
        }
        if (value instanceof Float && !Float.isFinite((Float) value)) {
            throw unsupported(path, value);
        }
        if (value instanceof Number) {
            return encodeNumber((Number) value, path);
        }
        if (value instanceof ChatMessage) {
            return encodeMessage((ChatMessage) value, path);
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            boolean requiresEscaping = false;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw unsupported(path, value);
                }
                String key = (String) entry.getKey();
                requiresEscaping |= TAG_TYPE.equals(key) || TAG_VALUE.equals(key);
                result.put(key, encodeValue(entry.getValue(), path + "." + key));
            }
            return requiresEscaping ? tagged("map", result) : result;
        }
        if (value instanceof Iterable<?>) {
            List<Object> result = new ArrayList<>();
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                result.add(encodeValue(item, path + "[" + index++ + "]"));
            }
            return result;
        }
        throw unsupported(path, value);
    }

    private static Map<String, Object> encodeMessage(ChatMessage message, String path) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", message.getRole().name().toLowerCase(Locale.ROOT));
        value.put("author_name", message.getAuthorName());
        List<Object> contents = new ArrayList<>();
        for (ChatContent content : message.getContents()) {
            contents.add(encodeContent(content, path + ".contents"));
        }
        value.put("contents", contents);
        value.put(
                "additional_properties",
                encodeValue(message.getAdditionalProperties(), path + ".additional_properties"));
        return tagged("chat_message", value);
    }

    private static Map<String, Object> encodeContent(ChatContent content, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", content.getType());
        if (content instanceof TextContent) {
            result.put("text", ((TextContent) content).getText());
        } else if (content instanceof FunctionCallContent) {
            FunctionCallContent call = (FunctionCallContent) content;
            result.put("call_id", call.getCallId());
            result.put("name", call.getName());
            result.put("arguments", call.getArguments());
        } else if (content instanceof FunctionResultContent) {
            FunctionResultContent functionResult = (FunctionResultContent) content;
            result.put("call_id", functionResult.getCallId());
            result.put("result", functionResult.getResult());
            result.put("error", functionResult.isError());
        } else if (content instanceof ToolApprovalRequestContent) {
            ToolApprovalRequestContent request =
                    (ToolApprovalRequestContent) content;
            FunctionCallContent call = request.getFunctionCall();
            result.put("request_id", request.getRequestId());
            result.put("call_id", call.getCallId());
            result.put("name", call.getName());
            result.put("arguments", call.getArguments());
        } else if (content instanceof ToolApprovalResponseContent) {
            ToolApprovalResponseContent response =
                    (ToolApprovalResponseContent) content;
            result.put("request_id", response.getRequestId());
            result.put("approved", response.isApproved());
            result.put("reason", response.getReason());
        } else {
            throw unsupported(path, content);
        }
        return result;
    }

    private static Object decodeValue(Object value, String path) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (isTagged(map, "chat_message")) {
                Object message = map.get(TAG_VALUE);
                if (!(message instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(path + " chat message must be an object");
                }
                return decodeMessage((Map<?, ?>) message, path);
            }
            if (isTagged(map, "map")) {
                Object escaped = map.get(TAG_VALUE);
                if (!(escaped instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(path + " escaped map must be an object");
                }
                return decodeOrdinaryMap((Map<?, ?>) escaped, path);
            }
            if (isTagged(map, "number")) {
                Object number = map.get(TAG_VALUE);
                if (!(number instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(path + " number must be an object");
                }
                return decodeNumber((Map<?, ?>) number, path);
            }
            return decodeOrdinaryMap(map, path);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < ((List<?>) value).size(); index++) {
                result.add(decodeValue(
                        ((List<?>) value).get(index),
                        path + "[" + index + "]"));
            }
            return result;
        }
        return value;
    }

    private static ChatMessage decodeMessage(Map<?, ?> value, String path) {
        String roleName = requiredString(value.get("role"), path + ".role");
        ChatRole role;
        try {
            role = ChatRole.valueOf(roleName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid chat role at " + path + ": " + roleName);
        }
        ChatMessage.Builder message = ChatMessage.builder(role);
        Object authorName = value.get("author_name");
        if (authorName != null) {
            message.authorName(stringValue(authorName, path + ".author_name"));
        }
        Object contents = value.get("contents");
        if (!(contents instanceof List<?>)) {
            throw new IllegalArgumentException(path + ".contents must be an array");
        }
        for (Object content : (List<?>) contents) {
            message.addContent(decodeContent(content, path + ".contents"));
        }
        Object properties = value.get("additional_properties");
        if (properties != null) {
            Object decodedProperties =
                    decodeValue(properties, path + ".additional_properties");
            if (!(decodedProperties instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        path + ".additional_properties must be an object");
            }
            decodeStringMap(
                            (Map<?, ?>) decodedProperties,
                            path + ".additional_properties")
                    .forEach(message::additionalProperty);
        }
        return message.build();
    }

    private static ChatContent decodeContent(Object value, String path) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(path + " must contain objects");
        }
        Map<?, ?> content = (Map<?, ?>) value;
        String type = requiredString(content.get("type"), path + ".type");
        switch (type) {
            case "text":
                return new TextContent(stringValue(content.get("text"), path + ".text"));
            case "function_call":
                return new FunctionCallContent(
                        stringValue(content.get("call_id"), path + ".call_id"),
                        stringValue(content.get("name"), path + ".name"),
                        stringValue(content.get("arguments"), path + ".arguments"));
            case "function_result":
                Object error = content.get("error");
                if (!(error instanceof Boolean)) {
                    throw new IllegalArgumentException(path + ".error must be a boolean");
                }
                return new FunctionResultContent(
                        stringValue(content.get("call_id"), path + ".call_id"),
                        stringValue(content.get("result"), path + ".result"),
                        (Boolean) error);
            case "tool_approval_request":
                return new ToolApprovalRequestContent(
                        stringValue(content.get("request_id"), path + ".request_id"),
                        new FunctionCallContent(
                                stringValue(content.get("call_id"), path + ".call_id"),
                                stringValue(content.get("name"), path + ".name"),
                                stringValue(content.get("arguments"), path + ".arguments")));
            case "tool_approval_response":
                Object approved = content.get("approved");
                if (!(approved instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            path + ".approved must be a boolean");
                }
                Object reason = content.get("reason");
                return new ToolApprovalResponseContent(
                        stringValue(content.get("request_id"), path + ".request_id"),
                        (Boolean) approved,
                        reason == null
                                ? null
                                : stringValue(reason, path + ".reason"));
            default:
                throw new IllegalArgumentException(
                        "Unsupported chat content type at " + path + ": " + type);
        }
    }

    private static Map<String, Object> decodeStringMap(Map<?, ?> value, String path) {
        return decodeOrdinaryMap(value, path);
    }

    private static Map<String, Object> decodeOrdinaryMap(Map<?, ?> value, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(path + " contains a non-string key");
            }
            result.put((String) key, decodeValue(item, path + "." + key));
        });
        return result;
    }

    private static Map<String, Object> tagged(String type, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TAG_TYPE, type);
        result.put(TAG_VALUE, value);
        return result;
    }

    private static Map<String, Object> encodeNumber(Number value, String path) {
        String kind;
        if (value instanceof Byte) {
            kind = "byte";
        } else if (value instanceof Short) {
            kind = "short";
        } else if (value instanceof Integer) {
            kind = "integer";
        } else if (value instanceof Long) {
            kind = "long";
        } else if (value instanceof Float) {
            kind = "float";
        } else if (value instanceof Double) {
            kind = "double";
        } else if (value instanceof BigInteger) {
            kind = "big_integer";
        } else if (value instanceof BigDecimal) {
            kind = "big_decimal";
        } else {
            throw unsupported(path, value);
        }
        return tagged("number", Map.of(
                "kind", kind,
                "value", value.toString()));
    }

    private static Number decodeNumber(Map<?, ?> value, String path) {
        String kind = requiredString(value.get("kind"), path + ".kind");
        String number = requiredString(value.get("value"), path + ".value");
        try {
            switch (kind) {
                case "byte":
                    return Byte.valueOf(number);
                case "short":
                    return Short.valueOf(number);
                case "integer":
                    return Integer.valueOf(number);
                case "long":
                    return Long.valueOf(number);
                case "float":
                    Float floatValue = Float.valueOf(number);
                    if (!Float.isFinite(floatValue)) {
                        throw new NumberFormatException("non-finite float");
                    }
                    return floatValue;
                case "double":
                    Double doubleValue = Double.valueOf(number);
                    if (!Double.isFinite(doubleValue)) {
                        throw new NumberFormatException("non-finite double");
                    }
                    return doubleValue;
                case "big_integer":
                    return new BigInteger(number);
                case "big_decimal":
                    return new BigDecimal(number);
                default:
                    throw new IllegalArgumentException(
                            "Unsupported number kind at " + path + ": " + kind);
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid number at " + path + ": " + number,
                    error);
        }
    }

    private static boolean isTagged(Map<?, ?> value, String type) {
        return value.size() == 2
                && type.equals(value.get(TAG_TYPE))
                && value.containsKey(TAG_VALUE);
    }

    private static String requiredString(Object value, String path) {
        String result = stringValue(value, path);
        if (result.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-blank string");
        }
        return result;
    }

    private static String stringValue(Object value, String path) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return (String) value;
    }

    private static IllegalArgumentException unsupported(String path, Object value) {
        return new IllegalArgumentException(
                "Session value at " + path + " is not JSON-compatible: "
                        + value.getClass().getName());
    }

    public static final class Builder {
        private ObjectMapper objectMapper = new ObjectMapper();
        private final Map<String, AgentSessionStateCodec> stateCodecs =
                new LinkedHashMap<>();

        private Builder() {
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        public Builder stateCodec(String key, AgentSessionStateCodec codec) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(codec, "codec");
            if (stateCodecs.putIfAbsent(key, codec) != null) {
                throw new IllegalArgumentException(
                        "Duplicate session state codec key: " + key);
            }
            return this;
        }

        public AgentSessionCodec build() {
            return new AgentSessionCodec(this);
        }
    }
}
