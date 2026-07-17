package io.github.weidongxu.agentframework.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ChatMessage {
    private final ChatRole role;
    private final List<ChatContent> contents;
    private final String authorName;
    private final Map<String, Object> additionalProperties;

    private ChatMessage(Builder builder) {
        this.role = Objects.requireNonNull(builder.role, "role");
        this.contents = Collections.unmodifiableList(new ArrayList<>(builder.contents));
        this.authorName = builder.authorName;
        this.additionalProperties = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.additionalProperties));
    }

    public static Builder builder(ChatRole role) {
        return new Builder(role);
    }

    public static ChatMessage user(String text) {
        return builder(ChatRole.USER).text(text).build();
    }

    public static ChatMessage assistant(String text) {
        return builder(ChatRole.ASSISTANT).text(text).build();
    }

    public static ChatMessage system(String text) {
        return builder(ChatRole.SYSTEM).text(text).build();
    }

    public ChatRole getRole() {
        return role;
    }

    public List<ChatContent> getContents() {
        return contents;
    }

    public String getAuthorName() {
        return authorName;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public String getText() {
        return contents.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::getText)
                .collect(Collectors.joining());
    }

    public static final class Builder {
        private final ChatRole role;
        private final List<ChatContent> contents = new ArrayList<>();
        private String authorName;
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        private Builder(ChatRole role) {
            this.role = Objects.requireNonNull(role, "role");
        }

        public Builder text(String text) {
            return addContent(new TextContent(text));
        }

        public Builder addContent(ChatContent content) {
            contents.add(Objects.requireNonNull(content, "content"));
            return this;
        }

        public Builder contents(List<? extends ChatContent> contents) {
            Objects.requireNonNull(contents, "contents").forEach(this::addContent);
            return this;
        }

        public Builder authorName(String authorName) {
            this.authorName = authorName;
            return this;
        }

        public Builder additionalProperty(String name, Object value) {
            additionalProperties.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }
}
