package io.github.weidongxu.agentframework.workflow;

@FunctionalInterface
public interface GroupChatTerminationPolicy {
    boolean shouldTerminate(GroupChatContext context);
}
