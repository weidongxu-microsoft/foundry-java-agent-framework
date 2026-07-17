package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatOptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AgentRunOptions {
    private static final AgentRunOptions EMPTY = builder().build();

    private final ChatOptions chatOptions;
    private final String continuationToken;
    private final Map<String, Object> additionalProperties;

    private AgentRunOptions(Builder builder) {
        this.chatOptions = builder.chatOptions;
        this.continuationToken = builder.continuationToken;
        this.additionalProperties = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.additionalProperties));
    }

    public static AgentRunOptions empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public ChatOptions getChatOptions() {
        return chatOptions;
    }

    public String getContinuationToken() {
        return continuationToken;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public static final class Builder {
        private ChatOptions chatOptions;
        private String continuationToken;
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        private Builder() {
        }

        private Builder(AgentRunOptions source) {
            this.chatOptions = source.chatOptions;
            this.continuationToken = source.continuationToken;
            this.additionalProperties.putAll(source.additionalProperties);
        }

        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = chatOptions;
            return this;
        }

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        public Builder additionalProperty(String name, Object value) {
            additionalProperties.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        public AgentRunOptions build() {
            return new AgentRunOptions(this);
        }
    }
}
