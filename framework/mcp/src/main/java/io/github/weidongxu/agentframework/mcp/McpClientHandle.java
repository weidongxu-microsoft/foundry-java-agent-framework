package io.github.weidongxu.agentframework.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Minimal seam over the MCP SDK sync client exposing only the operations {@link McpToolSource} and
 * {@link McpSkillSource} need: listing/invoking tools and reading resources. Isolating this lets the
 * adapt/allow-list and skill-discovery logic be unit-tested against a fake without a live server or
 * transport.
 */
interface McpClientHandle extends AutoCloseable {

    /** @return the tools advertised by the connected MCP server. */
    List<McpSchema.Tool> listTools();

    /** Invokes a single tool on the server and returns its raw result. */
    McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request);

    /** Reads a single resource (by URI) from the server — used to discover and load skills. */
    McpSchema.ReadResourceResult readResource(String uri);

    /** Closes the underlying client (and, for stdio, the spawned server process). */
    @Override
    void close();
}
