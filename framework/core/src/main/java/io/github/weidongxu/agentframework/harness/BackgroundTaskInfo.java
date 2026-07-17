package io.github.weidongxu.agentframework.harness;

/**
 * Metadata and result of a background task managed by {@link BackgroundAgentsProvider}, mirroring
 * the MAF {@code BackgroundTaskInfo} type.
 */
public final class BackgroundTaskInfo {
    private final int id;
    private final String agentName;
    private final String description;
    private BackgroundTaskStatus status;
    private String resultText;
    private String errorText;

    BackgroundTaskInfo(int id, String agentName, String description, BackgroundTaskStatus status) {
        this.id = id;
        this.agentName = agentName;
        this.description = description;
        this.status = status;
    }

    /** The unique identifier for this background task. */
    public int getId() {
        return id;
    }

    /** The name of the agent executing this task. */
    public String getAgentName() {
        return agentName;
    }

    /** A caller-supplied description of what this task is doing. */
    public String getDescription() {
        return description;
    }

    /** The current status of this task. */
    public BackgroundTaskStatus getStatus() {
        return status;
    }

    void setStatus(BackgroundTaskStatus status) {
        this.status = status;
    }

    /** The text result, populated when the task completes successfully. */
    public String getResultText() {
        return resultText;
    }

    void setResultText(String resultText) {
        this.resultText = resultText;
    }

    /** The error message, populated when the task fails. */
    public String getErrorText() {
        return errorText;
    }

    void setErrorText(String errorText) {
        this.errorText = errorText;
    }
}
