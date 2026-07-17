package io.github.weidongxu.agentframework.tool;

import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;

import java.util.Objects;

/**
 * Options controlling how an {@link io.github.weidongxu.agentframework.agent.Agent} is exposed as a
 * {@link Tool} via {@link AgentTool}.
 *
 * <p>All fields are optional. When unset, the tool name/description default to the agent's own
 * {@code name}/{@code description} (name sanitized to a function-safe identifier).
 */
public final class AgentToolOptions {

    private final String name;
    private final String description;
    private final String queryParameterDescription;
    private final AgentSession session;
    private final AgentRunOptions runOptions;
    private final ApprovalMode approvalMode;

    private AgentToolOptions(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.queryParameterDescription = builder.queryParameterDescription;
        this.session = builder.session;
        this.runOptions = builder.runOptions;
        this.approvalMode = builder.approvalMode;
    }

    public static AgentToolOptions empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getQueryParameterDescription() {
        return queryParameterDescription;
    }

    /**
     * @return an explicit session to reuse across calls (preserving conversation context), or
     * {@code null} to run each call statelessly. A shared session is not safe for concurrent calls.
     */
    public AgentSession getSession() {
        return session;
    }

    public AgentRunOptions getRunOptions() {
        return runOptions;
    }

    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public static final class Builder {
        private String name;
        private String description;
        private String queryParameterDescription;
        private AgentSession session;
        private AgentRunOptions runOptions;
        private ApprovalMode approvalMode = ApprovalMode.NEVER;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder queryParameterDescription(String queryParameterDescription) {
            this.queryParameterDescription = queryParameterDescription;
            return this;
        }

        public Builder session(AgentSession session) {
            this.session = session;
            return this;
        }

        public Builder runOptions(AgentRunOptions runOptions) {
            this.runOptions = runOptions;
            return this;
        }

        public Builder approvalMode(ApprovalMode approvalMode) {
            this.approvalMode = Objects.requireNonNull(approvalMode, "approvalMode");
            return this;
        }

        public AgentToolOptions build() {
            return new AgentToolOptions(this);
        }
    }
}
