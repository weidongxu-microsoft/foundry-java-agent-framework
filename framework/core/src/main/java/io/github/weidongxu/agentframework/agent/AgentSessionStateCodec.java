package io.github.weidongxu.agentframework.agent;

public interface AgentSessionStateCodec {
    Object encode(Object value);

    Object decode(Object value);
}
