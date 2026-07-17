package io.github.weidongxu.agentframework.harness;

import io.github.weidongxu.agentframework.agent.Agent;
import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;
import io.github.weidongxu.agentframework.agent.AgentResponse;
import io.github.weidongxu.agentframework.agent.AgentRunOptions;
import io.github.weidongxu.agentframework.agent.AgentSession;
import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.tool.FunctionTool;
import io.github.weidongxu.agentframework.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link AIContextProvider} that lets an agent delegate work to background agents asynchronously,
 * mirroring the MAF .NET {@code BackgroundAgentsProvider}.
 *
 * <p>A parent agent can start background tasks on named child agents, wait for their completion, and
 * retrieve or continue their results. Each task runs in its own {@link AgentSession} and executes
 * concurrently. The provider exposes six tools:</p>
 * <ul>
 *   <li>{@code background_agents_start_task} — start a task on a named agent; returns its task id.</li>
 *   <li>{@code background_agents_wait_for_first_completion} — block until the first of the given tasks completes.</li>
 *   <li>{@code background_agents_get_task_results} — retrieve a completed task's output.</li>
 *   <li>{@code background_agents_get_all_tasks} — list all tasks with ids, statuses, agents, descriptions.</li>
 *   <li>{@code background_agents_continue_task} — send follow-up input to a completed task's session.</li>
 *   <li>{@code background_agents_clear_completed_task} — remove a completed task and free its session.</li>
 * </ul>
 *
 * <p><strong>Security:</strong> supplied agents receive text input derived from the parent's context
 * and their output is fed back into the parent — only supply agents you trust.</p>
 */
public final class BackgroundAgentsProvider extends AIContextProvider {
    private static final String STATE_KEY = BackgroundAgentsProvider.class.getName();

    private static final String DEFAULT_INSTRUCTIONS =
            "## BackgroundAgents\n"
                    + "You have access to background agents that can perform work on your behalf.\n"
                    + "\n"
                    + "- Use the `background_agents_*` list of tools to start tasks on background agents and check their results.\n"
                    + "- Creating a background task does not block, and background tasks run concurrently.\n"
                    + "- Important: Always wait for outstanding tasks to finish before you finish processing.\n"
                    + "- Important: After retrieving results from a completed task, clear it with background_agents_clear_completed_task to free memory, unless you plan to continue it with background_agents_continue_task.\n"
                    + "\n"
                    + "{background_agents}";

    private final Map<String, Agent> agents;
    private final String instructions;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final Object nullSessionLock = new Object();
    private TaskState fallbackState;

    public BackgroundAgentsProvider(Iterable<Agent> agents) {
        this(agents, BackgroundAgentsProviderOptions.defaults());
    }

    public BackgroundAgentsProvider(Iterable<Agent> agents, BackgroundAgentsProviderOptions options) {
        Objects.requireNonNull(agents, "agents");
        Objects.requireNonNull(options, "options");
        this.agents = validateAndBuild(agents);
        String base = options.getInstructions() != null ? options.getInstructions() : DEFAULT_INSTRUCTIONS;
        String agentList = options.getAgentListBuilder() != null
                ? options.getAgentListBuilder().apply(Collections.unmodifiableMap(this.agents))
                : buildDefaultAgentListText(this.agents);
        this.instructions = base.replace("{background_agents}", agentList);
    }

    @Override
    public List<String> getStateKeys() {
        return Collections.singletonList(STATE_KEY);
    }

