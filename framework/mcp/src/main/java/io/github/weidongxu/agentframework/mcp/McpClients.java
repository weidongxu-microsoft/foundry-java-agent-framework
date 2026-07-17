package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.chat.PlatformCallContext;
import io.github.weidongxu.agentframework.chat.PlatformRequestHeaders;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared factory for {@link McpClientHandle}s, used by {@link McpToolSource} and
 * {@link McpSkillSource}. Centralises transport construction (stdio child process or streamable
 * HTTP) and the sync-client wiring so both sources connect identically.
 */
final class McpClients {

    private McpClients() {
    }

    /** Connects to an MCP server spawned as a stdio child process. */
    static McpClientHandle stdio(String command, List<String> args, Map<String, String> env) {
        ServerParameters.Builder params = ServerParameters.builder(
                Objects.requireNonNull(command, "command"));
        if (args != null) {
            params.args(args);
        }
        if (env != null) {
            params.env(env);
        }
        McpClientTransport transport =
                new StdioClientTransport(params.build(), McpJsonDefaults.getMapper());
        return connect(transport);
    }

    /** Connects to a streamable-HTTP MCP server at {@code url}. */
    static McpClientHandle streamableHttp(String url) {
        McpClientTransport transport = HttpClientStreamableHttpTransport
                .builder(Objects.requireNonNull(url, "url"))
                .httpRequestCustomizer(CALL_ID_CUSTOMIZER)
                .build();
        return connect(transport);
    }

    /**
     * Stamps the platform {@code x-agent-foundry-call-id} (when one is bound on the calling thread via
     * {@link PlatformCallContext}) onto every outbound MCP HTTP request, forwarding the call-id — and
     * only the call-id — to Foundry toolbox/MCP servers. The user-id is never forwarded.
     */
    static final McpSyncHttpClientRequestCustomizer CALL_ID_CUSTOMIZER =
            (requestBuilder, method, uri, body, context) -> {
                String callId = PlatformCallContext.current();
                if (callId != null && !callId.trim().isEmpty()) {
                    requestBuilder.header(PlatformRequestHeaders.CALL_ID, callId);
                }
            };

    private static McpClientHandle connect(McpClientTransport transport) {
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        return new SyncClientHandle(client);
    }

    /** Real {@link McpClientHandle} backed by the MCP SDK sync client. */
    private static final class SyncClientHandle implements McpClientHandle {
        private final McpSyncClient client;

        SyncClientHandle(McpSyncClient client) {
            this.client = client;
        }

        @Override
        public List<McpSchema.Tool> listTools() {
            return client.listTools().tools();
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            return client.callTool(request);
        }

        @Override
        public McpSchema.ReadResourceResult readResource(String uri) {
            return client.readResource(new McpSchema.ReadResourceRequest(uri));
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
