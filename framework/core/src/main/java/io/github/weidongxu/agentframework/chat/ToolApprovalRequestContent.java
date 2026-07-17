package io.github.weidongxu.agentframework.chat;

import java.util.Objects;

public final class ToolApprovalRequestContent implements ChatContent {
    private final String requestId;
    private final FunctionCallContent functionCall;

    public ToolApprovalRequestContent(
            String requestId,
            FunctionCallContent functionCall) {
        this.requestId = requireNonBlank(requestId, "requestId");
        this.functionCall = Objects.requireNonNull(functionCall, "functionCall");
    }

    @Override
    public String getType() {
        return "tool_approval_request";
    }

    public String getRequestId() {
        return requestId;
    }

    public FunctionCallContent getFunctionCall() {
        return functionCall;
    }

    public ToolApprovalResponseContent approve() {
        return new ToolApprovalResponseContent(requestId, true, null);
    }

    public ToolApprovalResponseContent reject(String reason) {
        return new ToolApprovalResponseContent(requestId, false, reason);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ToolApprovalRequestContent)) {
            return false;
        }
        ToolApprovalRequestContent content = (ToolApprovalRequestContent) other;
        return requestId.equals(content.requestId)
                && functionCall.equals(content.functionCall);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, functionCall);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
