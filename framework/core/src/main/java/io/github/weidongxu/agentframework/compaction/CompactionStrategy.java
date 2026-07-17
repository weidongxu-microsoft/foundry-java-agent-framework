package io.github.weidongxu.agentframework.compaction;

/**
 * Decides which {@link CompactionMessageGroup}s to exclude from the model context. A strategy marks
 * groups via {@link CompactionMessageGroup#setExcluded(boolean)}; it must never exclude a system
 * group. Mirrors MAF's {@code CompactionStrategy}.
 */
public interface CompactionStrategy {
    void compact(CompactionMessageIndex index);
}
