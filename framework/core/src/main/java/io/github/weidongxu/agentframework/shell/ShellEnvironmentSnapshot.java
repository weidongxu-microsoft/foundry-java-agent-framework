package io.github.weidongxu.agentframework.shell;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An immutable snapshot of the probed shell environment. Mirrors MAF's {@code ShellEnvironmentSnapshot}. */
public final class ShellEnvironmentSnapshot {
    private final ShellFamily family;
    private final String osDescription;
    private final String shellVersion;
    private final String workingDirectory;
    private final Map<String, String> toolVersions;

    public ShellEnvironmentSnapshot(
            ShellFamily family,
            String osDescription,
            String shellVersion,
            String workingDirectory,
            Map<String, String> toolVersions) {
        this.family = family;
        this.osDescription = osDescription == null ? "" : osDescription;
        this.shellVersion = shellVersion;
        this.workingDirectory = workingDirectory == null ? "" : workingDirectory;
        this.toolVersions = Collections.unmodifiableMap(
                new LinkedHashMap<>(toolVersions == null ? Collections.emptyMap() : toolVersions));
    }

    public ShellFamily getFamily() {
        return family;
    }

    public String getOsDescription() {
        return osDescription;
    }

    /** The shell version, or {@code null} if it could not be probed. */
    public String getShellVersion() {
        return shellVersion;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /** Map of probed CLI tool name to version line, or {@code null} value if not installed. */
    public Map<String, String> getToolVersions() {
        return toolVersions;
    }
}
