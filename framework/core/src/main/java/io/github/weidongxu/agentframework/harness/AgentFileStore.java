package io.github.weidongxu.agentframework.harness;

import java.io.IOException;
import java.util.List;

/**
 * A pluggable file storage backend for {@link FileAccessProvider}, mirroring MAF's {@code AgentFileStore}.
 * Implementations operate on a shared, persistent folder tree addressed by forward-slash relative paths
 * (e.g. {@code "reports/2024/summary.md"}). The default implementation is {@link LocalFileStore}.
 */
public interface AgentFileStore {
    /** Returns whether a file exists at the relative path. */
    boolean fileExists(String path) throws IOException;

    /** Returns the file content, or {@code null} if it does not exist. */
    String read(String path) throws IOException;

    /** Creates or overwrites the file with the given content, creating parent directories as needed. */
    void write(String path, String content) throws IOException;

    /** Deletes the file. Returns {@code true} if a file was deleted, {@code false} if none existed. */
    boolean delete(String path) throws IOException;

    /**
     * Lists the direct children of a directory (empty string = store root). Directories are returned
     * alongside files; callers sort/filter as needed.
     */
    List<FileStoreEntry> listChildren(String directory) throws IOException;

    /** Lists all file paths (relative to the store root) under a directory, recursively. */
    List<String> listFilesRecursively(String directory) throws IOException;
}
