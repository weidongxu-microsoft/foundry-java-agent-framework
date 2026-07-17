package io.github.weidongxu.agentframework.impl;

public final class ToolInvocationException extends RuntimeException {
    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
