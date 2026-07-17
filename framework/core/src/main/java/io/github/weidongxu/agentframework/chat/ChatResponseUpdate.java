package io.github.weidongxu.agentframework.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ChatResponseUpdate {
    private final List<ChatContent> contents;
    private final ChatRole role;
    private final String messageId;
    private final String responseId;
    private final String conversationId;
    private final String continuationToken;
    private final FinishReason finishReason;
    private final Object rawRepresentation;
    private final Map<String, Object> additionalProperties;

    private ChatResponseUpdate(Builder builder) {
        this.contents = Collections.unmodifiableList(new ArrayList<>(builder.contents));
        this.role = builder.role;
        this.messageId = builder.messageId;
        this.responseId = builder.responseId;
        this.conversationId = builder.conversationId;
        this.continuationToken = builder.continuationToken;
        this.finishReason = builder.finishReason;
        this.rawRepresentation = builder.rawRepresentation;
        this.additionalProperties = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.additionalProperties));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ChatContent> getContents() {
        return contents;
    }

    public String getText() {
        return contents.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::getText)
                .collect(Collectors.joining());
    }

    public ChatRole getRole() {
        return role;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getResponseId() {
        return responseId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getContinuationToken() {
        return continuationToken;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public Object getRawRepresentation() {
        return rawRepresentation;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public static final class Builder {
        private final List<ChatContent> contents = new ArrayList<>();
        private ChatRole role;
        private String messageId;
        private String responseId;
        private String conversationId;
        private String continuationToken;
        private FinishReason finishReason;
        private Object rawRepresentation;
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder content(ChatContent content) {
            contents.add(Objects.requireNonNull(content, "content"));
            return this;
        }

        public Builder text(String text) {
            return content(new TextContent(text));
        }

        public Builder contents(List<? extends ChatContent> contents) {
            Objects.requireNonNull(contents, "contents").forEach(this::content);
            return this;
        }

        public Builder role(ChatRole role) {
            this.role = role;
            return this;
        }

        public Builder messageId(String messageId) {
            this.messageId = messageId;
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

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
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

        public ChatResponseUpdate build() {
            return new ChatResponseUpdate(this);
        }
    }
}
