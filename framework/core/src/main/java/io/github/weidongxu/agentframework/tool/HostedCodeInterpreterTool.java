package io.github.weidongxu.agentframework.tool;

/**
 * Hosted code-interpreter tool. The model writes code that the host runs in a
 * managed sandbox container, returning results and files. Parity with .NET
 * {@code HostedCodeInterpreterTool} and Python {@code SupportsCodeInterpreterTool}.
 */
public final class HostedCodeInterpreterTool extends HostedTool {

    private final String containerId;

    public HostedCodeInterpreterTool() {
        this(null);
    }

    /**
     * @param containerId an explicit container id, or {@code null} to let the host
     *                    allocate an ephemeral container per response ("auto").
     */
    public HostedCodeInterpreterTool(String containerId) {
        super("code_interpreter");
        this.containerId = containerId;
    }

    /** @return the explicit container id, or {@code null} for auto allocation. */
    public String getContainerId() {
        return containerId;
    }
}