    /** Returns the tasks for the given session that are still running (refreshing status first). */
    public List<BackgroundTaskInfo> getIncompleteTasks(AgentSession session) {
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            refresh(state);
            List<BackgroundTaskInfo> incomplete = new ArrayList<>();
            for (BackgroundTaskInfo task : state.tasks) {
                if (task.getStatus() == BackgroundTaskStatus.RUNNING) {
                    incomplete.add(task);
                }
            }
            return incomplete;
        }
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        AgentSession session = context.getSession();
        AIContext.Builder builder = AIContext.builder()
                .instructions(instructions)
                .tool(startTaskTool(session))
                .tool(waitForFirstTool(session))
                .tool(getResultsTool(session))
                .tool(getAllTool(session))
                .tool(continueTool(session))
                .tool(clearTool(session));
        return CompletableFuture.completedFuture(builder.build());
    }

    // ----- tools --------------------------------------------------------------------------------

    private Tool startTaskTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "agentName", stringProperty("The name of the background agent to delegate the task to."),
                        "input", stringProperty("The request to pass to the background agent."),
                        "description", stringProperty("A description of the task used to identify it later.")),
                java.util.Arrays.asList("agentName", "input", "description"));
        return new FunctionTool(
                "background_agents_start_task",
                "Start a background task on a named background agent. Returns a confirmation message containing the task ID.",
                schema,
                arguments -> CompletableFuture.completedFuture(startTask(session, arguments)));
    }

    private Tool waitForFirstTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf("taskIds", arrayProperty(mapOf("type", "integer"), "The task IDs to wait on.")),
                Collections.singletonList("taskIds"));
        return new FunctionTool(
                "background_agents_wait_for_first_completion",
                "Block until the first of the specified background tasks completes. Provide one or more task IDs. "
                        + "Returns a status message containing the ID of the task that completed first.",
                schema,
                arguments -> waitForFirst(session, arguments));
    }

    private Tool getResultsTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf("taskId", intProperty("The ID of the task whose output to retrieve.")),
                Collections.singletonList("taskId"));
        return new FunctionTool(
                "background_agents_get_task_results",
                "Get the text output of a background task by its ID. Returns the result text if complete, "
                        + "or status information if still running or failed.",
                schema,
                arguments -> CompletableFuture.completedFuture(getResults(session, arguments)));
    }

    private Tool getAllTool(AgentSession session) {
        return new FunctionTool(
                "background_agents_get_all_tasks",
                "List all background tasks with their IDs, statuses, agent names, and descriptions.",
                objectSchema(new LinkedHashMap<>(), Collections.emptyList()),
                arguments -> CompletableFuture.completedFuture(getAll(session)));
    }

    private Tool continueTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf(
                        "taskId", intProperty("The ID of the completed task to continue."),
                        "text", stringProperty("The follow-up input to send to the task's session.")),
                java.util.Arrays.asList("taskId", "text"));
        return new FunctionTool(
                "background_agents_continue_task",
                "Send follow-up input to a completed or failed background task to resume its work. The background "
                        + "task's session is preserved, so the agent retains conversational context.",
                schema,
                arguments -> CompletableFuture.completedFuture(continueTask(session, arguments)));
    }

    private Tool clearTool(AgentSession session) {
        Map<String, Object> schema = objectSchema(
                mapOf("taskId", intProperty("The ID of the completed task to clear.")),
                Collections.singletonList("taskId"));
        return new FunctionTool(
                "background_agents_clear_completed_task",
                "Remove a completed or failed background task and release its session to free memory. Use this after "
                        + "retrieving results when you no longer need to continue the task.",
                schema,
                arguments -> CompletableFuture.completedFuture(clear(session, arguments)));
    }

    // ----- tool implementations -----------------------------------------------------------------

    private String startTask(AgentSession session, Map<String, Object> arguments) {
        String agentName = trimOrNull(str(arguments.get("agentName")));
        String input = str(arguments.get("input"));
        String description = str(arguments.get("description"));
        Agent agent = agentName == null ? null : agents.get(lookupKey(agentName));
        if (agent == null) {
            return "Error: No background agent found with name '" + agentName + "'. Available agents: "
                    + String.join(", ", agents.keySet());
        }
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            int taskId = state.nextId++;
            BackgroundTaskInfo info = new BackgroundTaskInfo(
                    taskId, agent.getName(), description == null ? "" : description, BackgroundTaskStatus.RUNNING);
            state.tasks.add(info);
            startRun(state, lock, agent, taskId, input, true);
            return "Background task " + taskId + " started on agent '" + agent.getName() + "'.";
        }
    }

    private CompletionStage<String> waitForFirst(AgentSession session, Map<String, Object> arguments) {
        List<Integer> taskIds = toIntList(arguments.get("taskIds"));
        if (taskIds.isEmpty()) {
            return CompletableFuture.completedFuture("Error: No task IDs provided.");
        }
        List<Integer> waitable = new ArrayList<>();
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            for (Integer id : taskIds) {
                if (state.inFlight.containsKey(id)) {
                    waitable.add(id);
                }
            }
            if (waitable.isEmpty()) {
                refresh(state);
                for (BackgroundTaskInfo task : state.tasks) {
                    if (taskIds.contains(task.getId()) && task.getStatus() != BackgroundTaskStatus.RUNNING) {
                        return CompletableFuture.completedFuture(
                                "Task " + task.getId() + " is not running; current status: " + task.getStatus() + ".");
                    }
                }
                return CompletableFuture.completedFuture(
                        "Error: None of the specified task IDs correspond to running tasks.");
            }
            CompletableFuture<Integer> firstDone = new CompletableFuture<>();
            for (Integer id : waitable) {
                CompletableFuture<AgentResponse> future = state.inFlight.get(id);
                future.whenComplete((response, error) -> firstDone.complete(id));
            }
            return firstDone.thenApply(id -> {
                synchronized (lock) {
                    TaskState current = state(session);
                    BackgroundTaskInfo task = findTask(current, id);
                    if (task != null) {
                        finalizeTask(current, task);
                    }
                    return "Task " + id + " finished with status: "
                            + (task != null ? task.getStatus() : "Unknown") + ".";
                }
            });
        }
    }

    private String getResults(AgentSession session, Map<String, Object> arguments) {
        Integer taskId = toInt(arguments.get("taskId"));
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            refresh(state);
            BackgroundTaskInfo task = taskId == null ? null : findTask(state, taskId);
            if (task == null) {
                return "Error: No task found with ID " + taskId + ".";
            }
            switch (task.getStatus()) {
                case COMPLETED:
                    return task.getResultText() != null ? task.getResultText() : "(no output)";
                case FAILED:
                    return "Task failed: " + (task.getErrorText() != null ? task.getErrorText() : "Unknown error");
                case LOST:
                    return "Task state was lost (reference unavailable).";
                case RUNNING:
                    return "Task " + taskId + " is still running.";
                default:
                    return "Task " + taskId + " has status: " + task.getStatus() + ".";
            }
        }
    }

    private String getAll(AgentSession session) {
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            refresh(state);
            if (state.tasks.isEmpty()) {
                return "No tasks.";
            }
            StringBuilder sb = new StringBuilder("Tasks:\n");
            for (BackgroundTaskInfo task : state.tasks) {
                sb.append("- Task ").append(task.getId()).append(" [").append(task.getStatus())
                        .append("] (").append(task.getAgentName()).append("): ")
                        .append(task.getDescription()).append('\n');
            }
            return sb.toString();
        }
    }

    private String continueTask(AgentSession session, Map<String, Object> arguments) {
        Integer taskId = toInt(arguments.get("taskId"));
        String text = str(arguments.get("text"));
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            refresh(state);
            BackgroundTaskInfo task = taskId == null ? null : findTask(state, taskId);
            if (task == null) {
                return "Error: No task found with ID " + taskId + ".";
            }
            if (task.getStatus() == BackgroundTaskStatus.LOST) {
                return "Error: Task " + taskId + " cannot be continued because its session was lost "
                        + "(e.g., after a session restore). Start a new task instead.";
            }
            if (task.getStatus() == BackgroundTaskStatus.RUNNING) {
                return "Error: Task " + taskId + " is still running. Wait for it to complete before continuing.";
            }
            Agent agent = agents.get(lookupKey(task.getAgentName()));
            if (agent == null) {
                return "Error: Agent '" + task.getAgentName() + "' is no longer available.";
            }
            if (!state.subSessions.containsKey(taskId)) {
                return "Error: Session for task " + taskId + " is no longer available.";
            }
            task.setStatus(BackgroundTaskStatus.RUNNING);
            task.setResultText(null);
            task.setErrorText(null);
            startRun(state, lock, agent, taskId, text, false);
            return "Task " + taskId + " continued with new input.";
        }
    }

    private String clear(AgentSession session, Map<String, Object> arguments) {
        Integer taskId = toInt(arguments.get("taskId"));
        Object lock = sessionLock(session);
        synchronized (lock) {
            TaskState state = state(session);
            refresh(state);
            BackgroundTaskInfo task = taskId == null ? null : findTask(state, taskId);
            if (task == null) {
                return "Error: No task found with ID " + taskId + ".";
            }
            if (task.getStatus() == BackgroundTaskStatus.RUNNING) {
                return "Error: Task " + taskId + " is still running. Wait for it to complete before clearing.";
            }
            state.tasks.remove(task);
            state.inFlight.remove(taskId);
            state.subSessions.remove(taskId);
            return "Task " + taskId + " cleared.";
        }
    }

    // ----- runtime ------------------------------------------------------------------------------

    /** Starts an agent run for a task, reusing the sub-session when it already exists. */
    private void startRun(TaskState state, Object lock, Agent agent, int taskId, String input, boolean newSession) {
        List<ChatMessage> messages = Collections.singletonList(ChatMessage.user(input == null ? "" : input));
        CompletableFuture<AgentResponse> future;
        if (newSession) {
            future = agent.createSession().toCompletableFuture().thenCompose(sub -> {
                synchronized (lock) {
                    state.subSessions.put(taskId, sub);
                }
                return agent.run(messages, sub, AgentRunOptions.empty());
            }).toCompletableFuture();
        } else {
            AgentSession sub = state.subSessions.get(taskId);
            future = agent.run(messages, sub, AgentRunOptions.empty()).toCompletableFuture();
        }
        state.inFlight.put(taskId, future);
    }

    /** Refreshes the status of running tasks whose in-flight futures have completed. */
    private void refresh(TaskState state) {
        for (BackgroundTaskInfo task : state.tasks) {
            if (task.getStatus() != BackgroundTaskStatus.RUNNING) {
                continue;
            }
            CompletableFuture<AgentResponse> future = state.inFlight.get(task.getId());
            if (future == null) {
                task.setStatus(BackgroundTaskStatus.LOST);
                continue;
            }
            if (future.isDone()) {
                finalizeTask(state, task);
            }
        }
    }

    /** Finalizes a task whose future has completed, extracting the result or error. */
    private void finalizeTask(TaskState state, BackgroundTaskInfo task) {
        CompletableFuture<AgentResponse> future = state.inFlight.get(task.getId());
        if (future == null || !future.isDone()) {
            return;
        }
        try {
            AgentResponse response = future.getNow(null);
            task.setStatus(BackgroundTaskStatus.COMPLETED);
            task.setResultText(response != null ? response.getText() : null);
        } catch (Throwable error) {
            task.setStatus(BackgroundTaskStatus.FAILED);
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            task.setErrorText(cause.getMessage() != null ? cause.getMessage() : "Unknown error");
        }
        state.inFlight.remove(task.getId());
    }

    // ----- state --------------------------------------------------------------------------------

    private TaskState state(AgentSession session) {
        if (session == null) {
            if (fallbackState == null) {
                fallbackState = new TaskState();
            }
            return fallbackState;
        }
        Object existing = session.get(STATE_KEY);
        if (existing instanceof TaskState) {
            return (TaskState) existing;
        }
        TaskState created = new TaskState();
        session.put(STATE_KEY, created);
        return created;
    }

    private Object sessionLock(AgentSession session) {
        if (session == null) {
            return nullSessionLock;
        }
        return sessionLocks.computeIfAbsent(session.getId(), k -> new Object());
    }

    private static BackgroundTaskInfo findTask(TaskState state, int id) {
        for (BackgroundTaskInfo task : state.tasks) {
            if (task.getId() == id) {
                return task;
            }
        }
        return null;
    }

    private static final class TaskState {
        final List<BackgroundTaskInfo> tasks = new ArrayList<>();
        final Map<Integer, CompletableFuture<AgentResponse>> inFlight = new ConcurrentHashMap<>();
        final Map<Integer, AgentSession> subSessions = new ConcurrentHashMap<>();
        int nextId = 1;
    }

    // ----- helpers ------------------------------------------------------------------------------

    private static Map<String, Agent> validateAndBuild(Iterable<Agent> agents) {
        Map<String, Agent> dict = new LinkedHashMap<>();
        for (Agent agent : agents) {
            String name = agent.getName();
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("All background agents must have a non-empty name.");
            }
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (dict.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Duplicate background agent name: '" + name + "'. Agent names must be unique (case-insensitive).");
            }
            dict.put(key, agent);
        }
        if (dict.isEmpty()) {
            throw new IllegalArgumentException("At least one background agent must be provided.");
        }
        return dict;
    }

    private String lookupKey(String name) {
        return name == null ? null : name.toLowerCase(java.util.Locale.ROOT);
    }

    private static String buildDefaultAgentListText(Map<String, Agent> agents) {
        StringBuilder sb = new StringBuilder("Available background agents:\n");
        for (Agent agent : agents.values()) {
            sb.append("- ").append(agent.getName());
            String description = agent.getDescription();
            if (description != null && !description.trim().isEmpty()) {
                sb.append(": ").append(description);
            }
            sb.append('\n');
        }
        return sb.toString();
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

    @SuppressWarnings("unchecked")
    private static List<Integer> toIntList(Object value) {
        List<Integer> ids = new ArrayList<>();
        if (value instanceof List) {
            for (Object element : (List<Object>) value) {
                Integer id = toInt(element);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
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
}
