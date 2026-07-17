package io.github.weidongxu.agentframework.compaction;

import io.github.weidongxu.agentframework.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An atomic group of chat messages that a {@link CompactionStrategy} must keep or drop together
 * (e.g. a user turn plus the assistant tool-call/result messages it produced).
 */
public final class CompactionMessageGroup {
    private final List<ChatMessage> messages;
    private final boolean system;
    private boolean excluded;

    CompactionMessageGroup(List<ChatMessage> messages, boolean system) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.system = system;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    /** System/developer groups are anchors that strategies must never exclude. */
    public boolean isSystem() {
        return system;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public void setExcluded(boolean excluded) {
        this.excluded = excluded;
    }
}
