package io.github.weidongxu.agentframework.openai;

public final class OpenAIAdapterException extends RuntimeException {
    public OpenAIAdapterException(String message) {
        super(message);
    }

    public OpenAIAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
