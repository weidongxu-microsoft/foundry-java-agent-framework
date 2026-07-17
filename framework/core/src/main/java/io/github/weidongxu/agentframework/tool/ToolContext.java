package io.github.weidongxu.agentframework.tool;

import io.github.weidongxu.agentframework.agent.AgentSession;

/**
 * Per-invocation context handed to a {@link Tool} when the function-invoking loop runs it.
 *
 * <p>Carries the ambient {@link AgentSession} (when the run is session-scoped) so tools can
 * partition their state per end-user/session, mirroring how microsoft/agent-framework passes run
 * context to tools. Tools that do not need context can ignore it and implement only
 * {@link Tool#invoke(java.util.Map)}.</p>
 */
public final class ToolContext {
    private static final ToolContext EMPTY = new ToolContext(null);

    private final AgentSession session;

    public ToolContext(AgentSession session) {
        this.session = session;
    }

    public static ToolContext empty() {
        return EMPTY;
    }

    /** @return the ambient agent session, or {@code null} when the run is not session-scoped. */
    public AgentSession getSession() {
        return session;
    }
}
