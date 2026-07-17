package io.github.weidongxu.agentframework.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link AIContextProvider} that gives an agent a todo list for tracking work across turns of a
 * long-running task, mirroring the MAF .NET {@code TodoProvider} / Python {@code TodoProvider}.
 *
 * <p>Each turn it (1) injects planning instructions, (2) contributes five read/write tools, and
 * (3) unless suppressed, injects a synthetic user message summarizing the current list so the agent
 * is aware of outstanding work. Todo state lives in the {@link AgentSession} state bag and therefore
 * persists across invocations within the same session. All mutations are serialized by a per-session
 * lock.</p>
 *
 * <p>Tools (all auto-approved, executed locally in the function-invocation loop):</p>
 * <ul>
 *   <li>{@code todos_add} — add one or more items ({@code title} + optional {@code description}).</li>
 *   <li>{@code todos_complete} — mark items complete by {@code id} (with a {@code reason}).</li>
 *   <li>{@code todos_remove} — remove items by {@code id}.</li>
 *   <li>{@code todos_get_remaining} — list incomplete items.</li>
 *   <li>{@code todos_get_all} — list all items.</li>
 * </ul>
 */
public final class TodoProvider extends AIContextProvider {
    private static final String STATE_KEY = TodoProvider.class.getName();

    private static final String DEFAULT_INSTRUCTIONS =
            "## Todo Items\n"
                    + "\n"
                    + "You have access to a todo list for tracking work items.\n"
                    + "When a user asks you to perform a task, follow these steps to manage your work:\n"
                    + "1. Determine whether the ask requires multiple steps (complex) or a single step (simple).\n"
                    + "2. If complex, break the task into manageable todo items and add them to the list.\n"
                    + "3. If simple, don't add a todo item; just complete the task directly.\n"
                    + "\n"
                    + "### General TODO Guidelines\n"
                    + "Ask the user for clarification where needed to create effective todos.\n"
                    + "If the user provides feedback on your plan, adjust your todos by adding or removing items.\n"
                    + "During execution, mark items complete when finished and remove items no longer needed.\n"
                    + "When the user changes topic or switches request, update the list accordingly.\n"
                    + "\n"
                    + "Use these tools to manage your tasks:\n"
                    + "- Use todos_add to break complex work into trackable items (one or many at once).\n"
                    + "- Use todos_complete to mark items done (one or many). Include a reason describing how each was completed.\n"
                    + "- Use todos_get_remaining to check what work is still pending.\n"
                    + "- Use todos_get_all to review the full list including completed items.\n"
                    + "- Use todos_remove to remove items no longer needed (one or many at once).";

    private final ObjectMapper objectMapper;
    private final String instructions;
    private final boolean suppressTodoListMessage;
    private final java.util.function.Function<List<TodoItem>, String> todoListMessageBuilder;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final Object nullSessionLock = new Object();
    private TodoState fallbackState;

    public TodoProvider() {
        this(TodoProviderOptions.defaults(), new ObjectMapper());
    }

    public TodoProvider(TodoProviderOptions options) {
        this(options, new ObjectMapper());
    }

    public TodoProvider(TodoProviderOptions options, ObjectMapper objectMapper) {
        Objects.requireNonNull(options, "options");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.instructions = options.getInstructions() != null
                ? options.getInstructions() : DEFAULT_INSTRUCTIONS;
        this.suppressTodoListMessage = options.isSuppressTodoListMessage();
        this.todoListMessageBuilder = options.getTodoListMessageBuilder();
    }

    @Override
    public List<String> getStateKeys() {
        return java.util.Collections.singletonList(STATE_KEY);
    }

    /** Returns a snapshot of all todo items in the session (live copies are not exposed). */
    public List<TodoItem> getAllTodos(AgentSession session) {
        Object lock = sessionLock(session);
        synchronized (lock) {
            return new ArrayList<>(state(session).items);
        }
    }

    /** Returns a snapshot of the incomplete todo items in the session. */
    public List<TodoItem> getRemainingTodos(AgentSession session) {
        Object lock = sessionLock(session);
        synchronized (lock) {
            List<TodoItem> remaining = new ArrayList<>();
            for (TodoItem item : state(session).items) {
                if (!item.isComplete()) {
                    remaining.add(item);
                }
            }
            return remaining;
        }
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        AgentSession session = context.getSession();
        AIContext.Builder builder = AIContext.builder()
                .instructions(instructions)
                .tool(addTool(session))
                .tool(completeTool(session))
                .tool(removeTool(session))
                .tool(getRemainingTool(session))
                .tool(getAllTool(session));
        if (!suppressTodoListMessage) {
            List<TodoItem> current;
            Object lock = sessionLock(session);
            synchronized (lock) {
                current = new ArrayList<>(state(session).items);
            }
            String message = todoListMessageBuilder != null
                    ? todoListMessageBuilder.apply(current)
                    : formatTodoListMessage(current);
            builder.message(ChatMessage.user(message));
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    // ----- tools --------------------------------------------------------------------------------

    private Tool addTool(AgentSession session) {
        Map<String, Object> itemSchema = objectSchema(
                mapOf(
                        "title", stringProperty("The todo item's title."),
                        "description", stringProperty("An optional longer description.")),
                singletonList("title"));
        Map<String, Object> schema = objectSchema(
                mapOf("todos", arrayProperty(itemSchema, "The todo items to add.")),
                singletonList("todos"));
        return new FunctionTool(
                "todos_add",
                "Add one or more todo items. Each item has a title and an optional description. "
                        + "Returns the list of created todo items.",
                schema,
                arguments -> CompletableFuture.completedFuture(add(session, arguments)));
    }

    private Tool completeTool(AgentSession session) {
        Map<String, Object> itemSchema = objectSchema(
                mapOf(
                        "id", intProperty("The id of the todo item to complete."),
                        "reason", stringProperty("How or why the item was completed.")),
                java.util.Arrays.asList("id", "reason"));
        Map<String, Object> schema = objectSchema(
                mapOf("items", arrayProperty(itemSchema, "The items to mark complete.")),
                singletonList("items"));
        return new FunctionTool(
                "todos_complete",
                "Mark one or more todo items as complete. Each entry has an id and a reason. "
                        + "Returns the number of items marked complete.",
                schema,
                arguments -> CompletableFuture.completedFuture(complete(session, arguments)));
    }

    private Tool removeTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf("ids", arrayProperty(mapOf("type", "integer"), "The ids to remove.")),
                singletonList("ids"));
        return new FunctionTool(
                "todos_remove",
                "Remove one or more todo items by their ids. Returns the number of items removed.",
                schema,
                arguments -> CompletableFuture.completedFuture(remove(session, arguments)));
    }

