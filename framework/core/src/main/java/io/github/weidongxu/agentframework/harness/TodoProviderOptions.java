package io.github.weidongxu.agentframework.harness;

import java.util.List;
import java.util.function.Function;

/**
 * Options for {@link TodoProvider}. All settings are optional; {@link #defaults()} uses the built-in
 * instructions and injects the current todo list as a synthetic user message each turn (matching the
 * MAF {@code TodoProviderOptions}).
 */
public final class TodoProviderOptions {
    private String instructions;
    private boolean suppressTodoListMessage;
    private Function<List<TodoItem>, String> todoListMessageBuilder;

    public static TodoProviderOptions defaults() {
        return new TodoProviderOptions();
    }

    /** Overrides the default guidance injected into the agent's instructions. */
    public String getInstructions() {
        return instructions;
    }

    public TodoProviderOptions setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /** When {@code true}, the current-todo-list user message is not injected each turn. */
    public boolean isSuppressTodoListMessage() {
        return suppressTodoListMessage;
    }

    public TodoProviderOptions setSuppressTodoListMessage(boolean suppressTodoListMessage) {
        this.suppressTodoListMessage = suppressTodoListMessage;
        return this;
    }

    /** Optional custom renderer for the injected todo-list message. */
    public Function<List<TodoItem>, String> getTodoListMessageBuilder() {
        return todoListMessageBuilder;
    }

    public TodoProviderOptions setTodoListMessageBuilder(
            Function<List<TodoItem>, String> todoListMessageBuilder) {
        this.todoListMessageBuilder = todoListMessageBuilder;
        return this;
    }
}
