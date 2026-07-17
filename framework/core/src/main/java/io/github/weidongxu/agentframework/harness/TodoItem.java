package io.github.weidongxu.agentframework.harness;

/**
 * A single todo item tracked by {@link TodoProvider}. Mirrors the MAF .NET {@code TodoItem} /
 * Python {@code TodoItem}: an integer {@code id}, a {@code title}, an optional {@code description},
 * and a {@code complete} flag.
 */
public final class TodoItem {
    private final int id;
    private String title;
    private String description;
    private boolean complete;

    public TodoItem(int id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
}
