package io.github.weidongxu.agentframework.harness;

import java.util.Objects;

/** A child entry (file or directory) returned by {@link AgentFileStore#listChildren(String)}. */
public final class FileStoreEntry {
    private final String name;
    private final boolean directory;

    public FileStoreEntry(String name, boolean directory) {
        this.name = Objects.requireNonNull(name, "name");
        this.directory = directory;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }
}
