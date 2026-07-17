package com.example.hostedagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A single <em>local</em> function tool exposed to the model: a session TODO list with combined
 * read/write.
 *
 * <p>Unlike the hosted tools (web search, code interpreter) that Foundry executes server-side, this
 * is a client-side function tool. The framework's function-invoking loop runs it in-process when the
 * model emits a {@code function_call} and feeds the result back as a {@code function_call_output}.
 * The per-user memory/state partition ({@code scope}) is taken from the ambient
 * {@link AgentSession} carried by {@link ToolContext} — the hosting controller derives that session
 * from the Foundry identity header.</p>
 *
 * <p>The tool is authored entirely through the framework's {@link FunctionTool} +
 * {@code ContextualToolHandler}: this class supplies only the workload-specific schema and business
 * logic ({@link #execute}) and calls {@link #asFunctionTool()} to obtain the {@code Tool} — the
 * boilerplate {@code invoke}/context plumbing lives in the framework, not here.</p>
 *
 * <p>One combined tool (rather than separate {@code read}/{@code write}) keeps the agent's tool
 * count low. It is fully testable without a model: {@link #execute} takes the raw JSON arguments and
 * returns the JSON tool output, and both {@code read} and {@code write} return the full current
 * list.</p>
 */
@Component
public class TodoTool {

    /** The function name advertised to the model and matched in the tool loop. */
    public static final String NAME = "todo";

    static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed", "cancelled");
    static final Set<String> PRIORITIES = Set.of("high", "medium", "low");

    private static final String DESCRIPTION =
            "Manage the session task list. action='read' returns the current list. action='write' "
                    + "atomically REPLACES the entire list with the provided todos. Use proactively "
                    + "for tasks with 3+ distinct steps. Keep exactly ONE item 'in_progress' at a "
                    + "time. Reuse each item's id across writes to track the same task.";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TodoService todos;

    public TodoTool(TodoService todos) {
        this.todos = todos;
    }

    /**
     * Builds the framework {@link FunctionTool} for this workload tool. The
     * {@code ContextualToolHandler} resolves the per-user scope from the ambient session (falling
     * back to the shared default when unscoped), serializes the parsed arguments back to the JSON
     * {@link #execute} understands, and returns the JSON tool output.
     */
    public FunctionTool asFunctionTool() {
        return new FunctionTool(NAME, DESCRIPTION, getParametersSchema(), this::handle);
    }

    private CompletionStage<String> handle(Map<String, Object> arguments, ToolContext context) {
        String scope = scope(context);
        String argumentsJson;
        try {
            argumentsJson = MAPPER.writeValueAsString(
                    arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    errorJson("failed to encode arguments: " + e.getMessage()));
        }
        return CompletableFuture.completedFuture(execute(argumentsJson, scope));
    }

    private static String scope(ToolContext context) {
        if (context == null) {
            return null;
        }
        AgentSession session = context.getSession();
        return session == null ? null : session.getId();
    }

    /** JSON-schema parameters for the model (matches the OpenAI Responses function-tool shape). */
    Map<String, Object> getParametersSchema() {
        Map<String, Object> itemProperties = Map.of(
                "id", Map.of(
                        "type", "string",
                        "description", "Stable unique id, e.g. 'task-1'. Reuse across writes to "
                                + "track the same task."),
                "content", Map.of(
                        "type", "string",
                        "description", "Brief, specific, actionable task description."),
                "status", Map.of(
                        "type", "string",
                        "enum", List.of("pending", "in_progress", "completed", "cancelled"),
                        "description", "pending=not started; in_progress=actively working (exactly "
                                + "ONE at a time); completed=done; cancelled=no longer needed."),
                "priority", Map.of(
                        "type", "string",
                        "enum", List.of("high", "medium", "low"),
                        "description", "Optional priority level."));

        Map<String, Object> items = Map.of(
                "type", "object",
                "required", List.of("id", "content", "status"),
                "properties", itemProperties);

        Map<String, Object> properties = Map.of(
                "action", Map.of(
                        "type", "string",
                        "enum", List.of("read", "write"),
                        "description", "'read' returns the current list (todos is ignored). 'write' "
                                + "replaces the full list with the provided todos array."),
                "todos", Map.of(
                        "type", "array",
                        "description", "Required when action='write'. The complete new list — "
                                + "replaces all existing items. Empty array clears the list.",
                        "items", items));

        return Map.of(
                "type", "object",
                "required", List.of("action"),
                "properties", properties,
                "additionalProperties", false);
    }

    /**
     * Executes one {@code todo} tool call and returns the JSON tool output. Never throws: validation
     * and parse failures are returned as {@code {"error": "..."}} so the model can read the message
     * and recover (the harness convention for tool errors).
     *
     * @param argumentsJson the model-supplied {@code function_call.arguments} JSON string
     * @param scope         the per-user memory/state partition for this turn
     */
    public String execute(String argumentsJson, String scope) {
        try {
            JsonNode root = (argumentsJson == null || argumentsJson.isBlank())
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(argumentsJson);

            String action = text(root, "action");
            if (action == null) {
                return errorJson("missing 'action'; expected 'read' or 'write'");
            }
            switch (action.toLowerCase()) {
                case "read":
                    return listJson(scope);
                case "write":
                    return write(root.get("todos"), scope);
                default:
                    return errorJson("unknown action '" + action + "'; expected 'read' or 'write'");
            }
        } catch (Exception e) {
            return errorJson("failed to parse arguments: " + e.getMessage());
        }
    }

    private String write(JsonNode todosNode, String scope) {
        List<TodoItem> items = new ArrayList<>();
        if (todosNode != null && todosNode.isArray()) {
            Set<String> ids = new HashSet<>();
            for (JsonNode n : todosNode) {
                String id = text(n, "id");
                String content = text(n, "content");
                String status = text(n, "status");
                String priority = text(n, "priority");

                if (id == null || id.isBlank()) {
                    return errorJson("each todo requires a non-empty 'id'");
                }
                if (content == null || content.isBlank()) {
                    return errorJson("todo '" + id + "' requires 'content'");
                }
                if (status == null || !STATUSES.contains(status)) {
                    return errorJson("todo '" + id + "' has invalid status '" + status
                            + "'; allowed: " + STATUSES);
                }
                if (priority != null && !priority.isBlank() && !PRIORITIES.contains(priority)) {
                    return errorJson("todo '" + id + "' has invalid priority '" + priority
                            + "'; allowed: " + PRIORITIES);
                }
                if (!ids.add(id)) {
                    return errorJson("duplicate id '" + id + "'");
                }
                items.add(new TodoItem(id, content, status,
                        (priority == null || priority.isBlank()) ? null : priority));
            }
        } else if (todosNode != null && !todosNode.isNull()) {
            return errorJson("'todos' must be an array");
        }
        todos.write(scope, items);
        return listJson(scope);
    }

    /** Serializes the current list as {@code {"todos":[...]}} — the shape both actions return. */
    private String listJson(String scope) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (TodoItem t : todos.read(scope)) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("id", t.id());
            o.put("content", t.content());
            o.put("status", t.status());
            if (t.priority() != null) {
                o.put("priority", t.priority());
            }
            arr.add(o);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.set("todos", arr);
        return out.toString();
    }

    private static String errorJson(String message) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("error", message);
        return o.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }
}
