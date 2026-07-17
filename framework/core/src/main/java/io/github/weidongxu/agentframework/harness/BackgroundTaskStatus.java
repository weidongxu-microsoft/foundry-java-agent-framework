package io.github.weidongxu.agentframework.harness;

/**
 * The status of a background task managed by {@link BackgroundAgentsProvider}, mirroring the MAF
 * {@code BackgroundTaskStatus} enum.
 */
public enum BackgroundTaskStatus {
    /** The task is currently running. */
    RUNNING,
    /** The task completed successfully. */
    COMPLETED,
    /** The task failed with an error. */
    FAILED,
    /**
     * The task's in-flight reference was lost (e.g. after a session restore), so its final state
     * cannot be determined.
     */
    LOST
}
