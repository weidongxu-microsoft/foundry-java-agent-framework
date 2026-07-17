package io.github.weidongxu.agentframework.shell;

import io.github.weidongxu.agentframework.agent.AIContext;
import io.github.weidongxu.agentframework.agent.AIContextProvider;
import io.github.weidongxu.agentframework.agent.AgentInvokingContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * An {@link AIContextProvider} that probes the underlying shell (OS, shell family/version, working
 * directory, available CLI tools) once per provider and injects an authoritative instructions block
 * so the agent emits commands in the correct shell idiom. Mirrors MAF's {@code ShellEnvironmentProvider}.
 *
 * <p>It adds no tools — it augments {@link AIContext#getInstructions()} only. Probe failures (missing
 * CLI, timeout) never fail the agent: the model simply sees fewer hints. The snapshot is captured on
 * the first invocation and reused thereafter; call {@link #refresh()} to re-probe.</p>
 */
public final class ShellEnvironmentProvider extends AIContextProvider {
    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final ShellExecutor executor;
    private final ShellEnvironmentProviderOptions options;
    private final Function<ShellEnvironmentSnapshot, String> formatter;
    private volatile ShellEnvironmentSnapshot snapshot;

    public ShellEnvironmentProvider(ShellExecutor executor) {
        this(executor, ShellEnvironmentProviderOptions.defaults());
    }

    public ShellEnvironmentProvider(ShellExecutor executor, ShellEnvironmentProviderOptions options) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.options = Objects.requireNonNull(options, "options");
        this.formatter = options.getInstructionsFormatter() != null
                ? options.getInstructionsFormatter()
                : ShellEnvironmentProvider::defaultInstructionsFormatter;
    }

    /** The most recently captured snapshot, or {@code null} if no probe has completed. */
    public ShellEnvironmentSnapshot getCurrentSnapshot() {
        return snapshot;
    }

    /** Forces a re-probe and refreshes the cached snapshot. */
    public ShellEnvironmentSnapshot refresh() {
        ShellEnvironmentSnapshot fresh = probe();
        this.snapshot = fresh;
        return fresh;
    }

    @Override
    protected CompletionStage<AIContext> provide(AgentInvokingContext context) {
        ShellEnvironmentSnapshot current = snapshot;
        if (current == null) {
            synchronized (this) {
                if (snapshot == null) {
                    snapshot = probe();
                }
                current = snapshot;
            }
        }
        return CompletableFuture.completedFuture(
                AIContext.builder().instructions(formatter.apply(current)).build());
    }

    private ShellEnvironmentSnapshot probe() {
        ShellFamily family = options.getOverrideFamily() != null
                ? options.getOverrideFamily() : LocalShellExecutor.detectFamily();

        String[] shellAndCwd = probeShellAndCwd(family);
        Map<String, String> toolVersions = new LinkedHashMap<>();
        for (String tool : options.getProbeTools()) {
            if (tool == null || toolVersions.containsKey(tool)) {
                continue;
            }
            toolVersions.put(tool, probeToolVersion(tool));
        }
        return new ShellEnvironmentSnapshot(
                family,
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
                shellAndCwd[0],
                shellAndCwd[1],
                toolVersions);
    }

    private String[] probeShellAndCwd(ShellFamily family) {
        String probe = family == ShellFamily.POWERSHELL
                ? "Write-Output (\"VERSION=\" + $PSVersionTable.PSVersion.ToString()); "
                        + "Write-Output (\"CWD=\" + (Get-Location).Path)"
                : "echo \"VERSION=${BASH_VERSION:-${ZSH_VERSION:-unknown}}\"; echo \"CWD=$PWD\"";
        ShellResult result = runProbe(probe);
        if (result == null) {
            return new String[] {null, ""};
        }
        String version = null;
        String cwd = "";
        for (String line : result.getStdout().split("[\r\n]+")) {
            if (line.startsWith("VERSION=")) {
                String v = line.substring("VERSION=".length()).trim();
                version = v.isEmpty() || "unknown".equals(v) ? null : v;
            } else if (line.startsWith("CWD=")) {
                cwd = line.substring("CWD=".length()).trim();
            }
        }
        return new String[] {version, cwd};
    }

    private String probeToolVersion(String tool) {
        if (tool.isEmpty() || !TOOL_NAME.matcher(tool).matches()) {
            return null;
        }
        ShellResult result = runProbe(tool + " --version");
        if (result == null || result.getExitCode() != 0) {
            return null;
        }
        String firstLine = firstNonEmptyLine(result.getStdout());
        if (firstLine == null) {
            firstLine = firstNonEmptyLine(result.getStderr());
        }
        return firstLine == null || firstLine.trim().isEmpty() ? null : firstLine.trim();
    }

    private ShellResult runProbe(String command) {
        try {
            return executor.run(command, options.getProbeTimeout());
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmptyLine(String text) {
        for (String line : text.split("[\r\n]+")) {
            if (!line.trim().isEmpty()) {
                return line;
            }
        }
        return null;
    }

    /** The built-in instructions formatter. Public so callers can wrap or augment it. */
    public static String defaultInstructionsFormatter(ShellEnvironmentSnapshot snapshot) {
        StringBuilder sb = new StringBuilder("## Shell environment\n");
        String version = snapshot.getShellVersion() == null ? "" : " " + snapshot.getShellVersion();
        if (snapshot.getFamily() == ShellFamily.POWERSHELL) {
            sb.append("You are operating a PowerShell").append(version)
                    .append(" session on ").append(snapshot.getOsDescription()).append(".\n");
            sb.append("Use PowerShell idioms, NOT bash:\n");
            sb.append("- Set environment variables with `$env:NAME = 'value'` (NOT `NAME=value`).\n");
            sb.append("- Change directory with `Set-Location` or `cd`. Paths use `\\` separators.\n");
            sb.append("- Reference environment variables as `$env:NAME` (NOT `$NAME`).\n");
            sb.append("- Pipe to `Out-Null` to suppress output (NOT `> /dev/null`).\n");
        } else {
            sb.append("You are operating a POSIX shell").append(version)
                    .append(" session on ").append(snapshot.getOsDescription()).append(".\n");
            sb.append("Use POSIX shell idioms (bash/sh).\n");
            sb.append("- Set environment variables for the next command with `export NAME=value`.\n");
            sb.append("- Reference environment variables as `$NAME` or `${NAME}`.\n");
            sb.append("- Paths use `/` separators.\n");
        }
        if (!snapshot.getWorkingDirectory().isEmpty()) {
            sb.append("Working directory: ").append(snapshot.getWorkingDirectory()).append("\n");
        }
        StringBuilder installed = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        for (Map.Entry<String, String> entry : snapshot.getToolVersions().entrySet()) {
            if (entry.getValue() != null) {
                if (installed.length() > 0) {
                    installed.append(", ");
                }
                installed.append(entry.getKey()).append(" (").append(entry.getValue()).append(")");
            } else {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(entry.getKey());
            }
        }
        if (installed.length() > 0) {
            sb.append("Available CLIs: ").append(installed).append("\n");
        }
        if (missing.length() > 0) {
            sb.append("Not installed: ").append(missing).append("\n");
        }
        return sb.toString().replaceAll("\\s+$", "");
    }
}
