package io.github.weidongxu.agentframework.workflow;

import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GroupChatContext {
    private final int round;
    private final String lastParticipant;
    private final List<ChatMessage> conversation;
    private final Map<String, AgentResponse> participantResponses;

    GroupChatContext(
            int round,
            String lastParticipant,
            List<ChatMessage> conversation,
            Map<String, AgentResponse> participantResponses) {
        this.round = round;
        this.lastParticipant = lastParticipant;
        this.conversation = Collections.unmodifiableList(
                new ArrayList<>(conversation));
        this.participantResponses = Collections.unmodifiableMap(
                new LinkedHashMap<>(participantResponses));
    }

    public int getRound() {
        return round;
    }

    public String getLastParticipant() {
        return lastParticipant;
    }

    public List<ChatMessage> getConversation() {
        return conversation;
    }

    public Map<String, AgentResponse> getParticipantResponses() {
        return participantResponses;
    }
}
