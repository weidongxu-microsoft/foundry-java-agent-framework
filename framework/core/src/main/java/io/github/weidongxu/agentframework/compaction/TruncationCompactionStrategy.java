package io.github.weidongxu.agentframework.compaction;

import java.util.List;

/**
 * Excludes the oldest non-system turns once the history grows beyond {@code triggerGroupCount},
 * reducing it down to the most recent {@code preserveGroups} turns. Mirrors MAF's
 * {@code TruncationCompactionStrategy} (default: preserve the last 32 turns).
 */
public final class TruncationCompactionStrategy implements CompactionStrategy {
    private static final int DEFAULT_PRESERVE_GROUPS = 32;

    private final int triggerGroupCount;
    private final int preserveGroups;

    public TruncationCompactionStrategy() {
        this(DEFAULT_PRESERVE_GROUPS, DEFAULT_PRESERVE_GROUPS);
    }

    public TruncationCompactionStrategy(int preserveGroups) {
        this(preserveGroups, preserveGroups);
    }

    public TruncationCompactionStrategy(int triggerGroupCount, int preserveGroups) {
        if (preserveGroups < 0) {
            throw new IllegalArgumentException("preserveGroups must not be negative");
        }
        this.preserveGroups = preserveGroups;
        this.triggerGroupCount = Math.max(triggerGroupCount, preserveGroups);
    }

    @Override
    public void compact(CompactionMessageIndex index) {
        List<CompactionMessageGroup> nonSystem = index.getNonSystemGroups();
        if (nonSystem.size() <= triggerGroupCount) {
            return;
        }
        int excludeCount = nonSystem.size() - preserveGroups;
        for (int i = 0; i < excludeCount; i++) {
            nonSystem.get(i).setExcluded(true);
        }
    }
}
