package io.github.weidongxu.agentframework.compaction;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.ChatRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Organises a flat message list into atomic {@link CompactionMessageGroup}s so a
 * {@link CompactionStrategy} can exclude whole turns without splitting tool-call/result pairs.
 *
 * <p>Grouping rules: each system/developer message is its own protected group; each user message
 * starts a new turn group; following assistant/tool messages attach to the current turn.
 */
public final class CompactionMessageIndex {
    private final List<CompactionMessageGroup> groups;

    public CompactionMessageIndex(List<ChatMessage> messages) {
        this.groups = Collections.unmodifiableList(buildGroups(messages));
    }

    private static List<CompactionMessageGroup> buildGroups(List<ChatMessage> messages) {
        List<CompactionMessageGroup> result = new ArrayList<>();
        List<ChatMessage> current = null;
        for (ChatMessage message : messages) {
            ChatRole role = message.getRole();
            if (role == ChatRole.SYSTEM || role == ChatRole.DEVELOPER) {
                current = flush(result, current);
                result.add(new CompactionMessageGroup(Collections.singletonList(message), true));
            } else if (role == ChatRole.USER) {
                current = flush(result, current);
                current = new ArrayList<>();
                current.add(message);
            } else {
                if (current == null) {
                    current = new ArrayList<>();
                }
                current.add(message);
            }
        }
        flush(result, current);
        return result;
    }

    private static List<ChatMessage> flush(List<CompactionMessageGroup> result, List<ChatMessage> current) {
        if (current != null && !current.isEmpty()) {
            result.add(new CompactionMessageGroup(current, false));
        }
        return null;
    }

    public List<CompactionMessageGroup> getGroups() {
        return groups;
    }

    public List<CompactionMessageGroup> getNonSystemGroups() {
        List<CompactionMessageGroup> nonSystem = new ArrayList<>();
        for (CompactionMessageGroup group : groups) {
            if (!group.isSystem()) {
                nonSystem.add(group);
            }
        }
        return nonSystem;
    }

    /** The messages of all non-excluded groups, in original order. */
    public List<ChatMessage> getIncludedMessages() {
        List<ChatMessage> included = new ArrayList<>();
        for (CompactionMessageGroup group : groups) {
            if (!group.isExcluded()) {
                included.addAll(group.getMessages());
            }
        }
        return included;
    }
}
