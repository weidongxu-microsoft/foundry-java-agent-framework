package io.github.weidongxu.agentframework.mcp;

import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link McpToolSource}: a fake {@link McpClientHandle} stands in for a live MCP
 * server so the adapt / allow-list / result-mapping logic is exercised without any process or
 * transport.
 */
class McpToolSourceTest {

    private static McpSchema.Tool tool(String name, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("repo_path", Map.of("type", "string"));
        schema.put("properties", properties);
        schema.put("required", List.of("repo_path"));
        return McpSchema.Tool.builder().name(name).description(description).inputSchema(schema)
                .build();
    }

    private static McpSchema.CallToolResult textResult(String text, boolean isError) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(text)), isError, null, null);
    }

    @Test
    void listToolsAdaptsNameDescriptionAndSchema() {
        FakeClient fake = new FakeClient(
                List.of(tool("git_log", "Show the commit logs")),
                req -> textResult("ok", false));
        try (McpToolSource source = new McpToolSource(fake, null)) {
            List<FunctionTool> tools = source.listTools();
            assertEquals(1, tools.size());
            FunctionTool git = tools.get(0);
            assertEquals("git_log", git.getName());
            assertEquals("Show the commit logs", git.getDescription());
            assertEquals("object", git.getParametersSchema().get("type"));
            assertNotNull(git.getParametersSchema().get("properties"));
        }
    }

    @Test
    void invokingAnAdaptedToolRelaysToCallToolAndMapsTextResult() {
        AtomicReference<McpSchema.CallToolRequest> seen = new AtomicReference<>();
        FakeClient fake = new FakeClient(
                List.of(tool("git_show", "Show a commit")),
                req -> {
                    seen.set(req);
                    return textResult("commit abc123", false);
                });
        try (McpToolSource source = new McpToolSource(fake, null)) {
            FunctionTool git = source.listTools().get(0);
            String result = git.invoke(Map.of("revision", "HEAD"))
                    .toCompletableFuture().join();
            assertEquals("commit abc123", result);
            assertEquals("git_show", seen.get().name());
            assertEquals("HEAD", seen.get().arguments().get("revision"));
        }
    }

    @Test
    void allowListFiltersOutNonAllowedTools() {
        FakeClient fake = new FakeClient(
                List.of(tool("git_log", "read"), tool("git_commit", "write"),
                        tool("git_show", "read")),
                req -> textResult("ok", false));
        try (McpToolSource source = new McpToolSource(fake, Set.of("git_log", "git_show"))) {
            List<String> names = new ArrayList<>();
            source.listTools().forEach(t -> names.add(t.getName()));
            assertEquals(List.of("git_log", "git_show"), names);
        }
    }

    @Test
    void errorResultIsSurfacedAsReadableMessage() {
        FakeClient fake = new FakeClient(
                List.of(tool("git_show", "Show a commit")),
                req -> textResult("bad revision", true));
        try (McpToolSource source = new McpToolSource(fake, null)) {
            String result = source.listTools().get(0).invoke(Map.of())
                    .toCompletableFuture().join();
            assertTrue(result.contains("failed"), result);
            assertTrue(result.contains("bad revision"), result);
        }
    }

    @Test
    void closePropagatesToClient() {
        FakeClient fake = new FakeClient(List.of(), req -> textResult("", false));
        McpToolSource source = new McpToolSource(fake, null);
        source.close();
        assertTrue(fake.closed);
    }

    /** In-memory {@link McpClientHandle} returning canned tools and a scripted call handler. */
    private static final class FakeClient implements McpClientHandle {
        private final List<McpSchema.Tool> tools;
        private final java.util.function.Function<McpSchema.CallToolRequest,
                McpSchema.CallToolResult> onCall;
        private boolean closed;

        FakeClient(
                List<McpSchema.Tool> tools,
                java.util.function.Function<McpSchema.CallToolRequest,
                        McpSchema.CallToolResult> onCall) {
            this.tools = tools;
            this.onCall = onCall;
        }

        @Override
        public List<McpSchema.Tool> listTools() {
            return tools;
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            return onCall.apply(request);
        }

        @Override
        public McpSchema.ReadResourceResult readResource(String uri) {
            throw new UnsupportedOperationException("readResource not used by tool tests");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
