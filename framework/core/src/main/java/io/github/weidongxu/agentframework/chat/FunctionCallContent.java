package io.github.weidongxu.agentframework.chat;

import java.util.Objects;

public final class FunctionCallContent implements ChatContent {
    private final String callId;
    private final String name;
    private final String arguments;

    public FunctionCallContent(String callId, String name, String arguments) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
    }

    @Override
    public String getType() {
        return "function_call";
    }

    public String getCallId() {
        return callId;
    }

    public String getName() {
        return name;
    }

    public String getArguments() {
        return arguments;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FunctionCallContent)) {
            return false;
        }
        FunctionCallContent content = (FunctionCallContent) other;
        return callId.equals(content.callId)
                && name.equals(content.name)
                && arguments.equals(content.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, name, arguments);
    }
}
