package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface GroupChatManager {
    CompletionStage<String> selectNext(
            List<ChatMessage> conversation,
            List<String> participants,
            int round);
}
