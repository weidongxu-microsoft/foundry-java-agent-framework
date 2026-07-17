package io.github.weidongxu.agentframework.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Hosted Model Context Protocol (MCP) tool. The host connects to a remote MCP
 * server and exposes its tools to the model. Parity with .NET
 * {@code HostedMcpServerTool} and Python {@code SupportsMCPTool} / {@code MCPTool}.
 */
public final class HostedMcpTool extends HostedTool {

    private final String serverUrl;
    private final List<String> allowedTools;
    private final ApprovalMode requireApproval;

    public HostedMcpTool(String serverLabel, String serverUrl) {
        this(serverLabel, serverUrl, null, ApprovalMode.NEVER);
    }

    /**
     * @param serverLabel     a stable label identifying the MCP server (used as the tool name).
     * @param serverUrl       the MCP server endpoint URL.
     * @param allowedTools    an optional allow-list of tool names, or {@code null}/empty for all.
     * @param requireApproval whether the host must request approval before running the server's
     *                        tools ({@link ApprovalMode#ALWAYS_REQUIRE}) or not
     *                        ({@link ApprovalMode#NEVER}).
     */
    public HostedMcpTool(
            String serverLabel,
            String serverUrl,
            List<String> allowedTools,
            ApprovalMode requireApproval) {
        super(Objects.requireNonNull(serverLabel, "serverLabel"));
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.allowedTools = allowedTools == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(allowedTools));
        this.requireApproval = Objects.requireNonNull(requireApproval, "requireApproval");
    }

    /** @return the server label, which doubles as {@link #getName()}. */
    public String getServerLabel() {
        return getName();
    }

    public String getServerUrl() {
        return serverUrl;
    }

    /** @return the allow-listed tool names, or an empty list to allow all. */
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    @Override
    public ApprovalMode getApprovalMode() {
        return requireApproval;
    }
}
