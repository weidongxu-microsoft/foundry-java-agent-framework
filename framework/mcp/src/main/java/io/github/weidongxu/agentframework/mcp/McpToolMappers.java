package io.github.weidongxu.agentframework.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure mappers between the MCP Java SDK wire types and the framework tool model. Kept separate from
 * {@link McpToolSource} so the (transport-free) conversion logic is unit-testable in isolation.
 */
final class McpToolMappers {

    private McpToolMappers() {
    }

    /**
     * Adapts an MCP tool's {@code inputSchema} to the JSON-schema {@code parametersSchema} the
     * framework's {@code FunctionTool} advertises to the model. MCP already exposes the schema as a
     * plain {@code Map}, so this is a defensive copy with a minimal-object-schema fallback when the
     * server omits it.
     */
    static Map<String, Object> parametersSchema(McpSchema.Tool tool) {
        Map<String, Object> inputSchema = tool.inputSchema();
        if (inputSchema == null || inputSchema.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "object");
            fallback.put("properties", new LinkedHashMap<>());
            return fallback;
        }
        return new LinkedHashMap<>(inputSchema);
    }

    /**
     * Flattens a {@link McpSchema.CallToolResult} into the plain-string tool output the framework's
     * function-invoking loop feeds back to the model. Text content is concatenated; an
     * {@code isError} result is surfaced as a readable message so the model can recover rather than
     * silently seeing empty output.
     */
    static String resultToString(McpSchema.CallToolResult result) {
        String text = textContent(result);
        boolean isError = Boolean.TRUE.equals(result.isError());
        if (isError) {
            return text.isEmpty() ? "MCP tool call failed." : "MCP tool call failed: " + text;
        }
        if (!text.isEmpty()) {
            return text;
        }
        Object structured = result.structuredContent();
        return structured == null ? "" : String.valueOf(structured);
    }

    private static String textContent(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
