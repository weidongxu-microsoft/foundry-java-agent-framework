package io.github.weidongxu.agentframework.harness;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** Options for {@link FileMemoryProvider}. Mirrors the MAF {@code FileMemoryProviderOptions}. */
public final class FileMemoryProviderOptions {
    private Path baseDirectory;
    private String instructions;

    /** Root folder under which per-session memory folders are created. */
    public Path getBaseDirectory() {
        if (baseDirectory != null) {
            return baseDirectory;
        }
        return Paths.get(System.getProperty("user.home"), ".agentframework", "file-memory");
    }

    public FileMemoryProviderOptions setBaseDirectory(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
        return this;
    }

    public String getInstructions() {
        return instructions;
    }

    public FileMemoryProviderOptions setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    public static FileMemoryProviderOptions defaults() {
        return new FileMemoryProviderOptions();
    }
}
