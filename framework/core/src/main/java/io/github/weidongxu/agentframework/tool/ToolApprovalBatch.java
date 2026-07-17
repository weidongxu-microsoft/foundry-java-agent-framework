package io.github.weidongxu.agentframework.tool;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolApprovalBatch {
    private final String id;
    private final String scope;
    private final Map<String, FunctionCallContent> callsByRequestId;
    private final List<ChatMessage> resumeConversation;
    private final List<String> toolNames;
    private final Instant createdAt;

    public ToolApprovalBatch(
            String id,
            String scope,
            Map<String, FunctionCallContent> callsByRequestId,
            List<ChatMessage> resumeConversation,
            List<String> toolNames,
            Instant createdAt) {
        this.id = requireNonBlank(id, "id");
        this.scope = scope;
        this.callsByRequestId = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        callsByRequestId,
                        "callsByRequestId")));
        if (this.callsByRequestId.isEmpty()) {
            throw new IllegalArgumentException(
                    "callsByRequestId cannot be empty");
        }
        this.resumeConversation = resumeConversation == null
                ? null
                : Collections.unmodifiableList(
                        new ArrayList<>(resumeConversation));
        this.toolNames = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        toolNames,
                        "toolNames")));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String getId() {
        return id;
    }

    public String getScope() {
        return scope;
    }

    public Map<String, FunctionCallContent> getCallsByRequestId() {
        return callsByRequestId;
    }

    public List<ChatMessage> getResumeConversation() {
        return resumeConversation;
    }

    public List<String> getToolNames() {
        return toolNames;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
