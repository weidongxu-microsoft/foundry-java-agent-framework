package io.github.weidongxu.agentframework.agent;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class AIContext {
    private static final AIContext EMPTY = builder().build();

    private final String instructions;
    private final List<ChatMessage> messages;
    private final List<Tool> tools;

    private AIContext(Builder builder) {
        this.instructions = builder.instructions;
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
    }

    public static AIContext empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getInstructions() {
        return instructions;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public AIContext merge(AIContext additional) {
        Objects.requireNonNull(additional, "additional");
        Builder merged = builder()
                .instructions(mergeInstructions(instructions, additional.instructions))
                .messages(messages)
                .messages(additional.messages)
                .tools(tools)
                .tools(additional.tools);
        return merged.build();
    }

    private static String mergeInstructions(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + "\n" + second;
    }

    public static final class Builder {
        private String instructions;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<Tool> tools = new ArrayList<>();

        private Builder() {
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder message(ChatMessage message) {
            messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder messages(List<? extends ChatMessage> messages) {
            Objects.requireNonNull(messages, "messages").forEach(this::message);
            return this;
        }

        public Builder tool(Tool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder tools(List<? extends Tool> tools) {
            Objects.requireNonNull(tools, "tools").forEach(this::tool);
            return this;
        }

        public AIContext build() {
            return new AIContext(this);
        }
    }
}
