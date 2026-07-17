package io.github.weidongxu.agentframework.chat;

import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChatOptions {
    private final String modelId;
    private final String instructions;
    private final String conversationId;
    private final String continuationToken;
    private final List<Tool> tools;
    private final boolean toolsSpecified;
    private final Double temperature;
    private final Integer maxOutputTokens;
    private final ResponseFormat responseFormat;
    private final Map<String, Object> additionalProperties;

    private ChatOptions(Builder builder) {
        this.modelId = builder.modelId;
        this.instructions = builder.instructions;
        this.conversationId = builder.conversationId;
        this.continuationToken = builder.continuationToken;
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.toolsSpecified = builder.toolsSpecified;
        this.temperature = builder.temperature;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.responseFormat = builder.responseFormat;
        this.additionalProperties = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.additionalProperties));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public String getModelId() {
        return modelId;
    }

    public String getInstructions() {
        return instructions;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getContinuationToken() {
        return continuationToken;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public boolean isToolsSpecified() {
        return toolsSpecified;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public static final class Builder {
        private String modelId;
        private String instructions;
        private String conversationId;
        private String continuationToken;
        private final List<Tool> tools = new ArrayList<>();
        private boolean toolsSpecified;
        private Double temperature;
        private Integer maxOutputTokens;
        private ResponseFormat responseFormat;
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        private Builder() {
        }

        private Builder(ChatOptions source) {
            this.modelId = source.modelId;
            this.instructions = source.instructions;
            this.conversationId = source.conversationId;
            this.continuationToken = source.continuationToken;
            this.tools.addAll(source.tools);
            this.toolsSpecified = source.toolsSpecified;
            this.temperature = source.temperature;
            this.maxOutputTokens = source.maxOutputTokens;
            this.responseFormat = source.responseFormat;
            this.additionalProperties.putAll(source.additionalProperties);
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
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

        public Builder tool(Tool tool) {
            this.tools.add(Objects.requireNonNull(tool, "tool"));
            this.toolsSpecified = true;
            return this;
        }

        public Builder tools(List<? extends Tool> tools) {
            Objects.requireNonNull(tools, "tools");
            toolsSpecified = true;
            tools.forEach(this::tool);
            return this;
        }

        public Builder clearTools() {
            tools.clear();
            toolsSpecified = true;
            return this;
        }

        public Builder temperature(Double temperature) {
            if (temperature != null && (temperature < 0 || temperature > 2)) {
                throw new IllegalArgumentException("temperature must be between 0 and 2");
            }
            this.temperature = temperature;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            if (maxOutputTokens != null && maxOutputTokens <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder additionalProperty(String name, Object value) {
            additionalProperties.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        public ChatOptions build() {
            return new ChatOptions(this);
        }
    }
}