    private Tool getRemainingTool(AgentSession session) {
        return new FunctionTool(
                "todos_get_remaining",
                "Retrieve the list of incomplete todo items.",
                objectSchema(new LinkedHashMap<>(), java.util.Collections.emptyList()),
                arguments -> CompletableFuture.completedFuture(toJson(getRemainingTodos(session))));
    }

    private Tool getAllTool(AgentSession session) {
        return new FunctionTool(
                "todos_get_all",
                "Retrieve the full list of todo items, both complete and incomplete.",
                objectSchema(new LinkedHashMap<>(), java.util.Collections.emptyList()),
                arguments -> CompletableFuture.completedFuture(toJson(getAllTodos(session))));
    }

    @SuppressWarnings("unchecked")
    private String add(AgentSession session, Map<String, Object> arguments) {
        Object raw = arguments.get("todos");
        List<TodoItem> created = new ArrayList<>();
        Object lock = sessionLock(session);
        synchronized (lock) {
            TodoState state = state(session);
            if (raw instanceof List) {
                for (Object element : (List<Object>) raw) {
                    if (element instanceof Map) {
                        Map<String, Object> entry = (Map<String, Object>) element;
                        String title = trimOrNull(str(entry.get("title")));
                        if (title == null) {
                            continue;
                        }
                        TodoItem item = new TodoItem(
                                state.nextId++, title, trimOrNull(str(entry.get("description"))));
                        state.items.add(item);
                        created.add(item);
                    }
                }
            }
        }
        return toJson(created);
    }

    @SuppressWarnings("unchecked")
    private String complete(AgentSession session, Map<String, Object> arguments) {
        Object raw = arguments.get("items");
        int completed = 0;
        Object lock = sessionLock(session);
        synchronized (lock) {
            TodoState state = state(session);
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            if (raw instanceof List) {
                for (Object element : (List<Object>) raw) {
                    if (element instanceof Map) {
                        Integer id = toInt(((Map<String, Object>) element).get("id"));
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                }
            }
            for (TodoItem item : state.items) {
                if (!item.isComplete() && ids.contains(item.getId())) {
                    item.setComplete(true);
                    completed++;
                }
            }
        }
        return "{\"completed\":" + completed + "}";
    }

    @SuppressWarnings("unchecked")
    private String remove(AgentSession session, Map<String, Object> arguments) {
        Object raw = arguments.get("ids");
        int removed = 0;
        Object lock = sessionLock(session);
        synchronized (lock) {
            TodoState state = state(session);
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            if (raw instanceof List) {
                for (Object element : (List<Object>) raw) {
                    Integer id = toInt(element);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
            java.util.Iterator<TodoItem> it = state.items.iterator();
            while (it.hasNext()) {
                if (ids.contains(it.next().getId())) {
                    it.remove();
                    removed++;
                }
            }
        }
        return "{\"removed\":" + removed + "}";
    }

    // ----- state --------------------------------------------------------------------------------

    private TodoState state(AgentSession session) {
        if (session == null) {
            if (fallbackState == null) {
                fallbackState = new TodoState();
            }
            return fallbackState;
        }
        Object existing = session.get(STATE_KEY);
        if (existing instanceof TodoState) {
            return (TodoState) existing;
        }
        TodoState created = new TodoState();
        session.put(STATE_KEY, created);
        return created;
    }

    private Object sessionLock(AgentSession session) {
        if (session == null) {
            return nullSessionLock;
        }
        return sessionLocks.computeIfAbsent(session.getId(), k -> new Object());
    }

    private static final class TodoState {
        final List<TodoItem> items = new ArrayList<>();
        int nextId = 1;
    }

    static String formatTodoListMessage(List<TodoItem> items) {
        if (items.isEmpty()) {
            return "### Current todo list\n- none yet";
        }
        StringBuilder sb = new StringBuilder("### Current todo list\n");
        for (TodoItem item : items) {
            sb.append("- ").append(item.getId())
                    .append(" [").append(item.isComplete() ? "done" : "open").append("] ")
                    .append(item.getTitle());
            if (item.getDescription() != null && !item.getDescription().trim().isEmpty()) {
                sb.append(": ").append(item.getDescription());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ----- helpers ------------------------------------------------------------------------------

    private String toJson(List<TodoItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TodoItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("title", item.getTitle());
            row.put("description", item.getDescription());
            row.put("complete", item.isComplete());
            rows.add(row);
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(required));
        return schema;
    }

    private static Map<String, Object> arrayProperty(Map<String, Object> itemSchema, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "array");
        property.put("items", itemSchema);
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> intProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static List<String> singletonList(String value) {
        return java.util.Collections.singletonList(value);
    }
}
