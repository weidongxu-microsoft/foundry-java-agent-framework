package io.github.weidongxu.agentframework.chat;

import java.util.Objects;

public final class TextContent implements ChatContent {
    private final String text;

    public TextContent(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    @Override
    public String getType() {
        return "text";
    }

    public String getText() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TextContent && text.equals(((TextContent) other).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
