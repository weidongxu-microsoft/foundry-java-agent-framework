package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;
import io.github.weidongxu.agentframework.chat.FinishReason;
import io.github.weidongxu.agentframework.chat.Usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentResponse {
    private final List<ChatMessage> messages;
    private final String responseId;
    private final String conversationId;
    private final String agentId;
    private final String continuationToken;
    private final FinishReason finishReason;
    private final Usage usage;
    private final Object rawRepresentation;
    private final Map<String, Object> additionalProperties;

    private AgentResponse(Builder builder) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.responseId = builder.responseId;
        this.conversationId = builder.conversationId;
        this.agentId = builder.agentId;
        this.continuationToken = builder.continuationToken;
        this.finishReason = builder.finishReason;
        this.usage = builder.usage;
        this.rawRepresentation = builder.rawRepresentation;
        this.additionalProperties = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.additionalProperties));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public String getText() {
        return messages.stream()
                .filter(message -> message.getRole() == ChatRole.ASSISTANT)
                .map(ChatMessage::getText)
                .reduce("", String::concat);
    }

    public String getResponseId() {
        return responseId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getContinuationToken() {
        return continuationToken;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public Usage getUsage() {
        return usage;
    }

    public Object getRawRepresentation() {
        return rawRepresentation;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public static final class Builder {
        private final List<ChatMessage> messages = new ArrayList<>();
        private String responseId;
        private String conversationId;
        private String agentId;
        private String continuationToken;
        private FinishReason finishReason;
        private Usage usage;
        private Object rawRepresentation;
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder message(ChatMessage message) {
            messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder messages(List<? extends ChatMessage> messages) {
            Objects.requireNonNull(messages, "messages").forEach(this::message);
            return this;
        }

        public Builder responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder rawRepresentation(Object rawRepresentation) {
            this.rawRepresentation = rawRepresentation;
            return this;
        }

        public Builder additionalProperty(String name, Object value) {
            additionalProperties.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        public AgentResponse build() {
            return new AgentResponse(this);
        }
    }
}
