package io.github.weidongxu.agentframework.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * An {@link AgentFileStore} backed by a directory on the local file system. All paths are resolved
 * relative to a fixed root and validated so operations cannot escape it (no {@code ..} traversal,
 * no absolute paths). Supports arbitrary subdirectory nesting.
 */
public final class LocalFileStore implements AgentFileStore {
    private final Path root;

    public LocalFileStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    private Path resolve(String path) throws IOException {
        String normalized = normalize(path);
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("path escapes the store root: " + path);
        }
        return resolved;
    }

    /** Normalizes a store-relative path, rejecting absolute paths and parent traversal. */
    static String normalize(String path) throws IOException {
        if (path == null) {
            throw new IOException("path must not be null");
        }
        String cleaned = path.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return "";
        }
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..")) {
                throw new IOException("parent traversal is not allowed: " + path);
            }
        }
        return cleaned;
    }

    @Override
    public boolean fileExists(String path) throws IOException {
        return Files.isRegularFile(resolve(path));
    }

    @Override
    public String read(String path) throws IOException {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Override
    public void write(String path, String content) throws IOException {
        Path file = resolve(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean delete(String path) throws IOException {
        return Files.deleteIfExists(resolve(path));
    }

    @Override
    public List<FileStoreEntry> listChildren(String directory) throws IOException {
        Path dir = resolve(directory);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<FileStoreEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted().forEach(p ->
                    entries.add(new FileStoreEntry(
                            p.getFileName().toString(), Files.isDirectory(p))));
        }
        return entries;
    }

    @Override
    public List<String> listFilesRecursively(String directory) throws IOException {
        Path dir = resolve(directory);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }
}
