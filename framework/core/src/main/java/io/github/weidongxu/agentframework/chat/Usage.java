package io.github.weidongxu.agentframework.chat;

public final class Usage {
    private final long inputTokens;
    private final long outputTokens;

    public Usage(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token counts cannot be negative");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return inputTokens + outputTokens;
    }
}
