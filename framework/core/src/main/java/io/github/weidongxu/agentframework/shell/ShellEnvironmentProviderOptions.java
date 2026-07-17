package io.github.weidongxu.agentframework.shell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Configuration for {@link ShellEnvironmentProvider}. Mirrors MAF's {@code ShellEnvironmentProviderOptions}. */
public final class ShellEnvironmentProviderOptions {
    private List<String> probeTools =
            Collections.unmodifiableList(Arrays.asList("git", "java", "node", "python", "docker"));
    private ShellFamily overrideFamily;
    private Duration probeTimeout = Duration.ofSeconds(5);
    private Function<ShellEnvironmentSnapshot, String> instructionsFormatter;

    /** CLI tools whose {@code --version} output is probed and surfaced. */
    public List<String> getProbeTools() {
        return probeTools;
    }

    public ShellEnvironmentProviderOptions setProbeTools(List<String> probeTools) {
        Objects.requireNonNull(probeTools, "probeTools");
        this.probeTools = Collections.unmodifiableList(new ArrayList<>(probeTools));
        return this;
    }

    /** Overrides the auto-detected shell family. When {@code null}, it is inferred from the OS. */
    public ShellFamily getOverrideFamily() {
        return overrideFamily;
    }

    public ShellEnvironmentProviderOptions setOverrideFamily(ShellFamily overrideFamily) {
        this.overrideFamily = overrideFamily;
        return this;
    }

    public Duration getProbeTimeout() {
        return probeTimeout;
    }

    public ShellEnvironmentProviderOptions setProbeTimeout(Duration probeTimeout) {
        this.probeTimeout = Objects.requireNonNull(probeTimeout, "probeTimeout");
        return this;
    }

    /** Optional custom instructions formatter. When {@code null}, the built-in formatter is used. */
    public Function<ShellEnvironmentSnapshot, String> getInstructionsFormatter() {
        return instructionsFormatter;
    }

    public ShellEnvironmentProviderOptions setInstructionsFormatter(
            Function<ShellEnvironmentSnapshot, String> instructionsFormatter) {
        this.instructionsFormatter = instructionsFormatter;
        return this;
    }

    public static ShellEnvironmentProviderOptions defaults() {
        return new ShellEnvironmentProviderOptions();
    }
}
