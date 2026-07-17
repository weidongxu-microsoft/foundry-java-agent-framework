package io.github.weidongxu.agentframework.chat;

import java.util.Objects;

public final class FunctionResultContent implements ChatContent {
    private final String callId;
    private final String result;
    private final boolean error;

    public FunctionResultContent(String callId, String result, boolean error) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.result = Objects.requireNonNull(result, "result");
        this.error = error;
    }

    @Override
    public String getType() {
        return "function_result";
    }

    public String getCallId() {
        return callId;
    }

    public String getResult() {
        return result;
    }

    public boolean isError() {
        return error;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FunctionResultContent)) {
            return false;
        }
        FunctionResultContent content = (FunctionResultContent) other;
        return error == content.error
                && callId.equals(content.callId)
                && result.equals(content.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, result, error);
    }
}
