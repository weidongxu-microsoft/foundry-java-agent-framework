package io.github.weidongxu.agentframework.foundry;

import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.chat.ChatOptions;
import io.github.weidongxu.agentframework.chat.PlatformRequestHeaders;

import java.util.Objects;

public final class FoundryHostedContext {
    public static final String USER_ID_HEADER = PlatformRequestHeaders.USER_ID;
    public static final String CALL_ID_HEADER = PlatformRequestHeaders.CALL_ID;

    private FoundryHostedContext() {
    }

    public static AgentRunOptions runOptions(
            String userId,
            String callId) {
        AgentRunOptions.Builder options = AgentRunOptions.builder();
        if (userId != null && !userId.isBlank()) {
            options.additionalProperty(USER_ID_HEADER, userId);
        }
        if (callId != null && !callId.isBlank()) {
            options.additionalProperty(CALL_ID_HEADER, callId);
        }
        return options.build();
    }

    public static String getUserId(ChatOptions options) {
        return property(options, USER_ID_HEADER);
    }

    public static String getCallId(ChatOptions options) {
        return property(options, CALL_ID_HEADER);
    }

    private static String property(ChatOptions options, String name) {
        Object value = Objects.requireNonNull(options, "options")
                .getAdditionalProperties()
                .get(name);
        return value instanceof String && !((String) value).isBlank()
                ? (String) value
                : null;
    }
}
