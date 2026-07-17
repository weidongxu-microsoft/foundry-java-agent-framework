package io.github.weidongxu.agentframework.impl;

final class RunContextProperties {
    static final String AGENT_SESSION =
            "io.github.weidongxu.agentframework.run.agentSession";
    static final String AGENT =
            "io.github.weidongxu.agentframework.run.agent";
    static final String CHAT_HISTORY_PROVIDER =
            "io.github.weidongxu.agentframework.run.chatHistoryProvider";
    static final String PER_SERVICE_CALL_HISTORY =
            "io.github.weidongxu.agentframework.run.perServiceCallHistory";

    private RunContextProperties() {
    }
}
