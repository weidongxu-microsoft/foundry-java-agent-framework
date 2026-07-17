package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A <em>local</em> MCP tool source: the framework acts as the MCP <em>client</em>, connects to a
 * server (a stdio child process or a streamable-HTTP endpoint), discovers its tools, and adapts each
 * into a framework {@link FunctionTool} that the local function-invoking loop runs. This mirrors the
 * Python framework's {@code MCPStdioTool} / {@code MCPStreamableHTTPTool} wrappers.
 *
 * <p>Contrast with {@code HostedMcpTool}, where the model host (Foundry) is the MCP client and the
 * framework never runs the tools. Here the tools execute in-process via a relayed {@code tools/call}.
 *
 * <p>An optional <em>allow-list</em> restricts which server tools are exposed to the model. This is
 * the recommended way to make a mixed-capability server effectively read-only: pass only the names
 * of the safe tools and the mutating ones are never surfaced.
 *
 * <p>The source owns the underlying client/process and is {@link AutoCloseable}; close it (e.g. via
 * a Spring {@code @Bean(destroyMethod = "close")}) to shut the server down.
 */
public final class McpToolSource implements AutoCloseable {

    private final McpClientHandle client;
    private final Set<String> allowedToolNames;

    McpToolSource(McpClientHandle client, Collection<String> allowedToolNames) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedToolNames = allowedToolNames == null
                ? null
                : new LinkedHashSet<>(allowedToolNames);
    }

    /** Connects to an MCP server spawned as a stdio child process, exposing all its tools. */
    public static McpToolSource stdio(String command, List<String> args, Map<String, String> env) {
        return stdio(command, args, env, null);
    }

    /**
     * Connects to an MCP server spawned as a stdio child process, exposing only tools whose names
     * are in {@code allowedToolNames} (pass {@code null} to expose all).
     */
    public static McpToolSource stdio(
            String command,
            List<String> args,
            Map<String, String> env,
            Collection<String> allowedToolNames) {
        return new McpToolSource(McpClients.stdio(command, args, env), allowedToolNames);
    }

    /** Connects to a streamable-HTTP MCP server at {@code url}, exposing all its tools. */
    public static McpToolSource streamableHttp(String url) {
        return streamableHttp(url, null);
    }

    /**
     * Connects to a streamable-HTTP MCP server at {@code url}, exposing only tools whose names are
     * in {@code allowedToolNames} (pass {@code null} to expose all).
     */
    public static McpToolSource streamableHttp(String url, Collection<String> allowedToolNames) {
        return new McpToolSource(McpClients.streamableHttp(url), allowedToolNames);
    }

    /**
     * Discovers the connected server's tools and adapts each (subject to the allow-list) into a
     * framework {@link FunctionTool}. Each returned tool invokes the server via {@code tools/call}
     * when the model calls it.
     */
    public List<FunctionTool> listTools() {
        List<FunctionTool> tools = new ArrayList<>();
        for (McpSchema.Tool tool : client.listTools()) {
            if (allowedToolNames != null && !allowedToolNames.contains(tool.name())) {
                continue;
            }
            tools.add(toFunctionTool(tool));
        }
        return tools;
    }

    private FunctionTool toFunctionTool(McpSchema.Tool tool) {
        String name = tool.name();
        String description = tool.description() == null ? "" : tool.description();
        Map<String, Object> schema = McpToolMappers.parametersSchema(tool);
        return new FunctionTool(name, description, schema, arguments -> callTool(name, arguments));
    }

    private CompletionStage<String> callTool(String name, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                name, arguments == null ? Map.of() : arguments);
        McpSchema.CallToolResult result = client.callTool(request);
        return CompletableFuture.completedFuture(McpToolMappers.resultToString(result));
    }

    @Override
    public void close() {
        client.close();
    }
}
