package io.github.weidongxu.agentframework.compaction;

import java.util.List;

/**
 * Keeps only the most recent {@code keepLastGroups} non-system turns, always excluding older ones.
 * Mirrors MAF's {@code SlidingWindowCompactionStrategy} (default: keep the last turn).
 */
public final class SlidingWindowCompactionStrategy implements CompactionStrategy {
    private final int keepLastGroups;

    public SlidingWindowCompactionStrategy() {
        this(1);
    }

    public SlidingWindowCompactionStrategy(int keepLastGroups) {
        if (keepLastGroups < 0) {
            throw new IllegalArgumentException("keepLastGroups must not be negative");
        }
        this.keepLastGroups = keepLastGroups;
    }

    @Override
    public void compact(CompactionMessageIndex index) {
        List<CompactionMessageGroup> nonSystem = index.getNonSystemGroups();
        int excludeCount = nonSystem.size() - keepLastGroups;
        for (int i = 0; i < excludeCount; i++) {
            nonSystem.get(i).setExcluded(true);
        }
    }
}
