package io.github.weidongxu.agentframework.chat;

import java.util.Objects;

public final class ToolApprovalResponseContent implements ChatContent {
    private final String requestId;
    private final boolean approved;
    private final String reason;

    public ToolApprovalResponseContent(
            String requestId,
            boolean approved,
            String reason) {
        this.requestId = requireNonBlank(requestId, "requestId");
        this.approved = approved;
        this.reason = reason;
    }

    @Override
    public String getType() {
        return "tool_approval_response";
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isApproved() {
        return approved;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ToolApprovalResponseContent)) {
            return false;
        }
        ToolApprovalResponseContent content = (ToolApprovalResponseContent) other;
        return approved == content.approved
                && requestId.equals(content.requestId)
                && Objects.equals(reason, content.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, approved, reason);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
