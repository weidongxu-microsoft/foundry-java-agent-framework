package io.github.weidongxu.agentframework.chat;

public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    CANCELLED,
    OTHER
}
